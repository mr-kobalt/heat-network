package ru.lct.heating.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.springframework.stereotype.Component;

/**
 * E8-15d2c: потоковое пространственное партиционирование входа (NFR-08).
 * Вход читается дважды: проход 1 — bbox набора; затем тайлы по
 * {@code forest-partition-tile-m} и раскладка фич по тайлам в JSONL-файлы
 * (по одной фиче на строку). Объекты линии/полигоны дублируются во все тайлы,
 * чей bbox расширен на margin пересекает их габарит; точки подключения —
 * ровно в один тайл; источники — во все. Полные списки объектов в памяти не
 * удерживаются.
 */
@Component
public class DatasetPartitioner {

    private final GeoJsonStreamReader reader;
    private final ObjectMapper objectMapper;

    public DatasetPartitioner(GeoJsonStreamReader reader, ObjectMapper objectMapper) {
        this.reader = reader;
        this.objectMapper = objectMapper;
    }

    /** Тайл: bbox (в исходной СК), файл с фичами (JSONL) и их число. */
    public static final class Partition {
        private final int id;
        private final Envelope bounds;
        private final Path file;
        private long count;

        private Partition(int id, Envelope bounds, Path file) {
            this.id = id;
            this.bounds = bounds;
            this.file = file;
        }

        public int id() {
            return id;
        }

        public Envelope bounds() {
            return new Envelope(bounds);
        }

        public Path file() {
            return file;
        }

        public long count() {
            return count;
        }
    }

    /** План партиционирования: либо единый вход, либо набор тайлов. */
    public static final class PartitionPlan {
        private final Path singleInput;
        private final List<Partition> partitions;

        private PartitionPlan(Path singleInput, List<Partition> partitions) {
            this.singleInput = singleInput;
            this.partitions = partitions;
        }

        public static PartitionPlan single(Path input) {
            return new PartitionPlan(input, List.of());
        }

        public boolean isSingle() {
            return singleInput != null;
        }

        public Path singleInput() {
            return singleInput;
        }

        public List<Partition> partitions() {
            return partitions;
        }
    }

