package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;

/**
 * ADR-0035: переприсоединение терминалов на уровне дерева. Для каждого
 * терминального листа полным перебором ищется более дешёвая точка врезки:
 * существующий узел либо T-врезка в середину ребра (новый узел-камера). Для
 * каждого кандидата дерево пересобирается и оценивается {@code S}; применяется
 * строго лучшее.
 */
public class TerminalRelinker {

    private static final double EPS = 1e-6;


    private final CostModel costModel;
    private final DiameterCatalog diameters;
    private final AppProperties appProperties;

    public TerminalRelinker(CostModel costModel, DiameterCatalog diameters,
                            AppProperties appProperties) {
        this.costModel = costModel;
        this.diameters = diameters;
        this.appProperties = appProperties;
    }

    public List<ForestTree> relink(List<ForestTree> trees, Map<String, ConnectionExit> exits,
                                   Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                                   Map<String, Set<PreparedGeometry>> ownObstacles) {
        List<ForestTree> result = new ArrayList<>();
        int iterations = Math.max(1, appProperties.getForestReattachIterations());
        int idCounter = 0;
        for (ForestTree tree : trees) {
            result.add(relinkTree(tree, exits, terminalFlow, obstacleIndex, ownObstacles,
                    iterations, idCounter));
            idCounter += 1000;
        }
        return result;
    }

    private ForestTree relinkTree(ForestTree tree, Map<String, ConnectionExit> exits,
                                  Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                                  Map<String, Set<PreparedGeometry>> ownObstacles, int iterations,
                                  int idBase) {
        Map<String, ForestNode> nodes = new LinkedHashMap<>(tree.getNodes());
        List<Edge> edges = new ArrayList<>();
        for (ForestEdge edge : tree.getEdges()) {
            edges.add(new Edge(edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                    new ArrayList<>(edge.getCoordinates())));
        }
        for (int iter = 0; iter < iterations; iter++) {
            Rebuild current = rebuild(tree.getTieInNodeId(), nodes, edges, terminalFlow);
            if (current == null) {
                return tree;
            }
            boolean changed = false;
            List<String> terminals = terminalIds(nodes);
            int idCounter = idBase;
            for (String terminal : terminals) {
                Best best = bestFor(terminal, tree.getTieInNodeId(), nodes, edges, current.score,
                        exits, terminalFlow, obstacleIndex, ownObstacles, idCounter);
                if (best != null && best.rebuild.score < current.score - EPS) {
                    nodes = best.nodes;
                    edges = best.edges;
                    current = best.rebuild;
                    changed = true;
                    idCounter += 100;
                } else {
                    idCounter += 100;
                }
            }
            if (!changed) {
                break;
            }
        }
        Rebuild finalState = rebuild(tree.getTieInNodeId(), nodes, edges, terminalFlow);
        return finalState == null ? tree : finalState.tree;
    }

    private List<String> terminalIds(Map<String, ForestNode> nodes) {
        List<String> result = new ArrayList<>();
        for (ForestNode node : nodes.values()) {
            if (node.getType() == NodeType.CONNECTION_POINT) {
                result.add(node.getId());
            }
        }
        return result;
    }

