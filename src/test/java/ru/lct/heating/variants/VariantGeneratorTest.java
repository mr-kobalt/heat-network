package ru.lct.heating.variants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.cost.CostProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.SourceObject;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.ObstacleMaskBuilder;
import ru.lct.heating.geometry.RestrictionMode;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.geometry.RestrictionRulesProperties;
import ru.lct.heating.geometry.SpecialSpanSplitter;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;
import ru.lct.heating.output.ForestResultBuilder;
import ru.lct.heating.output.VariantResult;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.CellStoreFactory;
import ru.lct.heating.routing.GridForestPlanner;
import ru.lct.heating.routing.OksApproachResolver;
import ru.lct.heating.routing.TieInCandidateProvider;
import ru.lct.heating.routing.algorithm.GridForestTracingAlgorithm;
import ru.lct.heating.routing.algorithm.TracingAlgorithm;

class VariantGeneratorTest {

    @Test
    void generate_multipleClusters_producesRankedVariants() {
        AppProperties appProperties = new AppProperties();
        DiameterCatalog catalog = catalog();
        CostModel costModel = new CostModel(catalog, new CostProperties());
        LineStringSimplifier simplifier = new LineStringSimplifier();
        GridForestPlanner planner = new GridForestPlanner(new TieInCandidateProvider(appProperties),
                catalog,
                costModel, new MaxLengthEnforcer(catalog), simplifier, new ObstacleMaskBuilder(),
                new CellStoreFactory(appProperties, null), appProperties, approachResolver());
        ForestResultBuilder resultBuilder = new ForestResultBuilder(
                new ru.lct.heating.ingest.CrsTransformer(), costModel, new SpecialSpanSplitter(),
                new ru.lct.heating.depth.DepthProfileBuilder(appProperties,
                        new EnvelopeCatalog(tables())),
                appProperties);
        VariantGenerator generator = new VariantGenerator(resultBuilder);
        TracingAlgorithm algorithm = new GridForestTracingAlgorithm(planner);

        NetworkDataset dataset = dataset();
        Map<String, ConnectionExit> exits = new LinkedHashMap<>();
        for (var point : dataset.getConnectionPoints()) {
            exits.put(point.getId(), ConnectionExit.builder()
                    .connectionPointId(point.getId())
                    .target(point.getGeometry().getCoordinate())
                    .tail(List.of())
                    .blocked(false)
                    .designDiameterMm(100)
                    .build());
        }
        List<VariantResult> variants = generator.generate(dataset, new ObstacleIndex(List.of()),
                new ru.lct.heating.graph.NetworkGraphBuilder().build(dataset),
                new SpecialZoneIndex(List.of()), new ArrayList<>(), exits, algorithm);

        assertThat(variants).isNotEmpty();
        assertThat(variants.size()).isLessThanOrEqualTo(VariantGenerator.MAX_VARIANTS);
        for (int i = 0; i < variants.size(); i++) {
            assertThat(variants.get(i).getSummary().getRank()).isEqualTo(i + 1);
            if (i > 0) {
                assertThat(variants.get(i).getSummary().getScore())
                        .isGreaterThanOrEqualTo(variants.get(i - 1).getSummary().getScore());
            }
        }
    }

    private DiameterCatalog catalog() {
        return new DiameterCatalog(tables());
    }

    private HeatingTablesProperties tables() {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5));
        rows.add(row(100, 22.3));
        rows.add(row(200, 152.3));
        rows.add(row(400, 943.1));
        tables.setDiameters(rows);
        tables.setEnvelopes(new ArrayList<>());
        return tables;
    }

    private OksApproachResolver approachResolver() {
        HeatingTablesProperties tables = tables();
        RestrictionRulesProperties rulesProperties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        RestrictionRule oks = new RestrictionRule();
        oks.setMode(RestrictionMode.PROHIBITED);
        oks.setMinDistanceM(5.0);
        rules.put("oks", oks);
        RestrictionRule fallback = new RestrictionRule();
        fallback.setMode(RestrictionMode.PROHIBITED);
        fallback.setMinDistanceM(1.0);
        rulesProperties.setRules(rules);
        rulesProperties.setFallback(fallback);
        return new OksApproachResolver(new RestrictionRuleResolver(rulesProperties),
                new EnvelopeCatalog(tables), new DiameterCatalog(tables), new AppProperties());
    }

    private DiameterRow row(int dn, double capacity) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(10000);
        row.setNewCostPerM(100000);
        return row;
    }

    private NetworkDataset dataset() {
        LineString network = GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(0, 0), new Coordinate(2000, 0)});
        NetworkSegment segment = NetworkSegment.builder()
                .id("seg1").diameterMm(400)
                .geometry(network).build();
        SourceObject source = SourceObject.builder().id("src")
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(0, 0))).build();
        return NetworkDataset.builder()
                .sources(List.of(source))
                .networkSegments(List.of(segment))
                .heatChambers(List.of())
                .connectionPoints(List.of(
                        point("a", 100, 100, 10.0),
                        point("b", 500, 100, 20.0),
                        point("c", 1500, 100, 30.0)))
                .restrictions(List.of())
                .build();
    }

    private OksConnectionPointObject point(String id, double x, double y, double flow) {
        return OksConnectionPointObject.builder().id(id).flowTph(flow)
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y))).build();
    }
}
