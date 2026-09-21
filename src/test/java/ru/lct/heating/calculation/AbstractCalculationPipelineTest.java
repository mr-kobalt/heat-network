package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.cost.CostProperties;
import ru.lct.heating.geometry.DistanceBand;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndexBuilder;
import ru.lct.heating.geometry.RestrictionAxisBuilder;
import ru.lct.heating.geometry.RestrictionMode;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.geometry.RestrictionRulesProperties;
import ru.lct.heating.geometry.SpecialSpanSplitter;
import ru.lct.heating.geometry.SpecialZoneIndexBuilder;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;
import ru.lct.heating.ingest.CrsTransformer;
import ru.lct.heating.ingest.FeatureParser;
import ru.lct.heating.ingest.GeoJsonGeometryParser;
import ru.lct.heating.ingest.GeoJsonStreamReader;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.ForestResultBuilder;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.routing.TieInCandidateProvider;
import ru.lct.heating.routing.algorithm.GridForestTracingAlgorithm;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;
import ru.lct.heating.routing.GridForestPlanner;
import ru.lct.heating.variants.VariantGenerator;

/**
 * Общая обвязка сквозного теста конвейера (без БД и Spring). Быстрый тест
 * использует маленький фикстур, полный — реальный набор (тег slow).
 */
abstract class AbstractCalculationPipelineTest {

    @TempDir
    protected Path tempDir;

    protected CalculationService service() {
        return service(new AppProperties());
    }

    protected CalculationService service(AppProperties appProperties) {
        ObjectMapper objectMapper = new ObjectMapper();
        CrsTransformer crs = new CrsTransformer();
        IngestService ingest = new IngestService(new GeoJsonStreamReader(objectMapper),
                new FeatureParser(crs));
        HeatingTablesProperties tables = diameters();
        DiameterCatalog catalog = new DiameterCatalog(tables);
        EnvelopeCatalog envelopes = new EnvelopeCatalog(tables);
        CostModel costModel = new CostModel(catalog, new CostProperties());
        RestrictionRuleResolver resolver = new RestrictionRuleResolver(rules());
        appProperties.setTurnPenaltyM(30.0);
        LineStringSimplifier simplifier = new LineStringSimplifier();
        ru.lct.heating.routing.OksApproachResolver approachResolver =
                new ru.lct.heating.routing.OksApproachResolver(resolver, envelopes, catalog,
                        appProperties);
        GridForestPlanner forestPlanner = new GridForestPlanner(
                new TieInCandidateProvider(), catalog, costModel, new MaxLengthEnforcer(catalog),
                simplifier, new ru.lct.heating.geometry.ObstacleMaskBuilder(),
                new ru.lct.heating.routing.CellStoreFactory(appProperties, null), appProperties);
        TracingAlgorithmRegistry registry = new TracingAlgorithmRegistry(
                List.of(new GridForestTracingAlgorithm(forestPlanner)), appProperties);
        VariantGenerator variantGenerator = new VariantGenerator(
                new ForestResultBuilder(crs, costModel, new SpecialSpanSplitter(), appProperties));

        return new CalculationService(ingest, new NetworkGraphBuilder(),
                new ObstacleIndexBuilder(resolver, envelopes),
                new SpecialZoneIndexBuilder(resolver, new RestrictionAxisBuilder(), envelopes),
                variantGenerator, new GeoJsonResultWriter(objectMapper), objectMapper,
                appProperties, registry, approachResolver);
    }