    private Best bestFor(String terminalId, String rootId, Map<String, ForestNode> nodes,
                         List<Edge> edges, double currentScore, Map<String, ConnectionExit> exits,
                         Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                         Map<String, Set<PreparedGeometry>> ownObstacles, int idCounter) {
        ForestNode terminal = nodes.get(terminalId);
        ConnectionExit exit = exits == null ? null : exits.get(terminalId);
        if (terminal == null || exit == null || exit.getTarget() == null) {
            return null;
        }
        Coordinate point = terminal.getCoordinate();
        Coordinate target = exit.getTarget();
        List<Coordinate> tail = exit.hasTail() ? exit.getTail() : List.of();
        Edge current = incident(terminalId, edges);
        if (current == null) {
            return null;
        }
        Set<PreparedGeometry> ignored = ownObstacles == null ? Set.of()
                : ownObstacles.getOrDefault(terminalId, Set.of());
        Best best = null;
        // 1. Существующие узлы.
        for (ForestNode candidate : nodes.values()) {
            if (candidate.getId().equals(terminalId)
                    || candidate.getType() == NodeType.CONNECTION_POINT) {
                continue;
            }
            Coordinate from = candidate.getCoordinate();
            if (!validSegment(from, target, tail, point, nodes, edges, obstacleIndex, ignored,
                    incomingDirection(candidate.getId(), rootId, nodes, edges))) {
                continue;
            }
            List<Edge> candidateEdges = removeEdge(edges, current.id);
            candidateEdges.add(branch(candidate.getId(), terminalId, from, target, point, tail,
                    "rj_b_" + idCounter + "_" + candidate.getId()));
            best = evaluate(best, nodes, candidateEdges, rootId, terminalFlow, currentScore);
        }
        // 2. T-врезки в рёбра.
        for (Edge edge : edges) {
            if (edge.id.equals(current.id)) {
                continue;
            }
            for (Coordinate p : tPoints(edge, target)) {
                if (p.equals2D(edge.coords.get(0))
                        || p.equals2D(edge.coords.get(edge.coords.size() - 1))) {
                    continue;
                }
                Coordinate parentEnd = parentEnd(edge, rootId, nodes, edges);
                Coordinate dirIn = unit(p.x - parentEnd.x, p.y - parentEnd.y);
                if (!validSegment(p, target, tail, point, nodes, edges, obstacleIndex, ignored,
                        dirIn)) {
                    continue;
                }
                String newId = "rj_" + (idCounter++);
                List<Edge> candidateEdges = removeEdge(edges, current.id);
                candidateEdges = removeEdge(candidateEdges, edge.id);
                List<List<Coordinate>> split = splitPolyline(edge.coords, p);
                candidateEdges.add(new Edge(edge.id + "_a", edge.a, newId, split.get(0)));
                candidateEdges.add(new Edge(edge.id + "_b", newId, edge.b, split.get(1)));
                candidateEdges.add(branch(newId, terminalId, p, target, point, tail,
                        "rj_e_" + idCounter + "_" + edge.id));
                Map<String, ForestNode> candidateNodes = new LinkedHashMap<>(nodes);
                candidateNodes.put(newId, ForestNode.builder().id(newId).type(NodeType.CHAMBER)
                        .coordinate(p).existing(false).build());
                best = evaluate(best, candidateNodes, candidateEdges, rootId, terminalFlow,
                        currentScore);
            }
        }
        return best;
    }

    private Best evaluate(Best best, Map<String, ForestNode> nodes, List<Edge> edges, String rootId,
                          Map<String, Double> terminalFlow, double currentScore) {
        Rebuild rebuild = rebuild(rootId, nodes, edges, terminalFlow);
        if (rebuild == null || rebuild.score >= currentScore) {
            return best;
        }
        if (best == null || rebuild.score < best.rebuild.score) {
            return new Best(nodes, edges, rebuild);
        }
        return best;
    }

    private boolean validSegment(Coordinate from, Coordinate target, List<Coordinate> tail,
                                 Coordinate point, Map<String, ForestNode> nodes, List<Edge> edges,
                                 ObstacleIndex obstacleIndex, Set<PreparedGeometry> ignored,
                                 Coordinate incoming) {
        LineString segment = line(from, target);
        if (obstacleIndex != null && obstacleIndex.isInteriorBlocked(segment, ignored)) {
            return false;
        }
        double maxTurn = appProperties.getForestMaxTurnDeg() > 0
                ? appProperties.getForestMaxTurnDeg() : 90.0;
        if (incoming != null) {
            Coordinate out = unit(target.x - from.x, target.y - from.y);
            if (angle(incoming, out) > maxTurn + EPS) {
                return false;
            }
        }
        if (!tail.isEmpty()) {
            Coordinate out = unit(point.x - target.x, point.y - target.y);
            Coordinate in = unit(target.x - from.x, target.y - from.y);
            if (angle(in, out) > maxTurn + EPS) {
                return false;
            }
        }
        for (Edge edge : edges) {
            if (contains(edge.coords, from) || contains(edge.coords, target)) {
                continue;
            }
            if (segment.crosses(line(edge.coords))) {
                return false;
            }
        }
        return true;
    }

