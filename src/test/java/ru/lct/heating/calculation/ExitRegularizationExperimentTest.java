package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.config.AppProperties;

/**
 * ADR-0066/0067, E50-08: A/B регуляризации выхода терминала
 * ({@code forest-exit-regularization}). Проверяет на реальных наборах, что
 * включение не ухудшает {@code S}, устраняет дефекты и что <b>все</b> выпущенные
 * варианты геометрически валидны (углы ≤90°, без самопересечений, включая
 * внутриреберные); невалидные варианты отбрасываются. Тег slow.
 */
@Tag("slow")
class ExitRegularizationExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path BASE = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OSM = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void baseDatasetAllVariantsValidAndPoint9Canonical() throws Exception {
        assumeTrue(Files.exists(BASE), "нет набора");
        Outcome off = run(BASE, "base-off", false);
        Outcome on = run(BASE, "base-on", true);

        System.out.println("EXIT-REG base off=" + off + " on=" + on);
        assertThat(on.unconnected).isZero();
        assertThat(on.allTurnViolations).as("углы во всех вариантах").isZero();
        assertThat(on.allSelfIntersections).as("самопересечения во всех вариантах").isZero();
        assertThat(on.exitMismatches).isZero();
        assertThat(on.degreeViolations).isZero();
        assertThat(on.score).as("S не ухудшается").isLessThanOrEqualTo(off.score + 1e-6);
        assertThat(on.terminalEdgeVertices("9", "v1")).as("точка 9 — канонический вывод")
                .isEqualTo(2);
    }

    @Test
    void osmPoint4FixedAndPoint17MinimalRoute() throws Exception {
        assumeTrue(Files.exists(OSM), "нет набора");
        Outcome off = run(OSM, "osm-off", false);
        Outcome on = run(OSM, "osm-on", true);

        System.out.println("EXIT-REG osm off=" + off + " on=" + on);
        assertThat(off.allTurnViolations + off.allSelfIntersections)
                .as("до правки дефекты есть хоть в одном варианте").isPositive();
        assertThat(on.unconnected).isZero();
        assertThat(on.allTurnViolations).as("углы во всех вариантах").isZero();
        assertThat(on.allSelfIntersections).as("самопересечения во всех вариантах").isZero();
        assertThat(on.exitMismatches).isZero();
        assertThat(on.degreeViolations).isZero();
        assertThat(on.score).as("S не ухудшается").isLessThanOrEqualTo(off.score + 1e-6);
        assertThat(on.terminalEdgeVertices("4", "v1")).as("точка 4 — канонический вывод")
                .isEqualTo(2);
        // Точка 17: ствол до ближайшей проекции сети (~96 м) + хвост ≈ 106.7 м.
        assertThat(on.maxTerminalEdgeLength("17")).as("точка 17 — минимальный маршрут")
                .isLessThanOrEqualTo(110.0);
    }

    private Outcome run(Path dataset, String name, boolean regularization) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestExitRegularization(regularization);
        Path result = tempDir.resolve(name + ".geojson");
        CalculationOutcome outcome = service(properties).calculate(dataset, result,
                tempDir.resolve(name + ".json"));
        ObjectMapper mapper = new ObjectMapper();
        Outcome value = new Outcome();
        value.score = outcome.getSummary().getScore();
        value.unconnected = outcome.getSummary().getUnconnectedOksIds().size();
        value.degreeViolations = countChamberDegreeViolations(result, mapper);
        value.exitMismatches = countMissingCanonicalExits(dataset, result, mapper);
        value.result = result;
        value.mapper = mapper;
        Set<String> variants = variantIds(result, mapper);
        for (String variant : variants) {
            for (OutputEdgeRef edge : outputEdges(result, mapper, variant)) {
                value.allTurnViolations += countTurns(edge.line());
            }
            value.allSelfIntersections += countSelfIntersections(result, mapper, variant);
        }
        return value;
    }

    private Set<String> variantIds(Path result, ObjectMapper mapper) throws Exception {
        Set<String> variants = new LinkedHashSet<>();
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            if ("variant_summary".equals(
                    feature.path("properties").path("object_type").asText())) {
                variants.add(feature.path("properties").path("variant_id").asText());
            }
        }
        return variants;
    }

    private int countTurns(LineString line) {
        LineString projected = (LineString) crsTransformer.toUtm(line);
        Coordinate[] coords = projected.getCoordinates();
        int violations = 0;
        for (int i = 1; i + 1 < coords.length; i++) {
            double inX = coords[i].x - coords[i - 1].x;
            double inY = coords[i].y - coords[i - 1].y;
            double outX = coords[i + 1].x - coords[i].x;
            double outY = coords[i + 1].y - coords[i].y;
            double angle = Math.toDegrees(Math.atan2(Math.abs(inX * outY - inY * outX),
                    inX * outX + inY * outY));
            if (angle > 90.05) {
                violations++;
            }
        }
        return violations;
    }

    private int countSelfIntersections(Path result, ObjectMapper mapper, String variant)
            throws Exception {
        List<OutputEdgeRef> edges = new ArrayList<>(outputEdges(result, mapper, variant));
        int violations = 0;
        for (int i = 0; i < edges.size(); i++) {
            OutputEdgeRef a = edges.get(i);
            Coordinate[] coords = a.line().getCoordinates();
            for (int x = 0; x + 1 < coords.length; x++) {
                LineString sa = a.line().getFactory().createLineString(
                        new Coordinate[]{coords[x], coords[x + 1]});
                for (int y = x + 2; y + 1 < coords.length; y++) {
                    if (!sa.intersection(a.line().getFactory().createLineString(
                            new Coordinate[]{coords[y], coords[y + 1]})).isEmpty()) {
                        violations++;
                    }
                }
            }
            for (int j = i + 1; j < edges.size(); j++) {
                OutputEdgeRef b = edges.get(j);
                if (a.startNode().equals(b.startNode()) || a.startNode().equals(b.endNode())
                        || a.endNode().equals(b.startNode()) || a.endNode().equals(b.endNode())) {
                    continue;
                }
                Geometry intersection = a.line().intersection(b.line());
                if (!intersection.isEmpty()) {
                    violations++;
                }
            }
        }
        return violations;
    }

    private final class Outcome {
        private double score;
        private int unconnected;
        private int allSelfIntersections;
        private int allTurnViolations;
        private int degreeViolations;
        private int exitMismatches;
        private Path result;
        private ObjectMapper mapper;

        private int terminalEdgeVertices(String pointId, String variant) throws Exception {
            for (OutputEdgeRef edge : outputEdges(result, mapper, variant)) {
                if (edge.endNode().equals(pointId) || edge.startNode().equals(pointId)) {
                    return edge.line().getNumPoints();
                }
            }
            return -1;
        }

        private double maxTerminalEdgeLength(String pointId) throws Exception {
            double max = 0.0;
            for (String variant : variantIds(result, mapper)) {
                for (OutputEdgeRef edge : outputEdges(result, mapper, variant)) {
                    if (!edge.endNode().equals(pointId) && !edge.startNode().equals(pointId)) {
                        continue;
                    }
                    LineString projected = (LineString) crsTransformer.toUtm(edge.line());
                    Coordinate[] coords = projected.getCoordinates();
                    double length = 0.0;
                    for (int i = 0; i + 1 < coords.length; i++) {
                        length += coords[i].distance(coords[i + 1]);
                    }
                    max = Math.max(max, length);
                }
            }
            return max;
        }

        @Override
        public String toString() {
            return "score=" + score + " unconnected=" + unconnected
                    + " selfX=" + allSelfIntersections + " turns=" + allTurnViolations
                    + " exit=" + exitMismatches + " degree=" + degreeViolations;
        }
    }
}
