package ru.lct.heating.trace;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPoint;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.heating.ingest.CrsTransformer;

/**
 * Запись промежуточных этапов алгоритма на диск (ADR-0036). Создаёт в каталоге
 * запуска файлы {@code *.geojson}, {@code grid.json} и {@code manifest.json};
 * геометрия преобразуется в WGS84.
 */
@Component
public class StageTraceWriter {

    private static final Logger log = LoggerFactory.getLogger(StageTraceWriter.class);

    private final ObjectMapper objectMapper;
    private final CrsTransformer crs;

    public StageTraceWriter(ObjectMapper objectMapper, CrsTransformer crs) {
        this.objectMapper = objectMapper;
        this.crs = crs;
    }

    public void write(StageTrace trace, String runId, String algorithm, Path stagesDir)
            throws IOException {
        if (trace == null || !trace.isEnabled()) {
            return;
        }
        Files.createDirectories(stagesDir);
        for (Map.Entry<String, List<StageFeature>> entry : trace.stages().entrySet()) {
            if (trace.treePasses().contains(passOf(entry.getKey()))) {
                continue;
            }
            writeGeoJson(stagesDir.resolve(entry.getKey() + ".geojson"), entry.getValue());
        }
        for (int pass : trace.treePasses()) {
            writeGeoJson(stagesDir.resolve("trees-" + pass + ".geojson"), trace.features("trees-" + pass));
        }
        if (trace.grid() != null) {
            writeGrid(trace.grid(), stagesDir.resolve("grid.json"));
        }
        writeManifest(trace, runId, algorithm, stagesDir.resolve("manifest.json"));
        log.info("Stage trace written: dir={} files={}", stagesDir, trace.stages().size());
    }

    private int passOf(String id) {
        if (id.startsWith("trees-")) {
            try {
                return Integer.parseInt(id.substring("trees-".length()));
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        return -1;
    }

    private void writeGeoJson(Path file, List<StageFeature> features) throws IOException {
        try (OutputStream out = Files.newOutputStream(file)) {
            try (JsonGenerator generator = objectMapper.getFactory().createGenerator(out)) {
                generator.writeStartObject();
                generator.writeStringField("type", "FeatureCollection");
                generator.writeArrayFieldStart("features");
                for (StageFeature feature : features) {
                    writeFeature(generator, feature);
                }
                generator.writeEndArray();
                generator.writeEndObject();
                generator.flush();
            }
        }
    }

    private void writeFeature(JsonGenerator generator, StageFeature feature) throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", "Feature");
        generator.writeFieldName("geometry");
        writeGeometry(generator, feature.getGeometry());
        generator.writeObjectFieldStart("properties");
        if (feature.getObjectType() != null) {
            generator.writeStringField("object_type", feature.getObjectType());
        }
        if (feature.getProperties() != null) {
            for (Map.Entry<String, Object> entry : feature.getProperties().entrySet()) {
                writeProperty(generator, entry.getKey(), entry.getValue());
            }
        }
        generator.writeEndObject();
        generator.writeEndObject();
    }

    private void writeProperty(JsonGenerator generator, String name, Object value) throws IOException {
        if (value == null) {
            generator.writeNullField(name);
        } else if (value instanceof Number) {
            generator.writeNumberField(name, ((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            generator.writeBooleanField(name, (Boolean) value);
        } else {
            generator.writeStringField(name, value.toString());
        }
    }

    private void writeGeometry(JsonGenerator generator, Geometry geometry) throws IOException {
        if (geometry == null) {
            generator.writeNull();
            return;
        }
        writeGeometryNode(generator, crs.toWgs84(geometry));
    }

    private void writeGeometryNode(JsonGenerator generator, Geometry geometry) throws IOException {
        if (geometry instanceof Point) {
            generator.writeStartObject();
            generator.writeStringField("type", "Point");
            generator.writeFieldName("coordinates");
            writeCoordinate(generator, geometry.getCoordinate());
            generator.writeEndObject();
        } else if (geometry instanceof MultiPoint) {
            writeMulti(generator, "MultiPoint", geometry);
        } else if (geometry instanceof LineString) {
            generator.writeStartObject();
            generator.writeStringField("type", "LineString");
            generator.writeFieldName("coordinates");
            writeCoordinateSequence(generator, (LineString) geometry);
            generator.writeEndObject();
        } else if (geometry instanceof MultiLineString) {
            writeMulti(generator, "MultiLineString", geometry);
        } else if (geometry instanceof Polygon) {
            generator.writeStartObject();
            generator.writeStringField("type", "Polygon");
            generator.writeFieldName("coordinates");
            writePolygonRings(generator, (Polygon) geometry);
            generator.writeEndObject();
        } else if (geometry instanceof MultiPolygon) {
            writeMulti(generator, "MultiPolygon", geometry);
        } else if (geometry instanceof GeometryCollection) {
            generator.writeStartObject();
            generator.writeStringField("type", "GeometryCollection");
            generator.writeArrayFieldStart("geometries");
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                writeGeometryNode(generator, geometry.getGeometryN(i));
            }
            generator.writeEndArray();
            generator.writeEndObject();
        } else {
            generator.writeNull();
        }
    }

    private void writeMulti(JsonGenerator generator, String type, Geometry geometry)
            throws IOException {
        generator.writeStartObject();
        generator.writeStringField("type", type);
        generator.writeArrayFieldStart("coordinates");
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry part = geometry.getGeometryN(i);
            if (part instanceof Point) {
                writeCoordinate(generator, part.getCoordinate());
            } else if (part instanceof LineString) {
                writeCoordinateSequence(generator, (LineString) part);
            } else if (part instanceof Polygon) {
                writePolygonRings(generator, (Polygon) part);
            }
        }
        generator.writeEndArray();
        generator.writeEndObject();
    }

    private void writePolygonRings(JsonGenerator generator, Polygon polygon) throws IOException {
        generator.writeStartArray();
        writeCoordinateSequence(generator, polygon.getExteriorRing());
        for (int i = 0; i < polygon.getNumInteriorRing(); i++) {
            writeCoordinateSequence(generator, polygon.getInteriorRingN(i));
        }
        generator.writeEndArray();
    }

    private void writeCoordinateSequence(JsonGenerator generator, LineString ring)
            throws IOException {
        generator.writeStartArray();
        for (Coordinate coordinate : ring.getCoordinates()) {
            writeCoordinate(generator, coordinate);
        }
        generator.writeEndArray();
    }

    private void writeCoordinate(JsonGenerator generator, Coordinate coordinate) throws IOException {
        if (coordinate == null) {
            generator.writeStartArray();
            generator.writeEndArray();
            return;
        }
        generator.writeStartArray();
        generator.writeNumber(coordinate.x);
        generator.writeNumber(coordinate.y);
        generator.writeEndArray();
    }

    private void writeGrid(GridMaskPayload grid, Path file) throws IOException {
        GridMaskPayload enriched = grid.toBuilder()
                .boundsWgs84(List.of(
                        toWgs84(grid.getOriginX(), grid.getOriginY() + (double) grid.getHeight() * grid.getCellM()),
                        toWgs84(grid.getOriginX() + (double) grid.getWidth() * grid.getCellM(),
                                grid.getOriginY() + (double) grid.getHeight() * grid.getCellM()),
                        toWgs84(grid.getOriginX() + (double) grid.getWidth() * grid.getCellM(),
                                grid.getOriginY()),
                        toWgs84(grid.getOriginX(), grid.getOriginY())))
                .sources(toWgs84(grid.getSources()))
                .terminalCells(toWgs84(grid.getTerminalCells()))
                .build();
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), enriched);
    }

