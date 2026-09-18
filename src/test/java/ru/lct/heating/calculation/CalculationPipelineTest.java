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
import ru.lct.heating.geometry.DistanceBand;
import ru.lct.heating.geometry.ObstacleIndexBuilder;
import ru.lct.heating.geometry.RestrictionMode;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.geometry.RestrictionRulesProperties;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.ingest.CrsTransformer;
import ru.lct.heating.ingest.FeatureParser;
import ru.lct.heating.ingest.GeoJsonStreamReader;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.output.ResultBuilder;
import ru.lct.heating.routing.RoutePlanner;
import ru.lct.heating.routing.TieInCandidateProvider;
import ru.lct.heating.routing.VisibilityGraphRouter;

/**
 * Сквозной прогон конвейера на конкурсном образце (без БД и Spring-контекста).
 */
class CalculationPipelineTest {

    @TempDir
    Path tempDir;

    @Test
    void producesPartialResultOnSampleDataset() throws Exception {
        Path sample = Path.of("source", "data_example.geojson");
        assumeSampleExists(sample);

        ObjectMapper objectMapper = new ObjectMapper();
        CrsTransformer crs = new CrsTransformer();
        IngestService ingest = new IngestService(new GeoJsonStreamReader(objectMapper), new FeatureParser(crs));
        DiameterCatalog catalog = new DiameterCatalog(diameters());
        CostModel costModel = new CostModel(catalog);

        CalculationService service = new CalculationService(
                ingest,
                new NetworkGraphBuilder(),
                new ObstacleIndexBuilder(new RestrictionRuleResolver(rules())),
                new RoutePlanner(new TieInCandidateProvider(), new VisibilityGraphRouter()),
                new ResultBuilder(crs, catalog, costModel),
                new GeoJsonResultWriter(objectMapper),
                objectMapper,
                new AppProperties());

        Path resultFile = tempDir.resolve("result.geojson");
        Path summaryFile = tempDir.resolve("summary.json");
        CalculationOutcome outcome = service.calculate(sample, resultFile, summaryFile);

        assertThat(Files.exists(resultFile)).isTrue();
        assertThat(Files.size(resultFile)).isGreaterThan(0);
        assertThat(outcome.getSummary().getCalculatedCost()).isGreaterThan(0);
        assertThat(outcome.getSummary().getNewNetworkLengthM()).isGreaterThan(0);
        String result = Files.readString(resultFile);
        assertThat(result).contains("\"object_type\":\"variant_summary\"");
        assertThat(result).contains("\"variant_id\":\"1\"");
    }

    private void assumeSampleExists(Path sample) {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(sample),
                "Образец source/data_example.geojson недоступен");
    }

    private HeatingTablesProperties diameters() {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5, 181, 74023, 96180));
        rows.add(row(65, 8.3, 245, 78631, 109989));
        rows.add(row(80, 13.2, 327, 83530, 117582));
        rows.add(row(100, 22.3, 419, 89748, 133694));
        rows.add(row(125, 40.2, 554, 97275, 148030));
        rows.add(row(150, 65.1, 696, 105507, 152295));
        rows.add(row(200, 152.3, 1042, 120275, 181766));
        rows.add(row(250, 274.9, 1379, 135323, 202030));
        rows.add(row(300, 437.4, 1718, 150022, 228707));
        rows.add(row(400, 943.1, 2477, 190299, 271317));
        properties.setDiameters(rows);
        return properties;
    }

    private DiameterRow row(int dn, double capacity, double length, long cost, long recon) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(length);
        row.setNewCostPerM(cost);
        row.setReconstructionCostPerM(recon);
        return row;
    }

    private RestrictionRulesProperties rules() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        rules.put("oks", prohibitedWithOksBands());
        rules.put("water", prohibited(1.0));
        rules.put("railway", special(1.5, 1.75));
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

    private RestrictionRule special(double distance, double kSpecial) {
        RestrictionRule rule = new RestrictionRule();
        rule.setMode(RestrictionMode.SPECIAL);
        rule.setMinDistanceM(distance);
        rule.setKSpecial(kSpecial);
        return rule;
    }
}
