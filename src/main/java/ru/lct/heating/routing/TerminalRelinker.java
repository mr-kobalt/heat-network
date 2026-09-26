package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.index.strtree.ItemDistance;
import org.locationtech.jts.index.strtree.STRtree;
import org.locationtech.jts.operation.distance.DistanceOp;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.graph.ExistingNetworkGraph;
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
    private final ExistingNetworkGraph graph;

    public TerminalRelinker(CostModel costModel, DiameterCatalog diameters,
                            AppProperties appProperties) {
        this(costModel, diameters, appProperties, null);
    }

    public TerminalRelinker(CostModel costModel, DiameterCatalog diameters,
                            AppProperties appProperties, ExistingNetworkGraph graph) {
        this.costModel = costModel;
        this.diameters = diameters;
        this.appProperties = appProperties;
        this.graph = graph;
    }

    public List<ForestTree> relink(List<ForestTree> trees, Map<String, List<ConnectionExit>> exits,
                                   Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                                   Map<String, Set<PreparedGeometry>> ownObstacles) {
        return relink(trees, exits, terminalFlow, obstacleIndex, ownObstacles, new RelinkStats());
    }

    /**
     * @param stats коллектор диагностики (R3): счётчики и тайминги relink.
     */
    public List<ForestTree> relink(List<ForestTree> trees, Map<String, List<ConnectionExit>> exits,
                                   Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                                   Map<String, Set<PreparedGeometry>> ownObstacles,
                                   RelinkStats stats) {
        long start = System.nanoTime();
        int iterations = Math.max(1, appProperties.getForestReattachIterations());
        int count = trees.size();
        ForestTree[] result = new ForestTree[count];
        // Деревья переприсоединяются независимо: распараллеливаем с сохранением
        // порядка (детерминированный idBase по индексу дерева).
        java.util.stream.IntStream.range(0, count).parallel().forEach(index ->
                result[index] = relinkTree(trees.get(index), exits, terminalFlow, obstacleIndex,
                        ownObstacles, iterations, index * 10_000_000, stats));
        stats.addTotal(System.nanoTime() - start);
        return new ArrayList<>(Arrays.asList(result));
    }

    private ForestTree relinkTree(ForestTree tree, Map<String, List<ConnectionExit>> exits,
                                  Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                                  Map<String, Set<PreparedGeometry>> ownObstacles, int iterations,
                                  int idBase, RelinkStats stats) {
        Map<String, ForestNode> nodes = new LinkedHashMap<>(tree.getNodes());
        List<Edge> edges = new ArrayList<>();
        for (ForestEdge edge : tree.getEdges()) {
            edges.add(new Edge(edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                    new ArrayList<>(edge.getCoordinates())));
        }
        int candidateK = appProperties.getForestRelinkCandidateK();
        double candidateRadius = appProperties.getForestRelinkCandidateRadiusM();
        int idCounter = idBase;
        for (int iter = 0; iter < iterations; iter++) {
            Rebuild current = rebuild(tree.getTieInNodeId(), nodes, edges, terminalFlow);
            if (current == null) {
                return tree;
            }
            boolean changed = false;
            // R7: геометрии рёбер пересчитываются один раз на итерацию и
            // переиспользуются всеми кандидатами (bestFor/bestForNode), а не
            // пересоздаются на каждый узел/терминал. Инвалидируются только при
            // принятии хода (edges меняются).
            LineString[] edgeLines = new LineString[edges.size()];
            Envelope[] edgeEnvelopes = new Envelope[edges.size()];
            edgeGeometries(edges, edgeLines, edgeEnvelopes);
            // R3: пространственный индекс кандидатов (kNN) строится на итерацию.
            CandidateIndex index = buildIndex(nodes, edges, edgeLines, edgeEnvelopes,
                    candidateK, candidateRadius, stats);
            List<String> movables = new ArrayList<>(nodes.keySet());
            for (String nodeId : movables) {
                if (nodeId.equals(tree.getTieInNodeId())) {
                    idCounter += 100;
                    continue;
                }
                ForestNode node = nodes.get(nodeId);
                Best best;
                if (node.getType() == NodeType.CONNECTION_POINT) {
                    best = bestFor(nodeId, tree.getTieInNodeId(), nodes, edges, current.score,
                            exits, terminalFlow, obstacleIndex, ownObstacles, idCounter,
                            edgeLines, edgeEnvelopes, index, stats);
                } else if (appProperties.isForestRelinkNodes()) {
                    best = bestForNode(nodeId, tree.getTieInNodeId(), nodes, edges, current.score,
                            terminalFlow, obstacleIndex, idCounter, edgeLines, edgeEnvelopes,
                            index, stats);
                } else {
                    best = null;
                }
                if (best != null && best.rebuild.score < current.score - EPS) {
                    nodes = best.nodes;
                    edges = best.edges;
                    current = best.rebuild;
                    changed = true;
                    stats.addMoveAccepted();
                    idCounter += 100;
                    edgeLines = new LineString[edges.size()];
                    edgeEnvelopes = new Envelope[edges.size()];
                    edgeGeometries(edges, edgeLines, edgeEnvelopes);
                    index = buildIndex(nodes, edges, edgeLines, edgeEnvelopes,
                            candidateK, candidateRadius, stats);
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

    /** R3: индекс строится только если включён kNN (k > 0). */
    private CandidateIndex buildIndex(Map<String, ForestNode> nodes, List<Edge> edges,
                                      LineString[] edgeLines, Envelope[] edgeEnvelopes,
                                      int candidateK, double candidateRadius, RelinkStats stats) {
        if (candidateK <= 0) {
            return null;
        }
        long start = System.nanoTime();
        CandidateIndex index = new CandidateIndex(nodes, edges, edgeLines, edgeEnvelopes);
        stats.addIndexBuild(System.nanoTime() - start);
        return index;
    }

    private Best bestFor(String terminalId, String rootId, Map<String, ForestNode> nodes,
                         List<Edge> edges, double currentScore,
                         Map<String, List<ConnectionExit>> exits,
                         Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                         Map<String, Set<PreparedGeometry>> ownObstacles, int idCounter,
                         LineString[] edgeLines, Envelope[] edgeEnvelopes,
                         CandidateIndex index, RelinkStats stats) {
        ForestNode terminal = nodes.get(terminalId);
        List<ConnectionExit> candidates = exits == null ? null : exits.get(terminalId);
        if (terminal == null || candidates == null || candidates.isEmpty()) {
            return null;
        }
        Coordinate point = terminal.getCoordinate();
        Edge current = incident(terminalId, edges);
        if (current == null) {
            return null;
        }
        // E50: переприсоединяется ствол `кандидат→target`; свой ОКС не
        // игнорируется (канонический хвост `target→point` добавляется отдельно).
        Set<PreparedGeometry> ignored = Set.of();
        Best best = null;
        // ADR-0039: перебираем все выходы-кандидаты точки, а не только канонический.
        for (ConnectionExit exit : candidates) {
            if (exit == null || exit.isBlocked() || exit.getTarget() == null) {
                continue;
            }
            Coordinate target = exit.getTarget();
            List<Coordinate> tail = exit.hasTail() ? exit.getTail() : List.of();
            // R3: kNN-отбор ближайших узлов/рёбер + опциональный радиус.
            List<ForestNode> nodeCandidates = candidateNodes(nodes, index, target);
            List<Integer> edgeCandidates = candidateEdges(edges, index, target);
            double radius = appProperties.getForestRelinkCandidateRadiusM();
            if (radius > 0) {
                nodeCandidates.removeIf(n -> n.getCoordinate() == null
                        || n.getCoordinate().distance(target) > radius);
                Envelope targetEnvelope = new Envelope(target);
                edgeCandidates.removeIf(i -> edgeEnvelopes[i].distance(targetEnvelope) > radius);
            }
            stats.addCandidateNodes(nodeCandidates.size());
            stats.addCandidateEdges(edgeCandidates.size());
            // 1. Существующие узлы.
            for (ForestNode candidate : nodeCandidates) {
                if (candidate.getId().equals(terminalId)
                        || candidate.getType() == NodeType.CONNECTION_POINT) {
                    continue;
                }
                Coordinate from = candidate.getCoordinate();
                long segmentStart = System.nanoTime();
                boolean valid = validSegment(from, target, tail, point, edges, edgeLines,
                        edgeEnvelopes, obstacleIndex, ignored);
                stats.addValidSegment(System.nanoTime() - segmentStart);
                if (!valid) {
                    continue;
                }
                List<Edge> candidateEdges = removeEdge(edges, current.id);
                candidateEdges.add(branch(candidate.getId(), terminalId, from, target, point, tail,
                        "rj_b_" + idCounter + "_" + candidate.getId()));
                best = evaluate(best, nodes, candidateEdges, rootId, terminalFlow, currentScore,
                        stats);
            }
            // 2. T-врезки в рёбра.
            for (int edgeIndex : edgeCandidates) {
                Edge edge = edges.get(edgeIndex);
                if (edge.id.equals(current.id)) {
                    continue;
                }
                List<Coordinate> tps = tPoints(edge, target);
                stats.addTpoints(tps.size());
                for (Coordinate p : tps) {
                    if (p.equals2D(edge.coords.get(0))
                            || p.equals2D(edge.coords.get(edge.coords.size() - 1))) {
                        continue;
                    }
                    long segmentStart = System.nanoTime();
                    boolean valid = validSegment(p, target, tail, point, edges, edgeLines,
                            edgeEnvelopes, obstacleIndex, ignored);
                    stats.addValidSegment(System.nanoTime() - segmentStart);
                    if (!valid) {
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
                            currentScore, stats);
                }
            }
        }
        return best;
    }

    /** R3: k ближайших узлов (или все, если kNN выключен). */
    private List<ForestNode> candidateNodes(Map<String, ForestNode> nodes, CandidateIndex index,
                                            Coordinate target) {
        int k = appProperties.getForestRelinkCandidateK();
        if (index != null && k > 0) {
            return index.nearestNodes(target, k);
        }
        return new ArrayList<>(nodes.values());
    }

    /** R3: индексы k ближайших рёбер (или все, если kNN выключен). */
    private List<Integer> candidateEdges(List<Edge> edges, CandidateIndex index, Coordinate target) {
        int k = appProperties.getForestRelinkCandidateK();
        if (index != null && k > 0) {
            return index.nearestEdges(target, k);
        }
        List<Integer> all = new ArrayList<>(edges.size());
        for (int i = 0; i < edges.size(); i++) {
            all.add(i);
        }
        return all;
    }

    private Best evaluate(Best best, Map<String, ForestNode> nodes, List<Edge> edges, String rootId,
                          Map<String, Double> terminalFlow, double currentScore, RelinkStats stats) {
        if (!degreeWithinLimit(edges, nodes)) {
            return best;
        }
        // R2 (флаг forest-relink-cost-bound): порог = лучшая найденная оценка
        // (или текущая), досрочное прерывание безопасно — оценка монотонна.
        double abort = appProperties.isForestRelinkCostBound()
                ? (best != null ? best.rebuild.score : currentScore)
                : Double.POSITIVE_INFINITY;
        long rebuildStart = System.nanoTime();
        Rebuild rebuild = rebuild(rootId, nodes, edges, terminalFlow, abort);
        stats.addRebuild(System.nanoTime() - rebuildStart);
        if (rebuild == null || rebuild.score >= currentScore) {
            return best;
        }
        if (best == null || rebuild.score < best.rebuild.score) {
            return new Best(nodes, edges, rebuild);
        }
        return best;
    }

    /**
     * FR-26: степень узла не выше {@code forest-max-chamber-degree}. Для
     * существующих камер (E26-02) добавляются их существующие примыкания
     * (проходная линия = 2).
     */
    private boolean degreeWithinLimit(List<Edge> edges, Map<String, ForestNode> nodes) {
        int max = appProperties.getForestMaxChamberDegree();
        if (max <= 0) {
            return true;
        }
        Map<String, Integer> degree = new HashMap<>();
        for (Edge edge : edges) {
            degree.merge(edge.a, 1, Integer::sum);
            degree.merge(edge.b, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : degree.entrySet()) {
            int total = entry.getValue();
            ForestNode node = nodes == null ? null : nodes.get(entry.getKey());
            if (node != null && node.isExisting() && graph != null
                    && appProperties.isForestChamberTieInRules()) {
                total += graph.chamberAttachments(entry.getKey());
            }
            if (total > max) {
                return false;
            }
        }
        return true;
    }

    /**
     * ADR-0044: перенос промежуточного узла {@code nodeId} вместе с поддеревом
     * к более дешёвому месту (существующий узел или T-врезка) в радиусе
     * {@code forest-relink-nodes-radius-m}. Углы маршрута чинит refine.
     */
    private Best bestForNode(String nodeId, String rootId, Map<String, ForestNode> nodes,
                             List<Edge> edges, double currentScore,
                             Map<String, Double> terminalFlow, ObstacleIndex obstacleIndex,
                             int idCounter, LineString[] edgeLines, Envelope[] edgeEnvelopes,
                             CandidateIndex index, RelinkStats stats) {
        ForestNode node = nodes.get(nodeId);
        if (node == null || nodeId.equals(rootId)) {
            return null;
        }
        Map<String, String> parent = parentMap(rootId, edges);
        String parentId = parent.get(nodeId);
        if (parentId == null) {
            return null;
        }
        Edge parentEdge = edgeBetween(nodeId, parentId, edges);
        if (parentEdge == null) {
            return null;
        }
        Set<String> subtree = subtreeNodes(nodeId, parentEdge.id, edges);
        Coordinate vertex = node.getCoordinate();
        double radius = appProperties.getForestRelinkNodesRadiusM();
        Best best = null;
        // 1. Существующие узлы.
        List<ForestNode> nodeCandidates = candidateNodes(nodes, index, vertex);
        stats.addCandidateNodes(nodeCandidates.size());
        for (ForestNode candidate : nodeCandidates) {
            if (candidate.getId().equals(nodeId)
                    || candidate.getType() == NodeType.CONNECTION_POINT
                    || subtree.contains(candidate.getId())) {
                continue;
            }
            Coordinate from = candidate.getCoordinate();
            if (radius > 0 && from.distance(vertex) > radius) {
                continue;
            }
            long segmentStart = System.nanoTime();
            boolean valid = validSegment(from, vertex, List.of(), vertex, edges, edgeLines,
                    edgeEnvelopes, obstacleIndex, Set.of());
            stats.addValidSegment(System.nanoTime() - segmentStart);
            if (!valid) {
                continue;
            }
            List<Edge> candidateEdges = removeEdge(edges, parentEdge.id);
            candidateEdges.add(branch(candidate.getId(), nodeId, from, vertex, vertex, List.of(),
                    "rj_b_" + idCounter + "_" + candidate.getId()));
            best = evaluate(best, nodes, candidateEdges, rootId, terminalFlow, currentScore,
                    stats);
        }
        // 2. T-врезки в рёбра вне поддерева.
        List<Integer> edgeCandidates = candidateEdges(edges, index, vertex);
        stats.addCandidateEdges(edgeCandidates.size());
        for (int edgeIndex : edgeCandidates) {
            Edge edge = edges.get(edgeIndex);
            if (edge.id.equals(parentEdge.id)) {
                continue;
            }
            if (subtree.contains(edge.a) && subtree.contains(edge.b)) {
                continue;
            }
            if (radius > 0 && edge.distanceTo(vertex) > radius) {
                continue;
            }
            List<Coordinate> tps = tPoints(edge, vertex);
            stats.addTpoints(tps.size());
            for (Coordinate p : tps) {
                if (p.equals2D(edge.coords.get(0))
                        || p.equals2D(edge.coords.get(edge.coords.size() - 1))) {
                    continue;
                }
                if (radius > 0 && p.distance(vertex) > radius) {
                    continue;
                }
                long segmentStart = System.nanoTime();
                boolean valid = validSegment(p, vertex, List.of(), vertex, edges, edgeLines,
                        edgeEnvelopes, obstacleIndex, Set.of());
                stats.addValidSegment(System.nanoTime() - segmentStart);
                if (!valid) {
                    continue;
                }
                String newId = "rj_" + (idCounter++);
                List<Edge> candidateEdges = removeEdge(edges, parentEdge.id);
                candidateEdges = removeEdge(candidateEdges, edge.id);
                List<List<Coordinate>> split = splitPolyline(edge.coords, p);
                candidateEdges.add(new Edge(edge.id + "_a", edge.a, newId, split.get(0)));
                candidateEdges.add(new Edge(edge.id + "_b", newId, edge.b, split.get(1)));
                candidateEdges.add(branch(newId, nodeId, p, vertex, vertex, List.of(),
                        "rj_e_" + idCounter + "_" + edge.id));
                Map<String, ForestNode> candidateNodes = new LinkedHashMap<>(nodes);
                candidateNodes.put(newId, ForestNode.builder().id(newId).type(NodeType.CHAMBER)
                        .coordinate(p).existing(false).build());
                best = evaluate(best, candidateNodes, candidateEdges, rootId, terminalFlow,
                        currentScore, stats);
            }
        }
        return best;
    }

    private Map<String, String> parentMap(String rootId, List<Edge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            adjacency.computeIfAbsent(edge.a, key -> new ArrayList<>()).add(edge.b);
            adjacency.computeIfAbsent(edge.b, key -> new ArrayList<>()).add(edge.a);
        }
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        if (!adjacency.containsKey(rootId)) {
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

    private Edge edgeBetween(String first, String second, List<Edge> edges) {
        for (Edge edge : edges) {
            if ((edge.a.equals(first) && edge.b.equals(second))
                    || (edge.a.equals(second) && edge.b.equals(first))) {
                return edge;
            }
        }
        return null;
    }

    /** Узлы, достижимые от {@code root} без прохода по {@code parentEdgeId} (сам root и его поддерево). */
    private Set<String> subtreeNodes(String root, String parentEdgeId, List<Edge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (Edge edge : edges) {
            if (edge.id.equals(parentEdgeId)) {
                continue;
            }
            adjacency.computeIfAbsent(edge.a, key -> new ArrayList<>()).add(edge.b);
            adjacency.computeIfAbsent(edge.b, key -> new ArrayList<>()).add(edge.a);
        }
        Set<String> subtree = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        subtree.add(root);
        queue.add(root);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (subtree.add(next)) {
                    queue.add(next);
                }
            }
        }
        return subtree;
    }

    /**
     * ADR-0043: relink решает топологию — углы поворота маршрута проверяет
     * refine. Исключение — стык финального вывода {@code target→point}: хвост
     * задан резолвером и refine его не меняет, поэтому угол на стыке проверяется
     * здесь.
     */
    private boolean validSegment(Coordinate from, Coordinate target, List<Coordinate> tail,
                                 Coordinate point, List<Edge> edges, LineString[] edgeLines,
                                 Envelope[] edgeEnvelopes,
                                 ObstacleIndex obstacleIndex, Set<PreparedGeometry> ignored) {
        LineString segment = line(from, target);
        if (obstacleIndex != null && obstacleIndex.isInteriorBlocked(segment, ignored)) {
            return false;
        }
        if (!tail.isEmpty()) {
            double maxTurn = appProperties.getForestMaxTurnDeg() > 0
                    ? appProperties.getForestMaxTurnDeg() : 90.0;
            Coordinate in = new Coordinate(target.x - from.x, target.y - from.y);
            Coordinate out = new Coordinate(point.x - target.x, point.y - target.y);
            double dot = in.x * out.x + in.y * out.y;
            double cross = in.x * out.y - in.y * out.x;
            if (Math.toDegrees(Math.atan2(Math.abs(cross), dot)) > maxTurn + EPS) {
                return false;
            }
        }
        Envelope segmentEnvelope = segment.getEnvelopeInternal();
        for (int i = 0; i < edges.size(); i++) {
            Edge edge = edges.get(i);
            if (contains(edge.coords, from) || contains(edge.coords, target)) {
                continue;
            }
            if (!segmentEnvelope.intersects(edgeEnvelopes[i])) {
                continue;
            }
            if (segment.crosses(edgeLines[i])) {
                return false;
            }
        }
        return true;
    }

    /** Линии и envelope рёбер для дешёвого отсева по габаритам при проверке. */
    private void edgeGeometries(List<Edge> edges, LineString[] lines, Envelope[] envelopes) {
        for (int i = 0; i < edges.size(); i++) {
            lines[i] = line(edges.get(i).coords);
            envelopes[i] = lines[i].getEnvelopeInternal();
        }
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
        List<Coordinate> projections = new ArrayList<>();
        for (int i = 0; i < c.size() - 1; i++) {
            Coordinate a = c.get(i);
            Coordinate b = c.get(i + 1);
            result.add(new Coordinate((a.x + b.x) / 2.0, (a.y + b.y) / 2.0));
            Coordinate projection = projection(target, a, b);
            if (projection != null) {
                result.add(projection);
                projections.add(projection);
            }
        }
        int max = appProperties.getForestRelinkTpointMax();
        if (max <= 0 || result.size() <= max) {
            return result;
        }
        // R1: прореживаем список, сохраняя порядок; проекции target обязательны
        // (кратчайшая T-врезка), поэтому добавляем их поверх.
        List<Coordinate> thinned = new ArrayList<>(max + projections.size());
        int step = (int) Math.ceil(result.size() / (double) max);
        for (int i = 0; i < result.size(); i += step) {
            thinned.add(result.get(i));
        }
        for (Coordinate projection : projections) {
            if (!thinned.contains(projection)) {
                thinned.add(projection);
            }
        }
        return thinned;
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

    private Rebuild rebuild(String rootId, Map<String, ForestNode> nodes, List<Edge> edges,
                            Map<String, Double> terminalFlow) {
        return rebuild(rootId, nodes, edges, terminalFlow, Double.POSITIVE_INFINITY);
    }

    /**
     * R2: помимо полной пересборки умеет досрочно прерваться, как только
     * частичная оценка (стоимость+длина уже обработанных рёбер) достигает
     * {@code abortScore}. Поскольку стоимость и длина только растут, итоговая
     * оценка не может стать меньше частичной — ход гарантированно не лучше
     * порога, и его можно не досчитывать. Оптимум не меняется.
     */
    private Rebuild rebuild(String rootId, Map<String, ForestNode> nodes, List<Edge> edges,
                            Map<String, Double> terminalFlow, double abortScore) {
        if (!nodes.containsKey(rootId)) {
            return null;
        }
        // Bugfix: удаляем тупиковые листья, оставшиеся после переприсоединения
        // (узел степени < 2, кроме корня и точек подключения), — иначе ребро
        // «висит в воздухе». Работаем на копиях, входные списки не мутируем.
        Map<String, ForestNode> keptNodes = new LinkedHashMap<>(nodes);
        List<Edge> keptEdges = new ArrayList<>(edges);
        boolean pruned = true;
        while (pruned) {
            pruned = false;
            Map<String, Integer> degree = new HashMap<>();
            for (Edge edge : keptEdges) {
                degree.merge(edge.a, 1, Integer::sum);
                degree.merge(edge.b, 1, Integer::sum);
            }
            for (String id : new ArrayList<>(keptNodes.keySet())) {
                if (id.equals(rootId)) {
                    continue;
                }
                ForestNode node = keptNodes.get(id);
                if (node.getType() == NodeType.CONNECTION_POINT) {
                    continue;
                }
                if (degree.getOrDefault(id, 0) <= 1) {
                    keptNodes.remove(id);
                    keptEdges.removeIf(edge -> edge.a.equals(id) || edge.b.equals(id));
                    pruned = true;
                }
            }
        }
        Map<String, List<Integer>> incident = new HashMap<>();
        for (String id : keptNodes.keySet()) {
            incident.put(id, new ArrayList<>());
        }
        for (int i = 0; i < keptEdges.size(); i++) {
            Edge edge = keptEdges.get(i);
            if (!incident.containsKey(edge.a) || !incident.containsKey(edge.b)) {
                return null;
            }
            incident.get(edge.a).add(i);
            incident.get(edge.b).add(i);
        }
        if (keptEdges.size() != keptNodes.size() - 1) {
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
                Edge edge = keptEdges.get(index);
                String next = edge.a.equals(current) ? edge.b : edge.a;
                if (visited.add(next)) {
                    parent.put(next, current);
                    parentEdge.put(next, index);
                    queue.add(next);
                }
            }
        }
        if (visited.size() != keptNodes.size()) {
            return null;
        }
        // Потоки: снизу вверх.
        Map<String, Double> subtree = new HashMap<>();
        for (int i = order.size() - 1; i >= 0; i--) {
            String node = order.get(i);
            double flow = terminalFlow.getOrDefault(node, 0.0);
            for (int index : incident.get(node)) {
                Edge edge = keptEdges.get(index);
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
        for (Edge edge : keptEdges) {
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
            if (costModel.score(cost, length) >= abortScore) {
                return null;
            }
            rebuiltEdges.add(ForestEdge.builder().id(edge.id).fromNodeId(edge.a)
                    .toNodeId(edge.b).coordinates(edge.coords).flowTph(flow).diameterMm(dn)
                    .build());
        }
        Map<String, ForestNode> rebuiltNodes = new HashMap<>();
        for (ForestNode node : keptNodes.values()) {
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

        private double distanceTo(Coordinate point) {
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < coords.size() - 1; i++) {
                best = Math.min(best, segmentDistance(point, coords.get(i), coords.get(i + 1)));
            }
            return best;
        }

        private double segmentDistance(Coordinate p, Coordinate a, Coordinate b) {
            double dx = b.x - a.x;
            double dy = b.y - a.y;
            double len2 = dx * dx + dy * dy;
            if (len2 < EPS) {
                return p.distance(a);
            }
            double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len2;
            t = Math.max(0.0, Math.min(1.0, t));
            return p.distance(new Coordinate(a.x + t * dx, a.y + t * dy));
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

    /**
     * R3: пространственный индекс узлов и рёбер дерева для kNN-отбора
     * кандидатов (JTS {@link STRtree}). Строится на итерацию relink и
     * инвалидируется при принятом ходе. Рассчитан на масштаб: поиск
     * k ближайших — {@code O(log + k)} вместо перебора всех узлов/рёбер.
     */
    private static final class CandidateIndex {
        private final STRtree nodeTree = new STRtree();
        private final STRtree edgeTree = new STRtree();
        private final LineString[] edgeLines;

        private CandidateIndex(Map<String, ForestNode> nodes, List<Edge> edges,
                               LineString[] edgeLines, Envelope[] edgeEnvelopes) {
            this.edgeLines = edgeLines;
            for (ForestNode node : nodes.values()) {
                Coordinate coordinate = node.getCoordinate();
                if (coordinate != null) {
                    nodeTree.insert(new Envelope(coordinate), node);
                }
            }
            nodeTree.build();
            for (int i = 0; i < edges.size(); i++) {
                edgeTree.insert(edgeEnvelopes[i], Integer.valueOf(i));
            }
            edgeTree.build();
        }

        private List<ForestNode> nearestNodes(Coordinate target, int k) {
            Point query = GeometrySupport.GEOMETRY_FACTORY.createPoint(target);
            ItemDistance distance = (a, b) -> {
                Object first = a.getItem();
                Object second = b.getItem();
                ForestNode node = (ForestNode) (first instanceof ForestNode ? first : second);
                Point point = (Point) (first instanceof Point ? first : second);
                return point.getCoordinate().distance(node.getCoordinate());
            };
            Object[] raw = nodeTree.nearestNeighbour(new Envelope(target), query, distance, k);
            List<ForestNode> result = new ArrayList<>(raw.length);
            for (Object item : raw) {
                if (item != null) {
                    result.add((ForestNode) item);
                }
            }
            // Детерминизм: сортировка по (расстояние, id).
            result.sort(Comparator
                    .comparingDouble((ForestNode node) -> node.getCoordinate().distance(target))
                    .thenComparing(ForestNode::getId));
            return result;
        }

        private List<Integer> nearestEdges(Coordinate target, int k) {
            Point query = GeometrySupport.GEOMETRY_FACTORY.createPoint(target);
            ItemDistance distance = (a, b) -> {
                Object first = a.getItem();
                Object second = b.getItem();
                Integer index = (Integer) (first instanceof Integer ? first : second);
                Point point = (Point) (first instanceof Point ? first : second);
                return DistanceOp.distance(point, edgeLines[index]);
            };
            Object[] raw = edgeTree.nearestNeighbour(new Envelope(target), query, distance, k);
            List<Integer> result = new ArrayList<>(raw.length);
            for (Object item : raw) {
                if (item != null) {
                    result.add((Integer) item);
                }
            }
            result.sort(Comparator
                    .comparingDouble((Integer index) -> DistanceOp.distance(query, edgeLines[index]))
                    .thenComparingInt(Integer::intValue));
            return result;
        }
    }
}
