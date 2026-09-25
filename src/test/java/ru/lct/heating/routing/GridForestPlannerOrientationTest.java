package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

/**
 * E50: после контракции сквозных узлов рёбра дерева могут быть развёрнуты;
 * {@code orientEdgesFromRoot} приводит ориентацию к «корень → лист», чтобы
 * терминал всегда был конечным ({@code to}) узлом и канонический выход
 * {@code target→point} не терялся.
 */
class GridForestPlannerOrientationTest {

    private static Coordinate c(double x) {
        return new Coordinate(x, 0.0);
    }

    private static ForestNode node(String id, NodeType type, double x) {
        return ForestNode.builder().id(id).type(type).coordinate(c(x)).existing(false).build();
    }

    private static ForestEdge edge(String id, String from, String to, double... xs) {
        List<Coordinate> coords = new ArrayList<>();
        for (double x : xs) {
            coords.add(c(x));
        }
        return ForestEdge.builder().id(id).fromNodeId(from).toNodeId(to)
                .coordinates(coords).flowTph(10.0).diameterMm(100).build();
    }

    private static ForestTree tree(String root, List<ForestNode> nodes, List<ForestEdge> edges) {
        Map<String, ForestNode> map = new LinkedHashMap<>();
        for (ForestNode node : nodes) {
            map.put(node.getId(), node);
        }
        return ForestTree.builder().tieInNodeId(root).nodes(map).edges(edges).build();
    }

    @Test
    void reorientsTerminalFromToTo() {
        ForestTree original = tree("r",
                List.of(node("r", NodeType.CHAMBER, 0.0),
                        node("a", NodeType.CHAMBER, 1.0),
                        node("t", NodeType.CONNECTION_POINT, 3.0)),
                List.of(edge("e1", "r", "a", 0.0, 1.0),
                        // терминал записан первым (`from`) — как после контракции
                        edge("e2", "t", "a", 3.0, 1.0)));

        ForestTree oriented = GridForestPlanner.orientEdgesFromRoot(List.of(original)).get(0);

        ForestEdge terminal = oriented.getEdges().stream()
                .filter(edge -> edge.getFromNodeId().equals("a") || edge.getToNodeId().equals("a"))
                .filter(edge -> edge.getId().equals("e2"))
                .findFirst().orElseThrow();
        assertThat(terminal.getFromNodeId()).isEqualTo("a");
        assertThat(terminal.getToNodeId()).isEqualTo("t");
        assertThat(terminal.getCoordinates().get(0)).isEqualTo(c(1.0));
        assertThat(terminal.getCoordinates().get(terminal.getCoordinates().size() - 1))
                .isEqualTo(c(3.0));
    }

    @Test
    void preservesCanonicalTailDirection() {
        // Ребро терминала записано «point → target → parent»; после ориентации
        // координаты должны идти «parent → target → point».
        ForestTree original = tree("r",
                List.of(node("r", NodeType.CHAMBER, 0.0),
                        node("t", NodeType.CONNECTION_POINT, 3.0)),
                List.of(edge("e1", "t", "r", 3.0, 2.0, 0.0)));

        ForestTree oriented = GridForestPlanner.orientEdgesFromRoot(List.of(original)).get(0);

        ForestEdge terminal = oriented.getEdges().get(0);
        List<Coordinate> coords = terminal.getCoordinates();
        assertThat(terminal.getFromNodeId()).isEqualTo("r");
        assertThat(terminal.getToNodeId()).isEqualTo("t");
        assertThat(coords.get(0)).isEqualTo(c(0.0));
        assertThat(coords.get(coords.size() - 1)).isEqualTo(c(3.0));
    }
}
