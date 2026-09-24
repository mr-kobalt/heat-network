package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

/**
 * FR-30: сквозные degree-2 узлы без смены параметра контрактируются в одно
 * ребро; узлы смены Ду, существующие камеры, корень и терминалы сохраняются.
 */
class GridForestPlannerContractionTest {

    private static Coordinate c(double x) {
        return new Coordinate(x, 0.0);
    }

    private static ForestNode node(String id, NodeType type, double x, boolean existing) {
        return ForestNode.builder().id(id).type(type).coordinate(c(x)).existing(existing).build();
    }

    private static ForestEdge edge(String id, String from, String to, int dn, double... xs) {
        List<Coordinate> coords = new ArrayList<>();
        for (double x : xs) {
            coords.add(c(x));
        }
        return ForestEdge.builder().id(id).fromNodeId(from).toNodeId(to)
                .coordinates(coords).flowTph(10.0).diameterMm(dn).build();
    }

    private static ForestTree tree(String root, List<ForestNode> nodes, List<ForestEdge> edges) {
        Map<String, ForestNode> map = new LinkedHashMap<>();
        for (ForestNode node : nodes) {
            map.put(node.getId(), node);
        }
        return ForestTree.builder().tieInNodeId(root).nodes(map).edges(edges).build();
    }

    @Test
    void contractsChainOfPassThroughNodes() {
        ForestNode root = node("r", NodeType.CHAMBER, 0.0, false);
        ForestNode a = node("a", NodeType.TECHNICAL_NODE, 1.0, false);
        ForestNode b = node("b", NodeType.TECHNICAL_NODE, 2.0, false);
        ForestNode terminal = node("t", NodeType.CONNECTION_POINT, 3.0, false);
        ForestTree contracted = GridForestPlanner.contractTree(tree("r",
                List.of(root, a, b, terminal),
                List.of(edge("e1", "r", "a", 100, 0.0, 1.0),
                        edge("e2", "a", "b", 100, 1.0, 2.0),
                        edge("e3", "b", "t", 100, 2.0, 3.0))));

        assertThat(contracted.getNodes()).containsOnlyKeys("r", "t");
        assertThat(contracted.getEdges()).hasSize(1);
        ForestEdge merged = contracted.getEdges().get(0);
        assertThat(List.of(merged.getFromNodeId(), merged.getToNodeId()))
                .containsExactlyInAnyOrder("r", "t");
        assertThat(merged.getDiameterMm()).isEqualTo(100);
        List<Coordinate> coordinates = merged.getCoordinates();
        List<Coordinate> forward = java.util.Arrays.asList(c(0.0), c(1.0), c(2.0), c(3.0));
        List<Coordinate> reverse = java.util.Arrays.asList(c(3.0), c(2.0), c(1.0), c(0.0));
        assertThat(coordinates.equals(forward) || coordinates.equals(reverse))
                .as("непрерывный путь r↔t независимо от ориентации")
                .isTrue();
    }

    @Test
    void contractsAcrossReverseOrientation() {
        ForestNode root = node("r", NodeType.CHAMBER, 0.0, false);
        ForestNode a = node("a", NodeType.TECHNICAL_NODE, 1.0, false);
        ForestNode terminal = node("t", NodeType.CONNECTION_POINT, 2.0, false);
        ForestTree contracted = GridForestPlanner.contractTree(tree("r",
                List.of(root, a, terminal),
                List.of(edge("e1", "r", "a", 150, 0.0, 1.0),
                        edge("e2", "t", "a", 150, 2.0, 1.0))));

        assertThat(contracted.getNodes()).containsOnlyKeys("r", "t");
        ForestEdge merged = contracted.getEdges().get(0);
        assertThat(List.of(merged.getFromNodeId(), merged.getToNodeId()))
                .containsExactlyInAnyOrder("r", "t");
        List<Coordinate> coordinates = merged.getCoordinates();
        List<Coordinate> forward = java.util.Arrays.asList(c(0.0), c(1.0), c(2.0));
        List<Coordinate> reverse = java.util.Arrays.asList(c(2.0), c(1.0), c(0.0));
        assertThat(coordinates.equals(forward) || coordinates.equals(reverse)).isTrue();
    }

    @Test
    void keepsNodeWithDiameterChange() {
        ForestNode root = node("r", NodeType.CHAMBER, 0.0, false);
        ForestNode a = node("a", NodeType.TECHNICAL_NODE, 1.0, false);
        ForestNode terminal = node("t", NodeType.CONNECTION_POINT, 2.0, false);
        ForestTree result = GridForestPlanner.contractTree(tree("r",
                List.of(root, a, terminal),
                List.of(edge("e1", "r", "a", 100, 0.0, 1.0),
                        edge("e2", "a", "t", 200, 1.0, 2.0))));

        assertThat(result.getNodes()).containsKeys("r", "a", "t");
        assertThat(result.getEdges()).hasSize(2);
    }

    @Test
    void keepsExistingChamberAndRoot() {
        ForestNode root = node("r", NodeType.CHAMBER, 0.0, false);
        ForestNode chamber = node("ch", NodeType.CHAMBER, 1.0, true);
        ForestNode terminal = node("t", NodeType.CONNECTION_POINT, 2.0, false);
        ForestTree result = GridForestPlanner.contractTree(tree("r",
                List.of(root, chamber, terminal),
                List.of(edge("e1", "r", "ch", 100, 0.0, 1.0),
                        edge("e2", "ch", "t", 100, 1.0, 2.0))));

        assertThat(result.getNodes()).containsKeys("r", "ch", "t");
        assertThat(result.getEdges()).hasSize(2);
    }

    @Test
    void keepsConnectionPointOnPath() {
        ForestNode root = node("r", NodeType.CHAMBER, 0.0, false);
        ForestNode point = node("p", NodeType.CONNECTION_POINT, 1.0, false);
        ForestNode terminal = node("t", NodeType.CONNECTION_POINT, 2.0, false);
        ForestTree result = GridForestPlanner.contractTree(tree("r",
                List.of(root, point, terminal),
                List.of(edge("e1", "r", "p", 100, 0.0, 1.0),
                        edge("e2", "p", "t", 100, 1.0, 2.0))));

        assertThat(result.getNodes()).containsKeys("r", "p", "t");
        assertThat(result.getEdges()).hasSize(2);
    }
}
