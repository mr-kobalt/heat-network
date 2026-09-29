package ru.lct.heating.output;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.calculation.CalculationMode;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.cost.CostProperties;
import ru.lct.heating.depth.DepthProfileBuilder;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.SpecialSpanSplitter;
import ru.lct.heating.geometry.SpecialZone;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.ingest.CrsTransformer;
import ru.lct.heating.routing.ForestEdge;
import ru.lct.heating.routing.ForestNode;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.routing.ForestTree;
import ru.lct.heating.routing.NodeType;

/**
 * ADR-0073: вывод режима глубины — {@code depth_start}/{@code depth_end},
 * технические узлы на границах профиля и {@code Kгл} в стоимости.
 */
class ForestResultBuilderDepthTest {

    private final AppProperties properties = new AppProperties();

    @Test
    void depthModeWritesProfileAndTechnicalNodes() {
        VariantResult variant = build(CalculationMode.DEPTH);

        assertThat(variant.getSegments()).hasSize(5);
        assertThat(variant.getTechnicalNodes()).hasSize(4);
        List<Double> starts = depths(variant, true);
        List<Double> ends = depths(variant, false);
        assertThat(starts).startsWith(3.0).endsWith(3.0);
        assertThat(starts).anyMatch(value -> Math.abs(value - 2.42) < 1e-6);
        assertThat(ends).anyMatch(value -> Math.abs(value - 2.42) < 1e-6);
    }

    @Test
    void deepDeviationIncreasingCost() {
        properties.setDepthMinM(2.9);
        VariantResult depth = build(CalculationMode.DEPTH);
        VariantResult flat = build(CalculationMode.TWO_D);

        assertThat(depths(depth, false)).anyMatch(value -> Math.abs(value - 3.4) < 1e-6);
        assertThat(depth.getSummary().getCalculatedCost())
                .isGreaterThan(flat.getSummary().getCalculatedCost());
    }

    @Test
    void twoDModeKeepsNullDepths() {
        VariantResult variant = build(CalculationMode.TWO_D);

        assertThat(depths(variant, true)).containsOnlyNulls();
        assertThat(depths(variant, false)).containsOnlyNulls();
    }

    private VariantResult build(CalculationMode mode) {
        HeatingTablesProperties tables = tables();
        DiameterCatalog catalog = new DiameterCatalog(tables);
        EnvelopeCatalog envelopes = new EnvelopeCatalog(tables);
        CostModel costModel = new CostModel(catalog, new CostProperties());
        ForestResultBuilder builder = new ForestResultBuilder(new CrsTransformer(), costModel,
                new SpecialSpanSplitter(), new DepthProfileBuilder(properties, envelopes),
                properties);

        ForestNode chamber = ForestNode.builder().id("ch1").type(NodeType.CHAMBER)
                .coordinate(new Coordinate(0, 0)).existing(false).build();
        ForestNode connection = ForestNode.builder().id("cp1").type(NodeType.CONNECTION_POINT)
                .coordinate(new Coordinate(40, 0)).build();
        ForestEdge edge = ForestEdge.builder().id("e1").fromNodeId("ch1").toNodeId("cp1")
                .coordinates(List.of(new Coordinate(0, 0), new Coordinate(40, 0)))
                .flowTph(10.0).diameterMm(100).build();
        ForestTree tree = ForestTree.builder().tieInNodeId("ch1")
                .nodes(index(chamber, connection)).edges(List.of(edge)).build();
        ForestPlanningResult planning = ForestPlanningResult.builder()
                .trees(List.of(tree)).unconnectedConnectionPointIds(List.of()).build();

        OksConnectionPointObject point = OksConnectionPointObject.builder().id("cp1").flowTph(10.0)
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(40, 0)))
                .build();
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of()).networkSegments(List.of()).heatChambers(List.of())
                .connectionPoints(List.of(point))
                .restrictions(List.of()).build();

        return builder.build(planning, dataset, gasZone(), new ObstacleIndex(List.of()),
                new ArrayList<>(), "v1", 1, 1, mode);
    }

    private SpecialZoneIndex gasZone() {
        LineString axis = GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(20, -5), new Coordinate(20, 5)});
        Geometry zone = axis.buffer(2.5);
        return new SpecialZoneIndex(List.of(SpecialZone.builder()
                .restrictionType("gas_pipeline").kSpecial(1.25).bufferM(2.5)
                .verticalTopDepthM(2.8).verticalHeightM(0.4)
                .axis(axis).zone(zone).build()));
    }

    private List<Double> depths(VariantResult variant, boolean start) {
        List<Double> values = new ArrayList<>();
        for (OutputSegment segment : variant.getSegments()) {
            values.add(start ? segment.getDepthStart() : segment.getDepthEnd());
        }
        return values;
    }

    private java.util.Map<String, ForestNode> index(ForestNode... nodes) {
        java.util.Map<String, ForestNode> map = new java.util.LinkedHashMap<>();
        for (ForestNode node : nodes) {
            map.put(node.getId(), node);
        }
        return map;
    }

    private HeatingTablesProperties tables() {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        List<DiameterRow> diameters = new ArrayList<>();
        DiameterRow row = new DiameterRow();
        row.setDn(100);
        row.setCapacityTph(22.3);
        row.setMaxLengthM(419);
        row.setNewCostPerM(89748);
        diameters.add(row);
        tables.setDiameters(diameters);
        List<EnvelopeRow> envelopes = new ArrayList<>();
        EnvelopeRow envelope = new EnvelopeRow();
        envelope.setDn(100);
        envelope.setHeightM(0.18);
        envelope.setPairWidthM(0.51);
        envelopes.add(envelope);
        tables.setEnvelopes(envelopes);
        return tables;
    }
}
