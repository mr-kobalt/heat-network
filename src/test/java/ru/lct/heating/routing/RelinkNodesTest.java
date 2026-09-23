package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.cost.CostProperties;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

/** ADR-0044: перестройка дерева — перенос промежуточных узлов с поддеревом. */
class RelinkNodesTest {

    private final AppProperties properties = new AppProperties();
    private final TerminalRelinker relinker = new TerminalRelinker(costModel(), catalog(),
            properties);
    private final ObstacleIndex obstacleIndex = new ObstacleIndex(List.of());

    @Test
    void movesNodeWithSubtreeToCheaperAttach_whenEnabled() {
        properties.setForestRelinkNodes(true);
        ForestTree tree = trunkAlongDetour();

        List<ForestTree> result = relinker.relink(List.of(tree), Map.of(), Map.of("t1", 10.0),
                obstacleIndex, Map.of());

        ForestTree relinked = result.get(0);
        assertThat(relinked.getNodes()).doesNotContainKey("a");
        assertThat(edge(relinked, "r", "b")).isNotNull();
        assertThat(edge(relinked, "b", "t1")).isNotNull();
    }

    @Test
    void leavesTreeUnchanged_whenDisabled() {
        properties.setForestRelinkNodes(false);
        ForestTree tree = trunkAlongDetour();

        List<ForestTree> result = relinker.relink(List.of(tree), Map.of(), Map.of("t1", 10.0),
                obstacleIndex, Map.of());

        ForestTree relinked = result.get(0);
        assertThat(relinked.getNodes()).containsKey("a");
        assertThat(edge(relinked, "a", "b")).isNotNull();
    }

    /** r(0,0) — a(50,0) — b(0,10) — t1(0,20): узел b дешевле повесить на r. */
    private ForestTree trunkAlongDetour() {
        Map<String, ForestNode> nodes = new LinkedHashMap<>();
        nodes.put("r", node("r", 0, 0, NodeType.CHAMBER));
        nodes.put("a", node("a", 50, 0, NodeType.TECHNICAL_NODE));
        nodes.put("b", node("b", 0, 10, NodeType.CHAMBER));
        nodes.put("t1", node("t1", 0, 20, NodeType.CONNECTION_POINT));
        List<ForestEdge> edges = new ArrayList<>();
        edges.add(edge("e1", "r", "a", new Coordinate(0, 0), new Coordinate(50, 0)));
        edges.add(edge("e2", "a", "b", new Coordinate(50, 0), new Coordinate(0, 10)));
        edges.add(edge("e3", "b", "t1", new Coordinate(0, 10), new Coordinate(0, 20)));
        return ForestTree.builder().tieInNodeId("r").nodes(nodes).edges(edges).build();
    }

    private ForestEdge edge(ForestTree tree, String a, String b) {
        for (ForestEdge edge : tree.getEdges()) {
            if ((edge.getFromNodeId().equals(a) && edge.getToNodeId().equals(b))
                    || (edge.getFromNodeId().equals(b) && edge.getToNodeId().equals(a))) {
                return edge;
            }
        }
        return null;
    }

    private ForestNode node(String id, double x, double y, NodeType type) {
        return ForestNode.builder().id(id).type(type).coordinate(new Coordinate(x, y))
                .existing(false).build();
    }

    private ForestEdge edge(String id, String from, String to, Coordinate... coordinates) {
        return ForestEdge.builder().id(id).fromNodeId(from).toNodeId(to)
                .coordinates(new ArrayList<>(List.of(coordinates))).flowTph(10.0).diameterMm(50)
                .build();
    }

    private CostModel costModel() {
        return new CostModel(catalog(), new CostProperties());
    }

    private DiameterCatalog catalog() {
        HeatingTablesProperties tables = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 100.0, 1000, 100000));
        tables.setDiameters(rows);
        EnvelopeRow envelope = new EnvelopeRow();
        envelope.setDn(50);
        envelope.setPairWidthM(0.2);
        envelope.setHeightM(0.1);
        tables.setEnvelopes(List.of(envelope));
        return new DiameterCatalog(tables);
    }

    private DiameterRow row(int dn, double capacity, double maxLength, long cost) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(maxLength);
        row.setNewCostPerM(cost);
        return row;
    }
}