    public PartitionPlan partition(Path input, Path workDir, double tileM, double marginM,
                                   List<String> warnings) throws IOException {
        Envelope bounds = scanBounds(input);
        if (bounds.isNull() || tileM <= 0.0) {
            return PartitionPlan.single(input);
        }
        double centerLat = (bounds.getMinY() + bounds.getMaxY()) / 2.0;
        double metersPerLon = 111320.0 * Math.cos(Math.toRadians(centerLat));
        double tileLat = tileM / 110540.0;
        double tileLon = tileM / Math.max(1.0, metersPerLon);
        int cols = Math.max(1, (int) Math.ceil(bounds.getWidth() / tileLon));
        int rows = Math.max(1, (int) Math.ceil(bounds.getHeight() / tileLat));
        if (tileLon <= 0 || tileLat <= 0) {
            return PartitionPlan.single(input);
        }
        if ((long) cols * rows <= 1) {
            return PartitionPlan.single(input);
        }
        double marginLat = marginM / 110540.0;
        double marginLon = marginM / Math.max(1.0, metersPerLon);

        Files.createDirectories(workDir);
        List<Partition> partitions = new ArrayList<>(cols * rows);
        Map<Integer, Partition> byId = new LinkedHashMap<>();
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int id = row * cols + col;
                Envelope tile = new Envelope(
                        bounds.getMinX() + col * tileLon,
                        bounds.getMinX() + (col + 1) * tileLon,
                        bounds.getMinY() + row * tileLat,
                        bounds.getMinY() + (row + 1) * tileLat);
                Partition partition = new Partition(id, tile,
                        workDir.resolve("tile-" + id + ".jsonl"));
                partitions.add(partition);
                byId.put(id, partition);
            }
        }

        WriterPool pool = new WriterPool(256);
        try (InputStream stream = Files.newInputStream(input)) {
            reader.read(stream, feature -> {
                try {
                    assign(feature, bounds, byId, cols, rows, tileLon, tileLat,
                            marginLon, marginLat, pool, warnings);
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        } finally {
            pool.closeAll();
        }

        List<Partition> nonEmpty = new ArrayList<>();
        for (Partition partition : partitions) {
            if (partition.count > 0) {
                nonEmpty.add(partition);
            }
        }
        if (nonEmpty.size() <= 1) {
            return PartitionPlan.single(input);
        }
        writeManifest(workDir, bounds, cols, rows, tileLon, tileLat, partitions);
        return new PartitionPlan(null, nonEmpty);
    }

    private void assign(JsonNode feature, Envelope bounds, Map<Integer, Partition> byId,
                        int cols, int rows, double tileLon, double tileLat,
                        double marginLon, double marginLat, WriterPool pool,
                        List<String> warnings) throws IOException {
        JsonNode geometryNode = feature.get("geometry");
        Geometry geometry = null;
        try {
            geometry = GeoJsonGeometryParser.parse(geometryNode);
        } catch (RuntimeException invalid) {
            warnings.add("PARTITION_INVALID_GEOMETRY: объект пропущен при партиционировании");
            return;
        }
        if (geometry == null || geometry.isEmpty()) {
            return;
        }
        String type = feature.path("properties").path("object_type").asText();
        Envelope envelope = geometry.getEnvelopeInternal();
        if ("oks_connection_point".equals(type)) {
            int id = tileOf(envelope.getMinX(), envelope.getMinY(), bounds, cols, rows,
                    tileLon, tileLat);
            write(pool, byId.get(id), feature);
            return;
        }
        if ("source".equals(type)) {
            for (Partition partition : byId.values()) {
                write(pool, partition, feature);
            }
            return;
        }
        Envelope test = new Envelope(envelope);
        test.expandBy(marginLon, marginLat);
        int c0 = clamp((int) Math.floor((test.getMinX() - bounds.getMinX()) / tileLon), cols);
        int c1 = clamp((int) Math.floor((test.getMaxX() - bounds.getMinX()) / tileLon), cols);
        int r0 = clamp((int) Math.floor((test.getMinY() - bounds.getMinY()) / tileLat), rows);
        int r1 = clamp((int) Math.floor((test.getMaxY() - bounds.getMinY()) / tileLat), rows);
        for (int row = r0; row <= r1; row++) {
            for (int col = c0; col <= c1; col++) {
                write(pool, byId.get(row * cols + col), feature);
            }
        }
    }

    private int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private int tileOf(double x, double y, Envelope bounds, int cols, int rows,
                       double tileLon, double tileLat) {
        int col = (int) Math.floor((x - bounds.getMinX()) / tileLon);
        int row = (int) Math.floor((y - bounds.getMinY()) / tileLat);
        col = Math.max(0, Math.min(cols - 1, col));
        row = Math.max(0, Math.min(rows - 1, row));
        return row * cols + col;
    }

    private void write(WriterPool pool, Partition partition, JsonNode feature) throws IOException {
        if (partition == null) {
            return;
        }
        pool.write(partition, objectMapper.writeValueAsString(feature));
    }

    private Envelope scanBounds(Path input) throws IOException {
        Envelope bounds = new Envelope();
        try (InputStream stream = Files.newInputStream(input)) {
            reader.read(stream, feature -> {
                JsonNode geometryNode = feature.get("geometry");
                try {
                    Geometry geometry = GeoJsonGeometryParser.parse(geometryNode);
                    if (geometry != null && !geometry.isEmpty()) {
                        bounds.expandToInclude(geometry.getEnvelopeInternal());
                    }
                } catch (RuntimeException ignored) {
                    // невалидная геометрия — учтётся ingest-валидацией
                }
            });
        }
        return bounds;
    }

    private void writeManifest(Path workDir, Envelope bounds, int cols, int rows,
                               double tileLon, double tileLat, List<Partition> partitions)
            throws IOException {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Partition partition : partitions) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", partition.id);
            entry.put("file", partition.file.getFileName().toString());
            entry.put("minX", partition.bounds.getMinX());
            entry.put("minY", partition.bounds.getMinY());
            entry.put("maxX", partition.bounds.getMaxX());
            entry.put("maxY", partition.bounds.getMaxY());
            entry.put("count", partition.count);
            entries.add(entry);
        }
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("bounds", new double[]{bounds.getMinX(), bounds.getMinY(),
                bounds.getMaxX(), bounds.getMaxY()});
        manifest.put("cols", cols);
        manifest.put("rows", rows);
        manifest.put("tileLon", tileLon);
        manifest.put("tileLat", tileLat);
        manifest.put("partitions", entries);
        objectMapper.writerWithDefaultPrettyPrinter()
                .writeValue(workDir.resolve("partition.json").toFile(), manifest);
    }

    /** Пул открытых append-писателей по тайлам (LRU) — файловых дескрипторов. */
    private static final class WriterPool {
        private final int maxOpen;
        private final Map<Partition, BufferedWriter> open;

        private WriterPool(int maxOpen) {
            this.maxOpen = maxOpen;
            this.open = new LinkedHashMap<>(16, 0.75f, true);
        }

        private void write(Partition partition, String line) throws IOException {
            BufferedWriter writer = open.get(partition);
            if (writer == null) {
                if (open.size() >= maxOpen) {
                    evict();
                }
                writer = Files.newBufferedWriter(partition.file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                open.put(partition, writer);
            }
            writer.write(line);
            writer.newLine();
            partition.count++;
        }

        private void evict() throws IOException {
            Iterator<Map.Entry<Partition, BufferedWriter>> iterator = open.entrySet().iterator();
            if (iterator.hasNext()) {
                Map.Entry<Partition, BufferedWriter> eldest = iterator.next();
                eldest.getValue().flush();
                eldest.getValue().close();
                iterator.remove();
            }
        }

        private void closeAll() throws IOException {
            IOException failure = null;
            for (BufferedWriter writer : open.values()) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException exception) {
                    failure = exception;
                }
            }
            open.clear();
            if (failure != null) {
                throw failure;
            }
        }
    }
}
