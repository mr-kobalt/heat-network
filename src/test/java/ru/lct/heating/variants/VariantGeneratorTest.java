package ru.lct.heating.variants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
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
import ru.lct.heating.geometry.SpecialSpanSplitter;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;
import ru.lct.heating.output.ForestResultBuilder;
import ru.lct.heating.output.VariantResult;
import ru.lct.heating.routing.ForestPlanner;
import ru.lct.heating.routing.RouteCrossingResolver;
import ru.lct.heating.routing.TieInCandidateProvider;
import ru.lct.heating.routing.VisibilityGraphRouter;

class VariantGeneratorTest {

    @Test
    void generate_multipleClusters_producesRankedVariants() {
        AppProperties appProperties = new AppProperties();
        appProperties.setClusterRadiusM(500.0);
        DiameterCatalog catalog = catalog();
        CostModel costModel = new CostModel(catalog, new CostProperties());

        VisibilityGraphRouter router = new VisibilityGraphRouter(appProperties);
        LineStringSimplifier simplifier = new LineStringSimplifier();
        ru.lct.heating.routing.OksApproachResolver approachResolver =
                new ru.lct.heating.routing.OksApproachResolver(
                        new ru.lct.heating.geometry.RestrictionRuleResolver(
                                new ru.lct.heating.geometry.RestrictionRulesProperties()),
                        new ru.lct.heating.hydraulics.EnvelopeCatalog(new HeatingTablesProperties()));
        ForestPlanner planner = new ForestPlanner(new TieInCandidateProvider(),
                router, catalog, costModel, new MaxLengthEnforcer(catalog),
                new RouteCrossingResolver(router, simplifier), approachResolver,
                simplifier, appProperties);
        ForestResultBuilder resultBuilder = new ForestResultBuilder(
                new ru.lct.heating.ingest.CrsTransformer(), costModel, new SpecialSpanSplitter(),
                appProperties);
        VariantGenerator generator = new VariantGenerator(planner, resultBuilder, appProperties);

        NetworkDataset dataset = dataset();
        List<VariantResult> variants = generator.generate(dataset, new ObstacleIndex(List.of()),
                new ru.lct.heating.graph.NetworkGraphBuilder().build(dataset),
                new SpecialZoneIndex(List.of()), new ArrayList<>());

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
        HeatingTablesProperties tables = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5));
        rows.add(row(100, 22.3));
        rows.add(row(200, 152.3));
        rows.add(row(400, 943.1));
        tables.setDiameters(rows);
        return new DiameterCatalog(tables);
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
                .oksFutures(List.of())
                .connectionPoints(List.of(
                        point("a", 100, 100, 10.0),
                        point("b", 500, 100, 20.0),
                        point("c", 1500, 100, 30.0)))
                .oksExisting(List.of())
                .restrictions(List.of())
                .build();
    }

    private OksConnectionPointObject point(String id, double x, double y, double flow) {
        return OksConnectionPointObject.builder().id(id).flowTph(flow)
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y))).build();
    }
}