    private boolean contains(List<Coordinate> coords, Coordinate point) {
        for (Coordinate coordinate : coords) {
            if (coordinate.equals2D(point)) {
                return true;
            }
        }
        return false;
    }

    private List<Coordinate> tPoints(Edge edge, Coordinate target) {
        List<Coordinate> result = new ArrayList<>();
        List<Coordinate> c = edge.coords;
        for (int i = 1; i < c.size() - 1; i++) {
            result.add(c.get(i));
        }
        for (int i = 0; i < c.size() - 1; i++) {
            Coordinate a = c.get(i);
            Coordinate b = c.get(i + 1);
            result.add(new Coordinate((a.x + b.x) / 2.0, (a.y + b.y) / 2.0));
            Coordinate projection = projection(target, a, b);
            if (projection != null) {
                result.add(projection);
            }
        }
        return result;
    }

    private Coordinate projection(Coordinate p, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double len2 = dx * dx + dy * dy;
        if (len2 < EPS) {
            return null;
        }
        double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
        if (t <= 0.0 || t >= 1.0) {
            return null;
        }
        return new Coordinate(a.x + t * dx, a.y + t * dy);
    }

    private Coordinate parentEnd(Edge edge, String rootId, Map<String, ForestNode> nodes,
                                 List<Edge> edges) {
        Integer depthA = depth(edge.a, rootId, nodes, edges);
        Integer depthB = depth(edge.b, rootId, nodes, edges);
        if (depthA == null || depthB == null) {
            return edge.coords.get(0);
        }
        return depthA <= depthB ? edge.coords.get(0)
                : edge.coords.get(edge.coords.size() - 1);
    }

    private Coordinate incomingDirection(String nodeId, String rootId, Map<String, ForestNode> nodes,
                                         List<Edge> edges) {
        Map<String, String> parent = parentMap(rootId, nodes, edges);
        String p = parent.get(nodeId);
        if (p == null) {
            return null;
        }
        ForestNode node = nodes.get(nodeId);
        ForestNode parentNode = nodes.get(p);
        if (node == null || parentNode == null) {
            return null;
        }
        return unit(node.getCoordinate().x - parentNode.getCoordinate().x,
                node.getCoordinate().y - parentNode.getCoordinate().y);
    }

