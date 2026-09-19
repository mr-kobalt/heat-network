package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
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
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;

class ForestPlannerTest {

    private final ForestPlanner planner = planner();

    @Test
    void plan_twoNearbyPoints_shareTrunkAndBranchInChamber() {
        OksConnectionPointObject a = connectionPoint("cp_a", 100, 100, 10.0);
        OksConnectionPointObject b = connectionPoint("cp_b", 300, 100, 20.0);
        NetworkDataset dataset = dataset(List.of(a, b));

        ForestPlanningResult result = planner.plan(dataset, graph(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>());

        assertThat(result.getUnconnectedConnectionPointIds()).isEmpty();
        assertThat(result.getTrees()).hasSize(1);
        ForestTree tree = result.getTrees().get(0);
        assertThat(tree.getEdges()).hasSize(2);

        ForestEdge connectionEdge = edgeFrom(tree, tree.getTieInNodeId());
        assertThat(connectionEdge.getFlowTph()).isEqualTo(30.0);
        assertThat(connectionEdge.getDiameterMm()).isEqualTo(200);

        String rootId = connectionEdge.getToNodeId();
        String leafId = rootId.equals("cp_a") ? "cp_b" : "cp_a";
        double leafFlow = leafId.equals("cp_a") ? 10.0 : 20.0;
        ForestEdge branchEdge = edgeTo(tree, leafId);
        assertThat(branchEdge.getFromNodeId()).isEqualTo(rootId);
        assertThat(branchEdge.getFlowTph()).isEqualTo(leafFlow);

        assertThat(tree.requireNode(leafId).getType()).isEqualTo(NodeType.CONNECTION_POINT);
        assertThat(tree.requireNode(tree.getTieInNodeId()).getType()).isEqualTo(NodeType.CHAMBER);
    }

    @Test
    void plan_pointNearExistingChamber_usesExistingChamber() {
        OksConnectionPointObject cp = connectionPoint("cp_a", 500, 5, 10.0);
        // Короткий участок у камеры: все кандидаты в пределах 10 м от неё.
        NetworkSegment segment = NetworkSegment.builder()
                .id("seg1").diameterMm(200)
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createLineString(
                        new Coordinate[]{new Coordinate(500, 0), new Coordinate(505, 0)}))
                .build();
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of(SourceObject.builder().id("src")
                        .geometry(GeometrySupport.GEOMETRY_FACTORY
                                .createPoint(new Coordinate(500, 0))).build()))
                .networkSegments(List.of(segment))
                .heatChambers(List.of(ru.lct.heating.domain.HeatChamberObject.builder()
                        .id("ch1")
                        .geometry(GeometrySupport.GEOMETRY_FACTORY
                                .createPoint(new Coordinate(500, 0))).build()))
                .oksFutures(List.of())
                .connectionPoints(List.of(cp))
                .oksExisting(List.of())
                .restrictions(List.of())
                .build();

        ForestPlanningResult result = planner.plan(dataset, graph(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>());

        assertThat(result.getTrees()).hasSize(1);
        ForestTree tree = result.getTrees().get(0);
        ForestNode connectionNode = tree.requireNode(tree.getTieInNodeId());
        assertThat(connectionNode.isExisting()).isTrue();
        assertThat(connectionNode.getId()).isEqualTo("ch1");
    }

    @Test
    void plan_connectionPointWithoutFlow_isUnconnected() {
        OksConnectionPointObject a = connectionPoint("cp_a", 100, 100, 10.0);
        OksConnectionPointObject b = connectionPoint("cp_b", 300, 100, null);
        NetworkDataset dataset = dataset(List.of(a, b));

        List<String> warnings = new ArrayList<>();
        ForestPlanningResult result = planner.plan(dataset, graph(dataset),
                new ObstacleIndex(List.of()), warnings);

        assertThat(result.getUnconnectedConnectionPointIds()).containsExactly("cp_b");
        assertThat(warnings).anyMatch(warning -> warning.contains("NO_FLOW"));
    }

