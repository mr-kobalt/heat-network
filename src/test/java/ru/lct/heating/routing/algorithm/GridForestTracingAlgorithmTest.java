package ru.lct.heating.routing.algorithm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.cost.CostProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.domain.SourceObject;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.ObstacleMaskBuilder;
import ru.lct.heating.geometry.RestrictionMode;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.geometry.RestrictionRulesProperties;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.ForestEdge;
import ru.lct.heating.routing.ForestNode;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.routing.ForestTree;
import ru.lct.heating.routing.CellStoreFactory;
import ru.lct.heating.routing.GridForestPlanner;
import ru.lct.heating.routing.NodeType;
import ru.lct.heating.routing.OksApproachResolver;
import ru.lct.heating.routing.TieInCandidateProvider;

class GridForestTracingAlgorithmTest {

    private final GridForestTracingAlgorithm algorithm = new GridForestTracingAlgorithm(planner());

    @Test
    void exposesTemplateIdentity() {
        assertThat(algorithm.id()).isEqualTo("grid-forest");
        assertThat(algorithm.description()).contains("лес");
    }

    @Test
    void plan_withoutExits_marksPointsUnconnected() {
        NetworkDataset dataset = dataset(List.of(), List.of(
                point("1", 0, 0, 1.0), point("2", 10, 0, 1.0)));

        ForestPlanningResult plan = algorithm.plan(dataset, new NetworkGraphBuilder().build(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>(), Map.of()).get(0);

        assertThat(plan.getTrees()).isEmpty();
        assertThat(plan.getUnconnectedConnectionPointIds()).containsExactlyInAnyOrder("1", "2");
    }

    @Test
    void plan_withoutConnectionPoints_returnsEmptyUnconnectedList() {
        NetworkDataset dataset = dataset(List.of(), List.of());

        ForestPlanningResult plan = algorithm.plan(dataset, new NetworkGraphBuilder().build(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>(), Map.of()).get(0);

        assertThat(plan.getTrees()).isEmpty();
        assertThat(plan.getUnconnectedConnectionPointIds()).isEmpty();
    }

    @Test
    void plan_pointsNearNetwork_connectsWithoutUnconnected() {
        NetworkSegment segment = NetworkSegment.builder().id("seg1").diameterMm(400)
                .geometry(line(0, 0, 1000, 0)).build();
        OksConnectionPointObject a = point("a", 100, 100, 10.0);
        OksConnectionPointObject b = point("b", 300, 100, 20.0);
        NetworkDataset dataset = dataset(List.of(segment), List.of(a, b));
        Map<String, ConnectionExit> exits = Map.of("a", directExit(a), "b", directExit(b));

        ForestPlanningResult plan = algorithm.plan(dataset, new NetworkGraphBuilder().build(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>(), exits).get(0);

        assertThat(plan.getUnconnectedConnectionPointIds()).isEmpty();
        assertThat(plan.getTrees()).isNotEmpty();
        boolean hasA = false;
        boolean hasB = false;
        for (ForestTree tree : plan.getTrees()) {
            assertThat(tree.requireNode(tree.getTieInNodeId())).isNotNull();
            for (ForestNode node : tree.getNodes().values()) {
                if (node.getId().equals("a")) {
                    hasA = true;
                    assertThat(node.getCoordinate().x).isEqualTo(100.0);
                }
                if (node.getId().equals("b")) {
                    hasB = true;
                    assertThat(node.getCoordinate().x).isEqualTo(300.0);
                }
            }
            for (var edge : tree.getEdges()) {
                assertThat(tree.getNodes()).containsKey(edge.getFromNodeId());
                assertThat(tree.getNodes()).containsKey(edge.getToNodeId());
            }
        }
        assertThat(hasA).isTrue();
        assertThat(hasB).isTrue();
        assertConnectionPointsAreLeaves(plan);
    }

    @Test
    void isSelectableThroughRegistry() {
        AppProperties properties = new AppProperties();
        TracingAlgorithmRegistry registry = new TracingAlgorithmRegistry(
                List.of(algorithm), properties);

        assertThat(registry.require("grid-forest").id()).isEqualTo("grid-forest");
        assertThat(registry.available()).extracting(AlgorithmInfo::getId)
                .containsExactly("grid-forest");
        assertThat(registry.defaultId()).isEqualTo("grid-forest");
    }

    @Test
    void plan_twoClosePointsFarFromNetwork_shareTree() {
        NetworkSegment segment = NetworkSegment.builder().id("seg1").diameterMm(400)
                .geometry(line(0, 0, 2000, 0)).build();
        OksConnectionPointObject a = point("a", 900, 400, 10.0);
        OksConnectionPointObject b = point("b", 950, 430, 20.0);
        NetworkDataset dataset = dataset(List.of(segment), List.of(a, b));
        Map<String, ConnectionExit> exits = Map.of("a", directExit(a), "b", directExit(b));

        ForestPlanningResult plan = algorithm.plan(dataset, new NetworkGraphBuilder().build(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>(), exits).get(0);

        assertThat(plan.getUnconnectedConnectionPointIds()).isEmpty();
        assertThat(plan.getTrees()).hasSize(1);
    }

    @Test
    void plan_pointInOks_keepsExitVertex() {
        Polygon building = square(800, 300, 1000, 500);
        OksConnectionPointObject a = point("a", 805, 400, 10.0);
        RestrictionObject oks = RestrictionObject.builder().id("oks").restrictionType("oks")
                .geometry(building).build();
        NetworkSegment segment = NetworkSegment.builder().id("seg1").diameterMm(400)
                .geometry(line(0, 0, 2000, 0)).build();
        NetworkDataset dataset = dataset(List.of(segment), List.of(a), List.of(oks));
        Map<String, ConnectionExit> exits = resolver().resolveExits(dataset);
        assertThat(exits.get("a").isBlocked()).isFalse();

        ForestPlanningResult plan = algorithm.plan(dataset, new NetworkGraphBuilder().build(dataset),
                new ObstacleIndex(List.of()), new ArrayList<>(), exits).get(0);

        ForestEdge edge = null;
        for (ForestTree tree : plan.getTrees()) {
            for (ForestEdge candidate : tree.getEdges()) {
                if (candidate.getToNodeId().equals("a") || candidate.getFromNodeId().equals("a")) {
                    edge = candidate;
                }
            }
        }
        assertThat(edge).isNotNull();
        List<Coordinate> coordinates = edge.getToNodeId().equals("a")
                ? edge.getCoordinates() : reversed(edge.getCoordinates());
        Coordinate point = a.getGeometry().getCoordinate();
        Coordinate beforePoint = coordinates.get(coordinates.size() - 2);
        assertThat(beforePoint.distance(point))
                .as("выход (target) должен присутствовать как вершина")
                .isGreaterThan(2.0);
        assertThat(beforePoint.distance(exits.get("a").getTarget())).isLessThan(0.5);
    }

    private List<Coordinate> reversed(List<Coordinate> coordinates) {
        List<Coordinate> result = new ArrayList<>(coordinates);
        java.util.Collections.reverse(result);
        return result;
    }

    @Test
    void plan_respectsTurnLimit() {
        NetworkSegment segment = NetworkSegment.builder().id("seg1").diameterMm(400)
                .geometry(line(0, 0, 1000, 0)).build();
        OksConnectionPointObject a = point("a", 100, 100, 10.0);
        OksConnectionPointObject b = point("b", 300, 100, 20.0);
        NetworkDataset dataset = dataset(List.of(segment), List.of(a, b));
        Map<String, ConnectionExit> exits = Map.of("a", directExit(a), "b", directExit(b));
        List<String> warnings = new ArrayList<>();

        ForestPlanningResult plan = algorithm.plan(dataset, new NetworkGraphBuilder().build(dataset),
                new ObstacleIndex(List.of()), warnings, exits).get(0);

        assertThat(warnings).noneMatch(warning -> warning.startsWith("FOREST_TURN_UNRESOLVED"));
        for (ForestTree tree : plan.getTrees()) {
            for (ForestEdge edge : tree.getEdges()) {
                assertThat(maxTurn(edge)).isLessThanOrEqualTo(90.5);
            }
        }
    }

    /**
     * FR-34: стык вывода из ОКС не превышает 90° — при недопустимом угле
     * вставляется промежуточная вершина (два соседних поворота ≤90°).
     */
    @Test
    void plan_exitFromOks_respectsTurnLimit() {
        Polygon building = square(800, 300, 1000, 500);
        OksConnectionPointObject a = point("a", 900, 400, 10.0);
        RestrictionObject oks = RestrictionObject.builder().id("oks").restrictionType("oks")
                .geometry(building).build();
        NetworkSegment segment = NetworkSegment.builder().id("seg1").diameterMm(400)
                .geometry(line(0, 0, 2000, 0)).build();
        NetworkDataset dataset = dataset(List.of(segment), List.of(a), List.of(oks));
        List<String> warnings = new ArrayList<>();

        ForestPlanningResult plan = algorithm.plan(dataset,
                new NetworkGraphBuilder().build(dataset), new ObstacleIndex(List.of()), warnings,
                resolver().resolveExits(dataset)).get(0);

        assertThat(warnings).noneMatch(warning -> warning.startsWith("FOREST_TURN_UNRESOLVED"));
        for (ForestTree tree : plan.getTrees()) {
            for (ForestEdge edge : tree.getEdges()) {
                assertThat(maxTurn(edge)).as("ребро %s", edge.getId()).isLessThanOrEqualTo(90.5);
            }
        }
    }

    /**
     * ADR-0037: план возвращается списком (варианты по проходам); все точки
     * подключены, листья сохранены, повороты в пределах.
     */
    @Test
    void plan_returnsPlansList() {
        NetworkSegment segment = NetworkSegment.builder().id("seg1").diameterMm(400)
                .geometry(line(0, 0, 2000, 0)).build();
        OksConnectionPointObject a = point("a", 900, 400, 10.0);
        OksConnectionPointObject b = point("b", 950, 430, 20.0);
        NetworkDataset dataset = dataset(List.of(segment), List.of(a, b));

        List<ForestPlanningResult> plans = algorithm.plan(dataset,
                new NetworkGraphBuilder().build(dataset), new ObstacleIndex(List.of()),
                new ArrayList<>(), Map.of("a", directExit(a), "b", directExit(b)));

        assertThat(plans).isNotEmpty();
        for (ForestPlanningResult plan : plans) {
            assertThat(plan.getUnconnectedConnectionPointIds()).isEmpty();
            assertThat(plan.getTrees()).isNotEmpty();
            for (ForestTree tree : plan.getTrees()) {
                for (ForestEdge edge : tree.getEdges()) {
                    assertThat(maxTurn(edge)).as("ребро %s", edge.getId()).isLessThanOrEqualTo(90.5);
                }
            }
            assertConnectionPointsAreLeaves(plan);
        }
    }

    private double maxTurn(ForestEdge edge) {
        List<Coordinate> coordinates = edge.getCoordinates();
        double max = 0.0;
        for (int i = 1; i < coordinates.size() - 1; i++) {
            Coordinate previous = coordinates.get(i - 1);
            Coordinate vertex = coordinates.get(i);
            Coordinate next = coordinates.get(i + 1);
            double inX = vertex.x - previous.x;
            double inY = vertex.y - previous.y;
            double outX = next.x - vertex.x;
            double outY = next.y - vertex.y;
            double angle = Math.toDegrees(Math.atan2(
                    Math.abs(inX * outY - inY * outX), inX * outX + inY * outY));
            max = Math.max(max, angle);
        }
        return max;
    }

    private void assertConnectionPointsAreLeaves(ForestPlanningResult plan) {
        for (ForestTree tree : plan.getTrees()) {
            for (ForestNode node : tree.getNodes().values()) {
                if (node.getId().equals("a") || node.getId().equals("b")
                        || node.getId().equals("1") || node.getId().equals("2")) {
                    assertThat(node.getType())
                            .as("точка подключения %s", node.getId())
                            .isEqualTo(NodeType.CONNECTION_POINT);
                    assertThat(incidentEdges(tree, node.getId())).isEqualTo(1);
                }
            }
        }
    }

    private int incidentEdges(ForestTree tree, String nodeId) {
        int count = 0;
        for (var edge : tree.getEdges()) {
            if (edge.getFromNodeId().equals(nodeId) || edge.getToNodeId().equals(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private GridForestPlanner planner() {
        return planner(new AppProperties());
    }

    private GridForestPlanner planner(AppProperties appProperties) {
        HeatingTablesProperties tables = tables();
        DiameterCatalog catalog = new DiameterCatalog(tables);
        CostModel costModel = new CostModel(catalog, new CostProperties());
        return new GridForestPlanner(new TieInCandidateProvider(appProperties), catalog, costModel,
                new MaxLengthEnforcer(catalog), new LineStringSimplifier(), new ObstacleMaskBuilder(),
                new CellStoreFactory(appProperties, null), appProperties, resolver());
    }

    private HeatingTablesProperties tables() {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5));
        rows.add(row(100, 22.3));
        rows.add(row(200, 152.3));
        rows.add(row(400, 943.1));
        tables.setDiameters(rows);
        return tables;
    }

    private OksApproachResolver resolver() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        RestrictionRule oks = new RestrictionRule();
        oks.setMode(RestrictionMode.PROHIBITED);
        oks.setMinDistanceM(5.0);
        rules.put("oks", oks);
        properties.setRules(rules);
        HeatingTablesProperties tables = tables();
        return new OksApproachResolver(new RestrictionRuleResolver(properties),
                new EnvelopeCatalog(tables), new DiameterCatalog(tables), new AppProperties());
    }

    private DiameterRow row(int dn, double capacityTph) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacityTph);
        row.setMaxLengthM(10000);
        row.setNewCostPerM(100000);
        return row;
    }

    private NetworkDataset dataset(List<NetworkSegment> segments,
                                   List<OksConnectionPointObject> points) {
        return dataset(segments, points, List.of());
    }

    private NetworkDataset dataset(List<NetworkSegment> segments,
                                   List<OksConnectionPointObject> points,
                                   List<RestrictionObject> restrictions) {
        return NetworkDataset.builder()
                .sources(List.of(SourceObject.builder().id("src").geometry(
                        GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(0, 0))).build()))
                .networkSegments(segments)
                .heatChambers(List.of())
                .connectionPoints(points)
                .oksFutures(List.of())
                .oksExisting(List.of())
                .restrictions(restrictions)
                .build();
    }

    private Polygon square(double minX, double minY, double maxX, double maxY) {
        return GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)});
    }

    private ConnectionExit directExit(OksConnectionPointObject point) {
        return ConnectionExit.builder()
                .connectionPointId(point.getId())
                .target(point.getGeometry().getCoordinate())
                .tail(List.of())
                .blocked(false)
                .designDiameterMm(100)
                .build();
    }

    private OksConnectionPointObject point(String id, double x, double y, Double flow) {
        Point geometry = GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y));
        return OksConnectionPointObject.builder()
                .id(id).flowTph(flow).numericId(false).geometry(geometry).build();
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
