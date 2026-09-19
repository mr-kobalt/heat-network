package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
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
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;
import ru.lct.heating.ingest.CrsTransformer;
import ru.lct.heating.ingest.FeatureParser;
import ru.lct.heating.ingest.GeoJsonStreamReader;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.ForestResultBuilder;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.routing.ForestPlanner;
import ru.lct.heating.routing.RouteCrossingResolver;
import ru.lct.heating.routing.TieInCandidateProvider;
import ru.lct.heating.routing.VisibilityGraphRouter;
import ru.lct.heating.variants.VariantGenerator;

/**
 * Сквозной прогон конвейера на скорректированном наборе (без БД и Spring).
 */
class CalculationPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void producesResultOnCorrectedDataset() throws Exception {
        Path sample = Path.of("source", "Датасет скорректированный.geojson");
        assumeSampleExists(sample);

        ObjectMapper objectMapper = new ObjectMapper();
        CrsTransformer crs = new CrsTransformer();
        IngestService ingest = new IngestService(new GeoJsonStreamReader(objectMapper), new FeatureParser(crs));
        HeatingTablesProperties tables = diameters();
        DiameterCatalog catalog = new DiameterCatalog(tables);
        EnvelopeCatalog envelopes = new EnvelopeCatalog(tables);
        CostModel costModel = new CostModel(catalog, new CostProperties());
        RestrictionRuleResolver resolver = new RestrictionRuleResolver(rules());
        AppProperties appProperties = new AppProperties();
        // Production-подобная конфигурация: габариты (полуширина) и штраф поворота.
        appProperties.setTurnPenaltyM(30.0);
        VisibilityGraphRouter router = new VisibilityGraphRouter(appProperties);
        LineStringSimplifier simplifier = new LineStringSimplifier();

        CalculationService service = new CalculationService(
                ingest,
                new NetworkGraphBuilder(),
                new ObstacleIndexBuilder(resolver, envelopes),
                new SpecialZoneIndexBuilder(resolver, new RestrictionAxisBuilder(), envelopes),
                new VariantGenerator(
                        new ForestPlanner(new TieInCandidateProvider(), router,
                                catalog, costModel, new MaxLengthEnforcer(catalog),
                                new RouteCrossingResolver(router, simplifier),
                                new ru.lct.heating.routing.OksApproachResolver(resolver, envelopes),
                                simplifier, appProperties),
                        new ForestResultBuilder(crs, costModel, new SpecialSpanSplitter(),
                                appProperties),
                        appProperties),
                new GeoJsonResultWriter(objectMapper),
                objectMapper,
                appProperties);

        Path resultFile = tempDir.resolve("result.geojson");
        Path summaryFile = tempDir.resolve("summary.json");
        CalculationOutcome outcome = service.calculate(sample, resultFile, summaryFile);

        assertThat(Files.exists(resultFile)).isTrue();
        assertThat(Files.size(resultFile)).isGreaterThan(0);
        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getCalculatedCost()).isGreaterThan(0);
        assertThat(outcome.getSummary().getNewNetworkLengthM()).isGreaterThan(0);
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        String result = Files.readString(resultFile);
        assertThat(result).contains("\"object_type\":\"variant_summary\"");
        assertThat(result).contains("\"variant_id\":\"v1\"");
        assertThat(result).doesNotContain("\"object_type\":\"tie_in\"");
        assertThat(result).doesNotContain("reconstruction");
        assertNodeReferencesMatchGeometry(sample, resultFile, objectMapper);
    }

    /**
     * FR-83: start_node_id/end_node_id совпадают с геометрическими концами
     * LineString (для узлов, присутствующих в выводе или во входных точках).
     */
    private void assertNodeReferencesMatchGeometry(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode resultRoot = mapper.readTree(result.toFile());
        com.fasterxml.jackson.databind.JsonNode inputRoot = mapper.readTree(input.toFile());
        Map<String, double[]> nodes = new java.util.HashMap<>();
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

    private int matchNode(Map<String, double[]> nodes, String nodeId, double[] point) {
        double[] node = nodes.get(nodeId);
        if (node == null) {
            return 0;
        }
        double distance = Math.hypot(node[0] - point[0], node[1] - point[1]);
        assertThat(distance).isLessThan(1e-5);
        return 1;
    }

    private void assumeSampleExists(Path sample) {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(sample),
                "Набор source/Датасет скорректированный.geojson недоступен");
    }

    private HeatingTablesProperties diameters() {
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
        properties.setDiameters(rows);
        properties.setEnvelopes(List.of(
                envelope(100, 0.510, 0.180),
                envelope(200, 0.880, 0.315),
                envelope(400, 1.370, 0.560),
                envelope(500, 1.670, 0.710)));
        return properties;
    }

    private ru.lct.heating.hydraulics.EnvelopeRow envelope(int dn, double pairWidth, double height) {
        ru.lct.heating.hydraulics.EnvelopeRow row = new ru.lct.heating.hydraulics.EnvelopeRow();
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

    private RestrictionRulesProperties rules() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        rules.put("oks", prohibitedWithOksBands());
        rules.put("water", prohibited(1.0));
        rules.put("railway", prohibited(1.0));
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
}