    @Test
    void plan_highDegreeBranch_splitsIntoAdditionalChamber() {
        OksConnectionPointObject center = connectionPoint("cp_c", 500, 0, 5.0);
        List<OksConnectionPointObject> points = List.of(
                center,
                connectionPoint("cp_1", 571, 71, 5.0),
                connectionPoint("cp_2", 429, 71, 5.0),
                connectionPoint("cp_3", 429, -71, 5.0),
                connectionPoint("cp_4", 571, -71, 5.0));
        NetworkDataset dataset = dataset(points);

        ForestPlanningResult result = planner.plan(dataset, graph(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>());

        assertThat(result.getTrees()).hasSize(1);
        ForestTree tree = result.getTrees().get(0);
        assertThat(tree.getNodes().keySet()).anyMatch(id -> id.startsWith("br_"));
        for (ForestNode node : tree.getNodes().values()) {
            if (node.getType() == NodeType.CHAMBER) {
                assertThat(incidentEdges(tree, node.getId())).isLessThanOrEqualTo(4);
            }
        }
    }

    private int incidentEdges(ForestTree tree, String nodeId) {
        int count = 0;
        for (ForestEdge edge : tree.getEdges()) {
            if (edge.getFromNodeId().equals(nodeId) || edge.getToNodeId().equals(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private ForestEdge edgeFrom(ForestTree tree, String nodeId) {
        return tree.getEdges().stream()
                .filter(edge -> edge.getFromNodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }

    private ForestEdge edgeTo(ForestTree tree, String nodeId) {
        return tree.getEdges().stream()
                .filter(edge -> edge.getToNodeId().equals(nodeId))
                .findFirst().orElseThrow();
    }

    private ExistingNetworkGraph graph(NetworkDataset dataset) {
        return new NetworkGraphBuilder().build(dataset);
    }

    private ForestPlanner planner() {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5));
        rows.add(row(100, 22.3));
        rows.add(row(200, 152.3));
        rows.add(row(400, 943.1));
        tables.setDiameters(rows);
        DiameterCatalog catalog = new DiameterCatalog(tables);
        CostModel costModel = new CostModel(catalog, new CostProperties());
        AppProperties appProperties = new AppProperties();
        VisibilityGraphRouter router = new VisibilityGraphRouter(appProperties);
        LineStringSimplifier simplifier = new LineStringSimplifier();
        OksApproachResolver approachResolver = new OksApproachResolver(
                new ru.lct.heating.geometry.RestrictionRuleResolver(
                        new ru.lct.heating.geometry.RestrictionRulesProperties()),
                new ru.lct.heating.hydraulics.EnvelopeCatalog(new HeatingTablesProperties()));
        return new ForestPlanner(new TieInCandidateProvider(), router,
                catalog, costModel, new MaxLengthEnforcer(catalog),
                new RouteCrossingResolver(router, simplifier), approachResolver,
                simplifier, appProperties);
    }

    private DiameterRow row(int dn, double capacity) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(10000);
        row.setNewCostPerM(100000);
        return row;
    }

    private NetworkDataset dataset(List<OksConnectionPointObject> points) {
        LineString network = GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(0, 0), new Coordinate(1000, 0)});
        NetworkSegment segment = NetworkSegment.builder()
                .id("seg1")
                .diameterMm(200)
                .geometry(network)
                .build();
        SourceObject source = SourceObject.builder()
                .id("src")
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(0, 0)))
                .build();
        return NetworkDataset.builder()
                .sources(List.of(source))
                .networkSegments(List.of(segment))
                .heatChambers(List.of())
                .oksFutures(List.of())
                .connectionPoints(points)
                .oksExisting(List.of())
                .restrictions(List.of())
                .build();
    }

    private OksConnectionPointObject connectionPoint(String id, double x, double y, Double flow) {
        Point point = GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y));
        return OksConnectionPointObject.builder()
                .id(id)
                .flowTph(flow)
                .geometry(point)
                .build();
    }
}