    private Map<String, String> parentMap(String rootId, Map<String, ForestNode> nodes,
                                          List<Edge> edges) {
        Map<String, List<String>> adjacency = adjacency(nodes, edges);
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        if (!nodes.containsKey(rootId)) {
            return parent;
        }
        visited.add(rootId);
        queue.add(rootId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (visited.add(next)) {
                    parent.put(next, current);
                    queue.add(next);
                }
            }
        }
        return parent;
    }

    private Integer depth(String nodeId, String rootId, Map<String, ForestNode> nodes,
                          List<Edge> edges) {
        Map<String, String> parent = parentMap(rootId, nodes, edges);
        if (!nodes.containsKey(nodeId)) {
            return null;
        }
        int depth = 0;
        String current = nodeId;
        int guard = nodes.size() + 1;
        while (parent.containsKey(current) && guard-- > 0) {
            current = parent.get(current);
            depth++;
        }
        return depth;
    }

    private Edge incident(String nodeId, List<Edge> edges) {
        Edge found = null;
        for (Edge edge : edges) {
            if (edge.a.equals(nodeId) || edge.b.equals(nodeId)) {
                if (found != null) {
                    return null;
                }
                found = edge;
            }
        }
        return found;
    }

    private List<Edge> removeEdge(List<Edge> edges, String id) {
        List<Edge> result = new ArrayList<>();
        for (Edge edge : edges) {
            if (!edge.id.equals(id)) {
                result.add(edge);
            }
        }
        return result;
    }

    private Edge branch(String fromId, String toId, Coordinate from, Coordinate target,
                        Coordinate point, List<Coordinate> tail, String id) {
        List<Coordinate> coords = new ArrayList<>();
        coords.add(from);
        coords.add(target);
        if (!target.equals2D(point)) {
            coords.add(point);
        }
        return new Edge(id, fromId, toId, coords);
    }

    private List<List<Coordinate>> splitPolyline(List<Coordinate> coords, Coordinate p) {
        int index = -1;
        for (int i = 0; i < coords.size(); i++) {
            if (coords.get(i).equals2D(p)) {
                index = i;
                break;
            }
        }
        List<Coordinate> left = new ArrayList<>();
        List<Coordinate> right = new ArrayList<>();
        if (index >= 0) {
            left.addAll(coords.subList(0, index + 1));
            right.addAll(coords.subList(index, coords.size()));
        } else {
            int segment = 0;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < coords.size() - 1; i++) {
                double d = p.distance(coords.get(i)) + p.distance(coords.get(i + 1));
                if (d < best) {
                    best = d;
                    segment = i;
                }
            }
            left.addAll(coords.subList(0, segment + 1));
            left.add(p);
            right.add(p);
            right.addAll(coords.subList(segment + 1, coords.size()));
        }
        return List.of(left, right);
    }

    private Map<String, List<String>> adjacency(Map<String, ForestNode> nodes, List<Edge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String id : nodes.keySet()) {
            adjacency.put(id, new ArrayList<>());
        }
        for (Edge edge : edges) {
            adjacency.computeIfAbsent(edge.a, key -> new ArrayList<>()).add(edge.b);
            adjacency.computeIfAbsent(edge.b, key -> new ArrayList<>()).add(edge.a);
        }
        return adjacency;
    }

    private Rebuild rebuild(String rootId, Map<String, ForestNode> nodes, List<Edge> edges,
                            Map<String, Double> terminalFlow) {
        if (!nodes.containsKey(rootId)) {
            return null;
        }
        Map<String, List<Integer>> incident = new HashMap<>();
        for (String id : nodes.keySet()) {
            incident.put(id, new ArrayList<>());
        }
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            if (!incident.containsKey(edge.a) || !incident.containsKey(edge.b)) {
                return null;
            }
            incident.get(edge.a).add(i);
            incident.get(edge.b).add(i);
        }
        if (edges.size() != nodes.size() - 1) {
            return null;
        }
        // Ориентация от корня.
        Map<String, String> parent = new HashMap<>();
        Map<String, Integer> parentEdge = new HashMap<>();
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        visited.add(rootId);
        queue.add(rootId);
        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            order.add(current);
            for (int index : incident.get(current)) {
                Edge edge = edges.get(index);
                String next = edge.a.equals(current) ? edge.b : edge.a;
                if (visited.add(next)) {
                    parent.put(next, current);
                    parentEdge.put(next, index);
                    queue.add(next);
                }
            }
        }
        if (visited.size() != nodes.size()) {
            return null;
        }
        // Потоки: снизу вверх.
        Map<String, Double> subtree = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String node = order.get(i);
            double flow = terminalFlow.getOrDefault(node, 0.0);
            for (int index : incident.get(node)) {
                Edge edge = edges.get(index);
                String child = edge.a.equals(node) ? edge.b : edge.a;
                if (parent.get(child) != null && parent.get(child).equals(node)) {
                    flow += subtree.getOrDefault(child, 0.0);
                }
            }
            subtree.put(node, flow);
        }
        long cost = 0L;
        double length = 0.0;
        Map<String, Integer> maxIncidentDn = new HashMap<>();
        List<ForestEdge> rebuiltEdges = new ArrayList<>();
        for (Edge edge : edges) {
            String child = parent.get(edge.b) != null && parent.get(edge.b).equals(edge.a)
                    ? edge.b : edge.a;
            double flow = subtree.getOrDefault(child, 0.0);
            int dn = diameter(flow);
            for (String node : List.of(edge.a, edge.b)) {
                maxIncidentDn.merge(node, dn, Math::max);
            }
            double edgeLength = edge.length();
            cost += costModel.segmentCost(edgeLength, dn, 1.0, 1.0);
            length += edgeLength;
            rebuiltEdges.add(ForestEdge.builder().id(edge.id).fromNodeId(edge.a)
                    .toNodeId(edge.b).coordinates(edge.coords).flowTph(flow).diameterMm(dn)
                    .build());
        }
        Map<String, ForestNode> rebuiltNodes = new HashMap<>();
        for (ForestNode node : nodes.values()) {
            int degree = incident.get(node.getId()).size();
            boolean isTerminal = node.getType() == NodeType.CONNECTION_POINT;
            NodeType type;
            if (isTerminal) {
                type = NodeType.CONNECTION_POINT;
            } else if (node.getId().equals(rootId) || degree >= 3) {
                type = NodeType.CHAMBER;
            } else {
                type = NodeType.TECHNICAL_NODE;
            }
            boolean chamber = type == NodeType.CHAMBER;
            if (chamber) {
                if (node.isExisting()) {
                    cost += (long) degree * costModel.existingChamberTieInCost();
                } else {
                    cost += costModel.chamberCost(maxIncidentDn.getOrDefault(node.getId(), 0));
                }
            }
            rebuiltNodes.put(node.getId(), ForestNode.builder().id(node.getId()).type(type)
                    .coordinate(node.getCoordinate()).existing(node.isExisting())
                    .existingObjectId(node.getExistingObjectId()).build());
        }
        ForestTree tree = ForestTree.builder().tieInNodeId(rootId).nodes(rebuiltNodes)
                .edges(rebuiltEdges).build();
        return new Rebuild(costModel.score(cost, length), tree);
    }

    private int diameter(double flow) {
        try {
            return diameters.select(flow).getDn();
        } catch (IllegalArgumentException overflow) {
            List<DiameterRow> rows = diameters.rows();
            return rows.get(rows.size() - 1).getDn();
        }
    }

    private LineString line(Coordinate a, Coordinate b) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(new Coordinate[]{a, b});
    }

    private LineString line(List<Coordinate> coords) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(coords.toArray(new Coordinate[0]));
    }

    private Coordinate unit(double x, double y) {
        double len = Math.hypot(x, y);
        return len < EPS ? new Coordinate(0, 0) : new Coordinate(x / len, y / len);
    }

    private double angle(Coordinate a, Coordinate b) {
        double dot = a.x * b.x + a.y * b.y;
        double cross = a.x * b.y - a.y * b.x;
        return Math.toDegrees(Math.atan2(Math.abs(cross), dot));
    }

    private static final class Edge {
        private final String id;
        private final String a;
        private final String b;
        private final List<Coordinate> coords;

        private Edge(String id, String a, String b, List<Coordinate> coords) {
            this.id = id;
            this.a = a;
            this.b = b;
            this.coords = coords;
        }

        private double length() {
            double total = 0.0;
            for (int i = 1; i < coords.size(); i++) {
                total += coords.get(i - 1).distance(coords.get(i));
            }
            return total;
        }
    }

    private static final class Rebuild {
        private final double score;
        private final ForestTree tree;

        private Rebuild(double score, ForestTree tree) {
            this.score = score;
            this.tree = tree;
        }
    }

    private static final class Best {
        private final Map<String, ForestNode> nodes;
        private final List<Edge> edges;
        private final Rebuild rebuild;

        private Best(Map<String, ForestNode> nodes, List<Edge> edges, Rebuild rebuild) {
            this.nodes = nodes;
            this.edges = edges;
            this.rebuild = rebuild;
        }
    }
}