    /**
     * FR-32/ТП 2.2: трасса может входить в `oks`-полигон только финальным
     * прямым выводом к собственной точке подключения (ADR-0024).
     */
    protected void assertNoOksCrossingBeyondApproach(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode inputRoot = mapper.readTree(input.toFile());
        com.fasterxml.jackson.databind.JsonNode resultRoot = mapper.readTree(result.toFile());
        List<Geometry> oksPolygons = new ArrayList<>();
        Map<String, Coordinate> connectionPoints = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature : inputRoot.path("features")) {
            com.fasterxml.jackson.databind.JsonNode properties = feature.path("properties");
            String type = properties.path("object_type").asText();
            if ("restriction".equals(type)
                    && "oks".equals(properties.path("restriction_type").asText())) {
                oksPolygons.add(GeoJsonGeometryParser.parse(feature.path("geometry")));
            } else if ("oks_connection_point".equals(type)) {
                com.fasterxml.jackson.databind.JsonNode c = feature.path("geometry").path("coordinates");
                connectionPoints.put(properties.path("id").asText(),
                        new Coordinate(c.get(0).asDouble(), c.get(1).asDouble()));
            }
        }
        int violations = 0;
        for (com.fasterxml.jackson.databind.JsonNode feature : resultRoot.path("features")) {
            com.fasterxml.jackson.databind.JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            Geometry line = GeoJsonGeometryParser.parse(feature.path("geometry"));
            Coordinate startPoint = connectionPoints.get(properties.path("start_node_id").asText());
            Coordinate endPoint = connectionPoints.get(properties.path("end_node_id").asText());
            for (Geometry polygon : oksPolygons) {
                Geometry intersection = line.intersection(polygon);
                if (intersection.isEmpty() || intersection.getLength() < 1e-9) {
                    continue;
                }
                if (!touchesOwnPoint(intersection, polygon, startPoint)
                        && !touchesOwnPoint(intersection, polygon, endPoint)) {
                    violations++;
                }
            }
        }
        assertThat(violations).isZero();
    }

    private boolean touchesOwnPoint(Geometry intersection, Geometry polygon, Coordinate point) {
        if (point == null || !polygon.covers(ru.lct.heating.domain.GeometrySupport.GEOMETRY_FACTORY
                .createPoint(point))) {
            return false;
        }
        return intersection.distance(ru.lct.heating.domain.GeometrySupport.GEOMETRY_FACTORY
                .createPoint(point)) < 1e-9;
    }

    /**
     * FR-83: start_node_id/end_node_id совпадают с геометрическими концами
     * LineString (для узлов, присутствующих в выводе или во входных точках).
     */
    protected void assertNodeReferencesMatchGeometry(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode resultRoot = mapper.readTree(result.toFile());
        com.fasterxml.jackson.databind.JsonNode inputRoot = mapper.readTree(input.toFile());
        Map<String, double[]> nodes = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature : resultRoot.path("features")) {
            String type = feature.path("properties").path("object_type").asText();
            if (("heat_chamber".equals(type) || "technical_node".equals(type))
                    && !feature.path("geometry").isNull()) {
                com.fasterxml.jackson.databind.JsonNode c = feature.path("geometry").path("coordinates");
                nodes.put(feature.path("properties").path("id").asText(),
                        new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            }
        }
        for (com.fasterxml.jackson.databind.JsonNode feature : inputRoot.path("features")) {
            if ("oks_connection_point".equals(feature.path("properties").path("object_type").asText())) {
                com.fasterxml.jackson.databind.JsonNode c = feature.path("geometry").path("coordinates");
                nodes.put(feature.path("properties").path("id").asText(),
                        new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            }
        }
        int checked = 0;
        for (com.fasterxml.jackson.databind.JsonNode feature : resultRoot.path("features")) {
            if (!"heat_network".equals(feature.path("properties").path("object_type").asText())) {
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode coords = feature.path("geometry").path("coordinates");
            double[] first = {coords.get(0).get(0).asDouble(), coords.get(0).get(1).asDouble()};
            double[] last = {coords.get(coords.size() - 1).get(0).asDouble(),
                    coords.get(coords.size() - 1).get(1).asDouble()};
            checked += matchNode(nodes, feature.path("properties").path("start_node_id").asText(), first);
            checked += matchNode(nodes, feature.path("properties").path("end_node_id").asText(), last);
        }
        assertThat(checked).isGreaterThan(0);
    }

    /** Точка подключения — лист: ровно одно инцидентное ребро (FR-25). */
    protected void assertConnectionPointsAreLeaves(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        Set<String> connectionPoints = new java.util.HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(input.toFile()).path("features")) {
            if ("oks_connection_point".equals(
                    feature.path("properties").path("object_type").asText())) {
                connectionPoints.add(feature.path("properties").path("id").asText());
            }
        }
        Map<String, Integer> degree = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(result.toFile()).path("features")) {
            if (!"heat_network".equals(feature.path("properties").path("object_type").asText())) {
                continue;
            }
            degree.merge(feature.path("properties").path("start_node_id").asText(), 1, Integer::sum);
            degree.merge(feature.path("properties").path("end_node_id").asText(), 1, Integer::sum);
        }
        for (String id : connectionPoints) {
            if (degree.containsKey(id)) {
                assertThat(degree.get(id)).as("точка подключения %s", id).isEqualTo(1);
            }
        }
    }

    /** Ветвления (степень ≥3) допускаются только в камерах (FR-25). */
    protected void assertBranchOnlyInChambers(Path result, ObjectMapper mapper) throws Exception {
        Set<String> chambers = new java.util.HashSet<>();
        Map<String, Integer> degree = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(result.toFile()).path("features")) {
            String type = feature.path("properties").path("object_type").asText();
            String id = feature.path("properties").path("id").asText();
            if ("heat_chamber".equals(type)) {
                chambers.add(id);
            }
            if ("heat_network".equals(type)) {
                degree.merge(feature.path("properties").path("start_node_id").asText(), 1,
                        Integer::sum);
                degree.merge(feature.path("properties").path("end_node_id").asText(), 1,
                        Integer::sum);
            }
        }
        assertThat(degree).isNotEmpty();
        for (Map.Entry<String, Integer> entry : degree.entrySet()) {
            if (entry.getValue() >= 3) {
                assertThat(chambers).as("ветвление в узле %s", entry.getKey())
                        .contains(entry.getKey());
            }
        }
    }

    private int matchNode(Map<String, double[]> nodes, String nodeId, double[] point) {
        double[] node = nodes.get(nodeId);
        if (node == null) {
            return 0;
        }
        double distance = Math.hypot(node[0] - point[0], node[1] - point[1]);
        assertThat(distance).isLessThan(1e-5);
        return 1;
    }

    protected HeatingTablesProperties diameters() {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5, 181, 74023));
        rows.add(row(65, 8.3, 245, 78631));
        rows.add(row(80, 13.2, 327, 83530));
        rows.add(row(100, 22.3, 419, 89748));
        rows.add(row(125, 40.2, 554, 97275));
        rows.add(row(150, 65.1, 696, 105507));
        rows.add(row(200, 152.3, 1042, 120275));
        rows.add(row(250, 274.9, 1379, 135323));
        rows.add(row(300, 437.4, 1718, 150022));
        rows.add(row(400, 943.1, 2477, 190299));
        rows.add(row(500, 1663.4, 3245, 224137));
        rows.add(row(600, 2627.7, 4037, 264790));
        rows.add(row(700, 3735.1, 4775, 324298));
        rows.add(row(800, 5296.8, 5644, 325996));
        rows.add(row(900, 7165.0, 6518, 327693));
        rows.add(row(1000, 9391.8, 7419, 418777));
        rows.add(row(1200, 15012.8, 9288, 428074));
        rows.add(row(1400, 22501.9, 11276, 683417));
        properties.setDiameters(rows);
        properties.setEnvelopes(List.of(
                envelope(50, 0.400, 0.125),
                envelope(65, 0.430, 0.140),
                envelope(80, 0.470, 0.160),
                envelope(100, 0.510, 0.180),
                envelope(125, 0.600, 0.225),
                envelope(150, 0.650, 0.250),
                envelope(200, 0.880, 0.315),
                envelope(250, 1.050, 0.400),
                envelope(300, 1.150, 0.450),
                envelope(400, 1.370, 0.560),
                envelope(500, 1.670, 0.710),
                envelope(600, 1.850, 0.800),
                envelope(700, 2.050, 0.900),
                envelope(800, 2.250, 1.000),
                envelope(900, 2.450, 1.100),
                envelope(1000, 2.650, 1.200),
                envelope(1200, 3.100, 1.425),
                envelope(1400, 3.450, 1.600)));
        return properties;
    }

    private EnvelopeRow envelope(int dn, double pairWidth, double height) {
        EnvelopeRow row = new EnvelopeRow();
        row.setDn(dn);
        row.setPairWidthM(pairWidth);
        row.setHeightM(height);
        return row;
    }

    private DiameterRow row(int dn, double capacity, double length, long cost) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(length);
        row.setNewCostPerM(cost);
        return row;
    }

    protected RestrictionRulesProperties rules() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        rules.put("oks", prohibitedWithOksBands());
        rules.put("water", prohibited(1.0));
        rules.put("railway", prohibited(1.0));
        rules.put("road", special(1.5, 45.0, 1.60));
        properties.setRules(rules);
        properties.setFallback(prohibited(1.0));
        return properties;
    }

    private RestrictionRule prohibitedWithOksBands() {
        RestrictionRule rule = prohibited(9.0);
        List<DistanceBand> bands = new ArrayList<>();
        bands.add(band(499, 5.0));
        bands.add(band(800, 7.0));
        bands.add(band(100000, 9.0));
        rule.setDistanceBands(bands);
        return rule;
    }

    private DistanceBand band(int maxDn, double distance) {
        DistanceBand band = new DistanceBand();
        band.setMaxDn(maxDn);
        band.setDistanceM(distance);
        return band;
    }

    private RestrictionRule prohibited(double distance) {
        RestrictionRule rule = new RestrictionRule();
        rule.setMode(RestrictionMode.PROHIBITED);
        rule.setMinDistanceM(distance);
        return rule;
    }

    private RestrictionRule special(double distance, double angle, double k) {
        RestrictionRule rule = new RestrictionRule();
        rule.setMode(RestrictionMode.SPECIAL);
        rule.setMinDistanceM(distance);
        rule.setAngleMinDeg(angle);
        rule.setKSpecial(k);
        return rule;
    }
}
