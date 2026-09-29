package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

/**
 * Харнесс верификации на произвольном наборе (готовность к проверочному набору
 * организатора, NFR-09/NFR-10). Прогоняет полный конвейер и все доступные
 * инварианты обязательной части ТП v2, печатает отчёт.
 *
 * <p>Запуск:
 * {@code mvn -B test -Dtest=VerificationHarnessTest -Dverify.dataset=<path> \
 *   -Dsurefire.excludedGroups= -Dgroups=slow}
 * Дополнительно: {@code -Dverify.strict=false|true}, {@code -Dverify.diameterAware=true}.</p>
 */
@Tag("slow")
class VerificationHarnessTest extends AbstractCalculationPipelineTest {

    @Test
    void verifyDataset() throws Exception {
        String datasetPath = System.getProperty("verify.dataset", "");
        assumeTrue(!datasetPath.isBlank(), "verify.dataset не задан");
        Path dataset = Path.of(datasetPath);
        assumeTrue(Files.exists(dataset), "Набор не найден: " + datasetPath);

        AppProperties properties = new AppProperties();
        if (System.getProperty("verify.strict") != null) {
            properties.setForestSpecialStrict(
                    Boolean.parseBoolean(System.getProperty("verify.strict")));
        }
        if (System.getProperty("verify.diameterAware") != null) {
            properties.setForestDiameterAwareBuffers(
                    Boolean.parseBoolean(System.getProperty("verify.diameterAware")));
        }
        if (System.getProperty("verify.oksExitExtraBufferM") != null) {
            properties.setOksExitExtraBufferM(
                    Double.parseDouble(System.getProperty("verify.oksExitExtraBufferM")));
        }
        if (System.getProperty("verify.rootOptimization") != null) {
            properties.setForestRootOptimization(
                    Boolean.parseBoolean(System.getProperty("verify.rootOptimization")));
        }
        if (System.getProperty("verify.exitVisibilityFallback") != null) {
            properties.setForestExitVisibilityFallback(
                    Boolean.parseBoolean(System.getProperty("verify.exitVisibilityFallback")));
        }
        if (System.getProperty("verify.exitRegularization") != null) {
            properties.setForestExitRegularization(
                    Boolean.parseBoolean(System.getProperty("verify.exitRegularization")));
        }
        if (System.getProperty("verify.clusterRadiusM") != null) {
            properties.setForestClusterRadiusM(
                    Double.parseDouble(System.getProperty("verify.clusterRadiusM")));
        }
        if (System.getProperty("verify.clusterMarginM") != null) {
            properties.setForestClusterMarginM(
                    Double.parseDouble(System.getProperty("verify.clusterMarginM")));
        }
        if (System.getProperty("verify.decomposition") != null) {
            properties.setForestDecomposition(
                    Boolean.parseBoolean(System.getProperty("verify.decomposition")));
        }

        ObjectMapper mapper = new ObjectMapper();
        Path result = tempDir.resolve("verify-result.geojson");
        Path summary = tempDir.resolve("verify-summary.json");
        CalculationOutcome outcome = service(properties).calculate(dataset, result, summary);
        assertThat(outcome.getSummary()).isNotNull();
        String dump = System.getProperty("verify.out", "");
        if (!dump.isBlank()) {
            Files.copy(result, Path.of(dump), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        int selfIntersections = countSelfIntersections(result, mapper);
        int turnViolations = countTurnViolations(result, mapper);
        int degreeViolations = countChamberDegreeViolations(result, mapper);
        int specialBends = countSpecialBends(result, mapper);
        int exitMismatches = countMissingCanonicalExits(dataset, result, mapper);
        int dangling = countDanglingNodeReferences(dataset, result, mapper);
        System.out.println("VERIFY dataset=" + datasetPath
                + " algorithm=grid-forest"
                + " score=" + outcome.getSummary().getScore()
                + " length=" + outcome.getSummary().getNewNetworkLengthM()
                + " cost=" + outcome.getSummary().getCalculatedCost()
                + " unconnected=" + outcome.getSummary().getUnconnectedOksIds().size()
                + " selfIntersections=" + selfIntersections
                + " turnViolations=" + turnViolations
                + " specialBends=" + specialBends
                + " exitMismatches=" + exitMismatches
                + " danglingRefs=" + dangling
                + " chamberDegreeViolations=" + degreeViolations
                + " warnings=" + outcome.getWarnings().size());

        if (turnViolations > 0) {
            printTurnViolations(result, mapper);
        }
        if (selfIntersections > 0) {
            printCrossings(result, mapper);
        }
        if (exitMismatches > 0) {
            printExitDiagnostics(dataset, result, mapper);
        }
        // Обязательные инварианты ТП v2.
        assertNoOksCrossingBeyondApproach(dataset, result, mapper);
        assertNodeReferencesMatchGeometry(dataset, result, mapper);
        assertConnectionPointsAreLeaves(dataset, result, mapper);
        assertBranchOnlyInChambers(result, mapper);
        assertThat(selfIntersections).isZero();
        assertThat(turnViolations).isZero();
        assertThat(specialBends).isZero();
        assertThat(exitMismatches).isZero();
        assertThat(dangling).isZero();
        assertThat(degreeViolations).isZero();
        assertThat(outcome.getSummary().getUnconnectedOksIds())
                .as("неподключённые точки (ТП §2.5)")
                .isEmpty();
    }

    private void printExitDiagnostics(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        var canonical = canonicalExits(input);
        var points = new java.util.HashMap<String, double[]>();
        for (var feature : mapper.readTree(input.toFile()).path("features")) {
            if ("oks_connection_point".equals(
                    feature.path("properties").path("object_type").asText())) {
                var c = feature.path("geometry").path("coordinates");
                points.put(feature.path("properties").path("id").asText(),
                        new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            }
        }
        Map<String, Boolean> foundByVariantKey = new java.util.HashMap<>();
        for (var feature : mapper.readTree(result.toFile()).path("features")) {
            var properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            var coords = feature.path("geometry").path("coordinates");
            if (coords.size() < 2) {
                continue;
            }
            for (int endIndex : new int[]{0, coords.size() - 1}) {
                double[] end = {coords.get(endIndex).get(0).asDouble(),
                        coords.get(endIndex).get(1).asDouble()};
                for (var point : points.entrySet()) {
                    if (Math.hypot(point.getValue()[0] - end[0], point.getValue()[1] - end[1])
                            > 1e-9) {
                        continue;
                    }
                    double[] target = canonical.get(point.getKey());
                    if (target == null) {
                        continue;
                    }
                    boolean found = false;
                    for (var coordinate : coords) {
                        if (Math.hypot(coordinate.get(0).asDouble() - target[0],
                                coordinate.get(1).asDouble() - target[1]) < 1e-7) {
                            found = true;
                        }
                    }
                    String key = properties.path("variant_id").asText() + "|" + point.getKey();
                    if (!found) {
                        System.out.println("EXIT MISS var=" + properties.path("variant_id").asText()
                                + " point=" + point.getKey() + " edge="
                                + properties.path("id").asText() + " canonical=("
                                + target[0] + "," + target[1] + ") coords=" + coords);
                    }
                    foundByVariantKey.merge(key, found, (a, b) -> a || b);
                }
            }
        }
    }

    private void printCrossings(Path result, ObjectMapper mapper) throws Exception {
        java.util.List<OutputEdgeRef> edges = outputEdges(result, mapper, bestVariantId(result, mapper));
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                OutputEdgeRef a = edges.get(i);
                OutputEdgeRef b = edges.get(j);
                if (a.startNode().equals(b.startNode()) || a.startNode().equals(b.endNode())
                        || a.endNode().equals(b.startNode()) || a.endNode().equals(b.endNode())) {
                    continue;
                }
                org.locationtech.jts.geom.Geometry inter = a.line().intersection(b.line());
                if (!inter.isEmpty()) {
                    org.locationtech.jts.geom.Coordinate x = inter.getCoordinate();
                    System.out.println("VERIFY CROSS " + a.id() + "[" + a.startNode() + "->"
                            + a.endNode() + "] x " + b.id() + "[" + b.startNode() + "->"
                            + b.endNode() + "] type=" + inter.getGeometryType() + " at " + x
                            + " | a0=" + a.line().getCoordinateN(0)
                            + " aN=" + a.line().getCoordinateN(a.line().getNumPoints() - 1)
                            + " | b0=" + b.line().getCoordinateN(0)
                            + " bN=" + b.line().getCoordinateN(b.line().getNumPoints() - 1));
                }
            }
        }
    }

    private void printTurnViolations(Path result, ObjectMapper mapper) throws Exception {
        for (OutputEdgeRef edge : outputEdges(result, mapper, bestVariantId(result, mapper))) {
            org.locationtech.jts.geom.LineString projected = (org.locationtech.jts.geom.LineString)
                    crsTransformer.toUtm(edge.line());
            org.locationtech.jts.geom.Coordinate[] cs = projected.getCoordinates();
            for (int i = 1; i < cs.length - 1; i++) {
                double inX = cs[i].x - cs[i - 1].x;
                double inY = cs[i].y - cs[i - 1].y;
                double outX = cs[i + 1].x - cs[i].x;
                double outY = cs[i + 1].y - cs[i].y;
                double angle = Math.toDegrees(Math.atan2(Math.abs(inX * outY - inY * outX),
                        inX * outX + inY * outY));
                if (angle > 90.0 + 1e-6) {
                    System.out.println("VERIFY TURN " + edge.id() + " [" + edge.startNode() + "->"
                            + edge.endNode() + "] vertex " + i + " angle=" + Math.round(angle)
                            + " n=" + cs.length + " prev=" + cs[i - 1] + " v=" + cs[i]
                            + " next=" + cs[i + 1]);
                }
            }
        }
    }
}