    private List<double[]> toWgs84(List<double[]> coordinates) {
        List<double[]> result = new ArrayList<>();
        if (coordinates == null) {
            return result;
        }
        for (double[] coordinate : coordinates) {
            result.add(toWgs84(coordinate[0], coordinate[1]));
        }
        return result;
    }

    private double[] toWgs84(double x, double y) {
        Coordinate transformed = crs.toWgs84(new Coordinate(x, y));
        return new double[]{transformed.x, transformed.y};
    }

    private void writeManifest(StageTrace trace, String runId, String algorithm, Path file)
            throws IOException {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("runId", runId);
        manifest.put("algorithm", algorithm);
        manifest.put("passes", trace.passes());
        manifest.put("bestPass", trace.bestPass());
        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(descriptor("input", "Вход", "input", "input", true));
        stages.add(descriptor(StageTrace.NETWORK, "Сеть", "network", "geojson",
                trace.hasStage(StageTrace.NETWORK)));
        stages.add(descriptor(StageTrace.OBSTACLES, "Ограничения", "obstacles", "geojson",
                trace.hasStage(StageTrace.OBSTACLES)));
        stages.add(descriptor(StageTrace.SPECIAL, "Спецпроходы", "special", "geojson",
                trace.hasStage(StageTrace.SPECIAL)));
        stages.add(descriptor(StageTrace.EXITS, "Выходы", "exits", "geojson",
                trace.hasStage(StageTrace.EXITS)));
        stages.add(descriptor(StageTrace.GRID, "Сетка", "grid", "mask", trace.grid() != null));
        Map<String, Object> trees = descriptor("trees", "Деревья", "trees", "geojson",
                !trace.treePasses().isEmpty());
        trees.put("passes", trace.treePasses());
        trees.put("bestPass", trace.bestPass());
        stages.add(trees);
        stages.add(descriptor(StageTrace.REFINE, "Refine", "refine", "geojson",
                trace.hasStage(StageTrace.REFINE)));
        stages.add(descriptor(StageTrace.RELINK, "Переподключение", "relink", "geojson",
                trace.hasStage(StageTrace.RELINK)));
        manifest.put("stages", stages);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
    }

    private Map<String, Object> descriptor(String id, String title, String kind, String format,
                                           boolean available) {
        Map<String, Object> descriptor = new LinkedHashMap<>();
        descriptor.put("id", id);
        descriptor.put("title", title);
        descriptor.put("kind", kind);
        descriptor.put("format", format);
        descriptor.put("available", available);
        return descriptor;
    }
}
