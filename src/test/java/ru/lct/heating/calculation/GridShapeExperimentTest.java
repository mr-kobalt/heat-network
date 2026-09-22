package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.ingest.CrsTransformer;

/**
 * Эксперимент A/B: форма сетки поиска пути — квадрат против гекса (ADR-0041).
 * Считает `S`, длину, стоимость, время, число камер и клеток, внутренние углы
 * >90°, пересечения и консолидацию точек 3/6/8. Отчёт —
 * {@code target/grid-shape/report.md}.
 *
 * <p>Запуск: {@code mvn test -Dtest=GridShapeExperimentTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@Tag("slow")
class GridShapeExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OUTPUT = Path.of("target", "grid-shape");

    @Test
    void comparesSquareAndHex() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор " + SAMPLE + " недоступен");
        Files.createDirectories(OUTPUT);
        ObjectMapper mapper = new ObjectMapper();

        Map<String, Object> square = run(mapper, "square");
        Map<String, Object> hex = run(mapper, "hex");

        System.out.println("SQUARE: " + square);
        System.out.println("HEX:    " + hex);

        writeReport(List.of(square, hex), mapper);
        assertThat(square.get("error")).isNull();
        assertThat(hex.get("error")).isNull();
    }

    private Map<String, Object> run(ObjectMapper mapper, String shape) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestGridShape(shape);
        Path dir = OUTPUT.resolve(shape);
        Files.createDirectories(dir);
        Path resultFile = dir.resolve("result.geojson");
        Path summaryFile = dir.resolve("summary.json");

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("shape", shape);
        long start = System.nanoTime();
        try {
            CalculationOutcome outcome = service(properties).calculate(SAMPLE, resultFile, summaryFile);
            row.put("timeMs", (System.nanoTime() - start) / 1_000_000L);
            row.put("score", outcome.getSummary().getScore());
            row.put("lengthM", outcome.getSummary().getNewNetworkLengthM());
            row.put("calculatedCost", outcome.getSummary().getCalculatedCost());
            row.put("chamberCost", outcome.getSummary().getChamberConstructionCost());
            row.put("unconnected", outcome.getSummary().getUnconnectedOksIds().size());
            row.put("turnWarnings", countPrefix(outcome.getWarnings(), "TURN_ANGLE_EXCEEDS_90"));
            Metrics metrics = metrics(mapper, resultFile);
            row.put("chambers", metrics.chambers);
            row.put("crossings", metrics.crossings);
            row.put("interiorTurns", metrics.interiorTurns);
            row.put("maxVerts", metrics.maxVerts);
            row.put("shareChamber368", metrics.shareChamber);
            row.put("error", null);
        } catch (Exception exception) {
            row.put("timeMs", (System.nanoTime() - start) / 1_000_000L);
            row.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return row;
    }

    private int countPrefix(List<String> warnings, String prefix) {
        int count = 0;
        for (String warning : warnings) {
            if (warning.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private Metrics metrics(ObjectMapper mapper, Path resultFile) throws Exception {
        CrsTransformer crs = new CrsTransformer();
        JsonNode features = mapper.readTree(resultFile.toFile()).path("features");
        String best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (JsonNode feature : features) {
            JsonNode p = feature.path("properties");
            if ("variant_summary".equals(p.path("object_type").asText())
                    && p.path("score").asDouble() < bestScore) {
                bestScore = p.path("score").asDouble();
                best = p.path("variant_id").asText();
            }
        }
        int chambers = 0;
        List<LineString> lines = new ArrayList<>();
        List<String> starts = new ArrayList<>();
        List<String> ends = new ArrayList<>();
        Map<String, Integer> degree = new java.util.HashMap<>();
        Map<String, List<String>> adjacency = new java.util.HashMap<>();
        for (JsonNode feature : features) {
            JsonNode p = feature.path("properties");
            String variant = p.path("variant_id").asText();
            if (!best.equals(variant)) {
                continue;
            }
            if ("heat_chamber".equals(p.path("object_type").asText())) {
                chambers++;
            }
            if (!"heat_network".equals(p.path("object_type").asText())) {
                continue;
            }
            String a = p.path("start_node_id").asText();
            String b = p.path("end_node_id").asText();
            adjacency.computeIfAbsent(a, key -> new ArrayList<>()).add(b);
            adjacency.computeIfAbsent(b, key -> new ArrayList<>()).add(a);
            degree.merge(a, 1, Integer::sum);
            degree.merge(b, 1, Integer::sum);
            starts.add(a);
            ends.add(b);
            JsonNode coords = feature.path("geometry").path("coordinates");
            Coordinate[] points = new Coordinate[coords.size()];
            for (int i = 0; i < coords.size(); i++) {
                points[i] = crs.toUtm(new Coordinate(coords.get(i).get(0).asDouble(),
                        coords.get(i).get(1).asDouble()));
            }
            lines.add(GeometrySupport.GEOMETRY_FACTORY.createLineString(points));
        }
        int crossings = 0;
        int interiorTurns = 0;
        int maxVerts = 0;
        for (int i = 0; i < lines.size(); i++) {
            maxVerts = Math.max(maxVerts, lines.get(i).getNumPoints());
            for (int j = i + 1; j < lines.size(); j++) {
                boolean shared = sharesNode(starts.get(i), ends.get(i), starts.get(j), ends.get(j));
                if (!shared && lines.get(i).crosses(lines.get(j))) {
                    crossings++;
                }
            }
        }
        for (LineString line : lines) {
            Coordinate[] c = line.getCoordinates();
            for (int i = 1; i < c.length - 1; i++) {
                if (angle(c[i - 1], c[i], c[i + 1]) > 90.0 + 1e-6) {
                    interiorTurns++;
                }
            }
        }
        boolean shareChamber = chamber(adjacency, degree, "3").equals(chamber(adjacency, degree, "6"))
                && chamber(adjacency, degree, "6").equals(chamber(adjacency, degree, "8"));
        return new Metrics(chambers, crossings, interiorTurns, maxVerts, shareChamber);
    }

    private String chamber(Map<String, List<String>> adjacency, Map<String, Integer> degree,
                           String point) {
        if (!adjacency.containsKey(point)) {
            return "missing";
        }
        String previous = point;
        String current = adjacency.get(point).get(0);
        Set<String> visited = new HashSet<>();
        while (degree.getOrDefault(current, 0) == 2 && visited.add(current)) {
            List<String> next = adjacency.get(current);
            String step = next.get(0).equals(previous) ? next.get(1) : next.get(0);
            previous = current;
            current = step;
        }
        return current;
    }

    private boolean sharesNode(String aStart, String aEnd, String bStart, String bEnd) {
        return aStart.equals(bStart) || aStart.equals(bEnd)
                || aEnd.equals(bStart) || aEnd.equals(bEnd);
    }

    private double angle(Coordinate a, Coordinate b, Coordinate c) {
        double inX = b.x - a.x;
        double inY = b.y - a.y;
        double outX = c.x - b.x;
        double outY = c.y - b.y;
        return Math.toDegrees(Math.atan2(Math.abs(inX * outY - inY * outX), inX * outX + inY * outY));
    }

    private void writeReport(List<Map<String, Object>> rows, ObjectMapper mapper) throws Exception {
        StringBuilder report = new StringBuilder();
        report.append("# Эксперимент формы сетки: square vs hex (ADR-0041)\n\n");
        report.append("| Метрика | square | hex |\n");
        report.append("|---|---|---|\n");
        for (String key : List.of("score", "lengthM", "calculatedCost", "chamberCost", "unconnected",
                "chambers", "crossings", "interiorTurns", "maxVerts", "shareChamber368",
                "turnWarnings", "timeMs")) {
            report.append("| ").append(key).append(" |");
            for (Map<String, Object> row : rows) {
                report.append(' ').append(row.getOrDefault(key, "-")).append(" |");
            }
            report.append('\n');
        }
        Files.writeString(OUTPUT.resolve("report.md"), report.toString());
        mapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.resolve("report.json").toFile(), rows);
    }

    private static final class Metrics {
        private final int chambers;
        private final int crossings;
        private final int interiorTurns;
        private final int maxVerts;
        private final boolean shareChamber;

        private Metrics(int chambers, int crossings, int interiorTurns, int maxVerts,
                        boolean shareChamber) {
            this.chambers = chambers;
            this.crossings = crossings;
            this.interiorTurns = interiorTurns;
            this.maxVerts = maxVerts;
            this.shareChamber = shareChamber;
        }
    }
}
