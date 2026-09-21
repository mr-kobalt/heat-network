package ru.lct.heating.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;

/**
 * Планирование леса новой сети (ТП v2, ADR-0019/0021): кластеризация точек,
 * MST, присоединение через тепловую камеру (существующую/новую), потоки и Ду.
 */
@Component
public class ForestPlanner {

    private static final double EPS = 1e-6;
    private static final double EDGE_CLEARANCE_M = 0.2;
    private static final double NODE_CLEARANCE_M = 2.0;
    private static final int MAX_CHAMBER_CHILDREN = 3;
    private static final int MAX_CHAMBER_ATTACHMENTS = 4;
    private static final int RECONNECT_TRIES = 4;
    private static final int NETWORK_PROBE_CANDIDATES = 3;

    private final TieInCandidateProvider candidateProvider;
    private final VisibilityGraphRouter router;
    private final DiameterCatalog diameters;
    private final CostModel costModel;
    private final MaxLengthEnforcer maxLengthEnforcer;
    private final RouteCrossingResolver crossingResolver;
    private final OksApproachResolver approachResolver;
    private final LineStringSimplifier simplifier;
    private final AppProperties appProperties;

    public ForestPlanner(TieInCandidateProvider candidateProvider, VisibilityGraphRouter router,
                         DiameterCatalog diameters, CostModel costModel,
                         MaxLengthEnforcer maxLengthEnforcer, RouteCrossingResolver crossingResolver,
                         OksApproachResolver approachResolver, LineStringSimplifier simplifier,
                         AppProperties appProperties) {
        this.candidateProvider = candidateProvider;
        this.router = router;
        this.diameters = diameters;
        this.costModel = costModel;
        this.maxLengthEnforcer = maxLengthEnforcer;
        this.crossingResolver = crossingResolver;
        this.approachResolver = approachResolver;
        this.simplifier = simplifier;
        this.appProperties = appProperties;
    }

    public ForestPlanningResult plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                     ObstacleIndex obstacleIndex, List<String> warnings) {
        return plan(dataset, graph, obstacleIndex, warnings, appProperties.getClusterRadiusM());
    }

    /**
     * Вариант планирования с заданным радиусом кластеризации (FR-75).
     */
    public ForestPlanningResult plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                     ObstacleIndex obstacleIndex, List<String> warnings,
                                     double clusterRadiusM) {
        List<TieInCandidate> candidates = candidateProvider.candidates(dataset);
        Map<String, Double> flows = flowsByConnectionPoint(dataset);
        Map<String, List<OksApproachResolver.Approach>> candidateLists =
                approachResolver.resolveCandidates(dataset, appProperties.getDefaultDiameterMm());

        List<OksConnectionPointObject> points = new ArrayList<>();
        List<String> unconnected = new ArrayList<>();
        Map<String, OksApproachResolver.Approach> approaches = new HashMap<>();
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            Double flow = flows.get(connectionPoint.getId());
            if (flow == null || flow <= 0.0) {
                warnings.add("NO_FLOW: для точки подключения " + connectionPoint.getId()
                        + " не найден расчётный расход");
                unconnected.add(connectionPoint.getId());
                continue;
            }
            OksApproachResolver.Approach approach = chooseApproach(connectionPoint.getId(),
                    candidateLists.get(connectionPoint.getId()), candidates, obstacleIndex);
            if (approach == null || approach.isBlocked()) {
                warnings.add("OKS_APPROACH_BLOCKED: для точки подключения "
                        + connectionPoint.getId() + " не найден допустимый вывод из ОКС");
                unconnected.add(connectionPoint.getId());
                continue;
            }
            approaches.put(connectionPoint.getId(), approach);
            points.add(connectionPoint);
        }
        points.sort(Comparator.comparing(OksConnectionPointObject::getId));

        Map<String, Integer> chamberUsage = new HashMap<>();
        List<ForestTree> trees = new ArrayList<>();
        int index = 0;
        for (List<OksConnectionPointObject> cluster : cluster(points, clusterRadiusM)) {
            ForestTree tree = buildTree(cluster, flows, candidates, dataset, graph, chamberUsage,
                    obstacleIndex, approaches, warnings, unconnected, index++);
            if (tree != null) {
                trees.add(tree);
            }
        }
        attachUnconnectedAsSingleTrees(points, flows, candidates, dataset, graph, chamberUsage,
                obstacleIndex, approaches, warnings, unconnected, trees, index);
        return ForestPlanningResult.builder()
                .trees(trees)
                .unconnectedConnectionPointIds(unconnected)
                .build();
    }

    /** Область вокруг точек стыковки: исключается из препятствий-участков. */
    private Geometry nodeClearance(List<OksConnectionPointObject> cluster,
                                   Map<String, OksApproachResolver.Approach> approaches,
                                   Coordinate connectionCoordinate) {
        List<Geometry> disks = new ArrayList<>();
        for (OksConnectionPointObject connectionPoint : cluster) {
            Coordinate coordinate = target(approaches, connectionPoint.getId());
            if (coordinate != null) {
                disks.add(GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate)
                        .buffer(NODE_CLEARANCE_M));
            }
        }
        if (connectionCoordinate != null) {
            disks.add(GeometrySupport.GEOMETRY_FACTORY.createPoint(connectionCoordinate)
                    .buffer(NODE_CLEARANCE_M));
        }
        if (disks.isEmpty()) {
            return GeometrySupport.GEOMETRY_FACTORY.createGeometryCollection();
        }
        return GeometrySupport.GEOMETRY_FACTORY
                .createGeometryCollection(disks.toArray(new Geometry[0]))
                .union();
    }

    /** Препятствие из уже проложенного участка (FR-29). */
    private Geometry routeBuffer(List<Coordinate> path, Geometry nodeClear) {
        if (path == null || path.size() < 2) {
            return null;
        }
        LineString line = GeometrySupport.GEOMETRY_FACTORY
                .createLineString(path.toArray(new Coordinate[0]));
        return line.buffer(EDGE_CLEARANCE_M).difference(nodeClear);
    }

    private ObstacleIndex addRoute(ObstacleIndex index, List<Coordinate> path, Geometry nodeClear) {
        Geometry buffer = routeBuffer(path, nodeClear);
        return buffer == null ? index : index.withAdditional(List.of(buffer));
    }

    /**
     * Кратчайший кандидат вывода; если его точка стыковки недостижима для сети —
     * следующий по длине достижимый (вынужденный откат из замкнутого кармана).
     */
    /**
     * Точки, не подключившиеся к общему дереву, подключаются отдельными
     * деревьями к сети — связность важнее экономии на общем стволе (FR-77).
     */
    private void attachUnconnectedAsSingleTrees(List<OksConnectionPointObject> points,
                                                Map<String, Double> flows,
                                                List<TieInCandidate> candidates,
                                                NetworkDataset dataset,
                                                ExistingNetworkGraph graph,
                                                Map<String, Integer> chamberUsage,
                                                ObstacleIndex obstacleIndex,
                                                Map<String, OksApproachResolver.Approach> approaches,
                                                List<String> warnings, List<String> unconnected,
                                                List<ForestTree> trees, int startIndex) {
        if (unconnected.isEmpty()) {
            return;
        }
        Map<String, OksConnectionPointObject> byId = new HashMap<>();
        for (OksConnectionPointObject point : points) {
            byId.put(point.getId(), point);
        }
        List<String> pending = new ArrayList<>(unconnected);
        unconnected.clear();
        int index = startIndex + trees.size();
        for (String id : pending) {
            OksConnectionPointObject point = byId.get(id);
            if (point == null || !approaches.containsKey(id)) {
                unconnected.add(id);
                continue;
            }
            ForestTree tree = buildTree(List.of(point), flows, candidates, dataset, graph, chamberUsage,
                    obstacleIndex, approaches, warnings, unconnected, index++);
            if (tree != null) {
                trees.add(tree);
            }
        }
    }

    private ForestTree buildTree(List<OksConnectionPointObject> cluster, Map<String, Double> flows,
                                 List<TieInCandidate> candidates, NetworkDataset dataset,
                                 ExistingNetworkGraph graph,
                                 Map<String, Integer> chamberUsage, ObstacleIndex obstacleIndex,
                                 Map<String, OksApproachResolver.Approach> approaches,
                                 List<String> warnings, List<String> unconnected, int index) {
        int n = cluster.size();
        double totalFlow = 0.0;
        for (OksConnectionPointObject connectionPoint : cluster) {
            totalFlow += flows.get(connectionPoint.getId());
        }
        int trunkDn = diameters.select(totalFlow).getDn();

        int rootIndex = chooseRoot(cluster, candidates);
        Coordinate rootTarget = target(approaches, cluster.get(rootIndex).getId());
        ConnectionChoice connection = chooseConnection(rootTarget,
                tailPoint(approaches, cluster.get(rootIndex).getId()), trunkDn, candidates, dataset,
                graph, chamberUsage, obstacleIndex, index);
        if (connection == null) {
            for (OksConnectionPointObject connectionPoint : cluster) {
                unconnected.add(connectionPoint.getId());
            }
            return null;
        }

        int[] parent = new int[n];
        int[] order = breadthFirst(parent, euclideanMst(cluster), rootIndex, n);
        boolean[] reachable = new boolean[n];
        reachable[rootIndex] = true;
        List<ForestEdge> edges = new ArrayList<>();
        Map<Integer, ForestEdge> edgeByChild = new HashMap<>();

        for (int position = 1; position < order.length; position++) {
            int child = order[position];
            if (reachable[child]) {
                continue;
            }
            int from = parent[child];
            List<Coordinate> path = reachable[from]
                    ? router.findPath(target(approaches, cluster.get(from).getId()),
                            target(approaches, cluster.get(child).getId()), obstacleIndex,
                            tailPoint(approaches, cluster.get(from).getId()),
                            tailPoint(approaches, cluster.get(child).getId()))
                    : null;
            if (path == null) {
                // Fallback: переподключение к другому достижимому узлу (FR-24/FR-77).
                Reconnect reconnect = reconnectParent(cluster, approaches, obstacleIndex,
                        reachable, child, from);
                if (reconnect == null) {
                    continue;
                }
                from = reconnect.parentIndex;
                parent[child] = from;
                path = reconnect.path;
            }
            reachable[child] = true;
            ForestEdge edge = ForestEdge.builder()
                    .id("e_" + index + "_" + cluster.get(child).getId())
                    .fromNodeId(cluster.get(from).getId())
                    .toNodeId(cluster.get(child).getId())
                    .coordinates(simplifier.simplify(combine(
                            approaches.get(cluster.get(from).getId()), path,
                            approaches.get(cluster.get(child).getId()))))
                    .build();
            edges.add(edge);
            edgeByChild.put(child, edge);
        }
        for (int i = 0; i < n; i++) {
            if (!reachable[i]) {
                unconnected.add(cluster.get(i).getId());
            }
        }
        if (!reachable[rootIndex]) {
            return null;
        }

        ForestNode connectionNode = connection.node;
        Map<String, ForestNode> nodes = new HashMap<>();
        nodes.put(connectionNode.getId(), connectionNode);
        if (connectionNode.isExisting()) {
            chamberUsage.merge(connectionNode.getExistingObjectId(), 1, Integer::sum);
        }

        double[] subtreeFlow = subtreeFlows(cluster, flows, parent, order, reachable);
        for (Map.Entry<Integer, ForestEdge> entry : edgeByChild.entrySet()) {
            ForestEdge edge = entry.getValue();
            double flow = subtreeFlow[entry.getKey()];
            edgeByChild.put(entry.getKey(), copy(edge, edge.getFromNodeId(), edge.getToNodeId(),
                    flow, diameters.select(flow).getDn()));
        }
        List<ForestEdge> finalEdges = new ArrayList<>();
        for (int position = 1; position < order.length; position++) {
            ForestEdge edge = edgeByChild.get(order[position]);
            if (edge != null && reachable[order[position]]) {
                finalEdges.add(edge);
            }
        }
        finalEdges.add(ForestEdge.builder()
                .id("e_" + index + "_connection")
                .fromNodeId(connectionNode.getId())
                .toNodeId(cluster.get(rootIndex).getId())
                .coordinates(simplifier.simplify(combine(
                        null, reversed(connection.route),
                        approaches.get(cluster.get(rootIndex).getId()))))
                .flowTph(subtreeFlow[rootIndex])
                .diameterMm(diameters.select(subtreeFlow[rootIndex]).getDn())
                .build());

        for (int i = 0; i < n; i++) {
            if (!reachable[i]) {
                continue;
            }
            int children = countChildren(cluster.get(i).getId(), finalEdges);
            NodeType type = children >= 1 ? NodeType.CHAMBER : NodeType.CONNECTION_POINT;
            nodes.put(cluster.get(i).getId(), ForestNode.builder()
                    .id(cluster.get(i).getId())
                    .type(type)
                    .coordinate(cluster.get(i).getGeometry().getCoordinate())
                    .build());
        }

        finalEdges = crossingResolver.resolve(finalEdges, obstacleIndex, approaches, warnings);
        finalEdges = enforceDegree(finalEdges, nodes, index, approaches, obstacleIndex, warnings);
        finalEdges = maxLengthEnforcer.enforce(finalEdges);
        return ForestTree.builder()
                .tieInNodeId(connectionNode.getId())
                .nodes(nodes)
                .edges(finalEdges)
                .build();
    }

    /**
     * Выбор места присоединения: существующая камера (если ≤10 м и есть
     * свободные примыкания) либо новая камера в точке сети (ТП 2.4, FR-22).
     */
    private ConnectionChoice chooseConnection(Coordinate root, Coordinate rootPrevious, int trunkDn,
                                              List<TieInCandidate> candidates,
                                              NetworkDataset dataset,
                                              ExistingNetworkGraph graph,
                                              Map<String, Integer> chamberUsage,
                                              ObstacleIndex obstacleIndex, int index) {
        // Сэмплы + точные проекции корня на участки сети (непрерывная точка врезки).
        List<TieInCandidate> all = new ArrayList<>(candidates);
        all.addAll(candidateProvider.projections(dataset, root));
        all = candidateProvider.distinct(all);
        all.sort(Comparator.comparingDouble(candidate -> root.distance(candidate.getCoordinate())));
        double bestScore = Double.POSITIVE_INFINITY;
        ConnectionChoice best = null;
        int considered = 0;
        for (TieInCandidate candidate : all) {
            if (considered++ >= appProperties.getTieInCandidates()) {
                break;
            }
            ForestNode chamber = resolveChamber(candidate, graph, chamberUsage, index);
            if (chamber == null) {
                continue;
            }
            List<Coordinate> path = router.findPath(root, chamber.getCoordinate(), obstacleIndex,
                    rootPrevious, null);
            if (path == null) {
                continue;
            }
            double length = pathLength(path);
            double connectionCost = chamber.isExisting()
                    ? costModel.existingChamberTieInCost()
                    : costModel.chamberCost(trunkDn);
            double score = length * diameters.newCostPerM(trunkDn) + connectionCost;
            if (score < bestScore - EPS) {
                bestScore = score;
                best = new ConnectionChoice(chamber, path);
            }
        }
        return best;
    }

    private ForestNode resolveChamber(TieInCandidate candidate, ExistingNetworkGraph graph,
                                      Map<String, Integer> chamberUsage, int index) {
        if ("heat_chamber".equals(candidate.getExistingObjectType())) {
            return existingChamber(candidate.getExistingObjectId(), graph, chamberUsage);
        }
        double radius = appProperties.getChamberTieInRadiusM();
        HeatChamberObject nearest = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (HeatChamberObject chamber : graph.getChambers().values()) {
            if (chamber.getGeometry() == null || !hasCapacity(chamber.getId(), graph, chamberUsage)) {
                continue;
            }
            double distance = candidate.getCoordinate().distance(chamber.getGeometry().getCoordinate());
            if (distance <= radius && distance < bestDistance) {
                bestDistance = distance;
                nearest = chamber;
            }
        }
        if (nearest != null) {
            return existingChamber(nearest.getId(), graph, chamberUsage);
        }
        return ForestNode.builder()
                .id("ch_" + index)
                .type(NodeType.CHAMBER)
                .existing(false)
                .coordinate(candidate.getCoordinate())
                .build();
    }

    private ForestNode existingChamber(String chamberId, ExistingNetworkGraph graph,
                                       Map<String, Integer> chamberUsage) {
        if (!hasCapacity(chamberId, graph, chamberUsage)) {
            return null;
        }
        HeatChamberObject chamber = graph.getChambers().get(chamberId);
        if (chamber == null || chamber.getGeometry() == null) {
            return null;
        }
        return ForestNode.builder()
                .id(chamberId)
                .type(NodeType.CHAMBER)
                .existing(true)
                .existingObjectId(chamberId)
                .coordinate(chamber.getGeometry().getCoordinate())
                .build();
    }

    private boolean hasCapacity(String chamberId, ExistingNetworkGraph graph,
                                Map<String, Integer> chamberUsage) {
        int used = graph.chamberAttachments(chamberId) + chamberUsage.getOrDefault(chamberId, 0);
        return used < MAX_CHAMBER_ATTACHMENTS;
    }

    /**
     * Степень новой камеры ≤4 (1 к источнику + ≤3 других). Лишние лучи
     * переносятся на дополнительную камеру-разветвитель (эвристика).
     */
    private List<ForestEdge> enforceDegree(List<ForestEdge> edges, Map<String, ForestNode> nodes,
                                           int treeIndex,
                                           Map<String, OksApproachResolver.Approach> approaches,
                                           ObstacleIndex obstacleIndex, List<String> warnings) {
        List<ForestEdge> current = new ArrayList<>(edges);
        boolean changed = true;
        int counter = 0;
        while (changed) {
            changed = false;
            Map<String, List<ForestEdge>> children = childrenEdges(current);
            for (Map.Entry<String, List<ForestEdge>> entry : children.entrySet()) {
                ForestNode node = nodes.get(entry.getKey());
                if (node == null || node.isExisting()) {
                    continue;
                }
                List<ForestEdge> childEdges = entry.getValue();
                if (childEdges.size() <= MAX_CHAMBER_CHILDREN) {
                    continue;
                }
                childEdges.sort(Comparator.comparing(ForestEdge::getId));
                int keep = MAX_CHAMBER_CHILDREN - 1;
                List<ForestEdge> excess = new ArrayList<>(childEdges.subList(keep, childEdges.size()));
                Coordinate centroid = centroid(excess, nodes);
                String chamberId = "br_" + treeIndex + "_" + counter++;
                nodes.put(chamberId, ForestNode.builder()
                        .id(chamberId).type(NodeType.CHAMBER).coordinate(centroid).build());
                double flow = excess.stream().mapToDouble(ForestEdge::getFlowTph).sum();
                current.removeAll(excess);
                current.add(branchEdge(node, chamberId, centroid, flow, treeIndex,
                        approaches, obstacleIndex, warnings));
                for (ForestEdge edge : excess) {
                    current.add(reparent(edge, chamberId, centroid, nodes, approaches,
                            obstacleIndex, warnings));
                }
                changed = true;
                break;
            }
        }
        return current;
    }

    private ForestEdge branchEdge(ForestNode node, String chamberId, Coordinate centroid,
                                  double flow, int treeIndex,
                                  Map<String, OksApproachResolver.Approach> approaches,
                                  ObstacleIndex obstacleIndex, List<String> warnings) {
        Coordinate start = node.getCoordinate();
        Coordinate startPrevious = null;
        List<Coordinate> coordinates = new ArrayList<>();
        OksApproachResolver.Approach approach = approaches.get(node.getId());
        if (approach != null && !approach.isBlocked() && approach.getTail().size() == 2) {
            startPrevious = start;
            coordinates.add(start);
            start = approach.getTarget();
        }
        List<Coordinate> path = router.findPath(start, centroid, obstacleIndex, startPrevious, null);
        if (path == null) {
            warnings.add("BRANCH_ROUTE_UNRESOLVED: узел " + node.getId()
                    + " — луч к камере " + chamberId + " проложен по прямой");
            path = List.of(start, centroid);
        }
        coordinates.addAll(path);
        return ForestEdge.builder()
                .id("e_" + treeIndex + "_" + chamberId)
                .fromNodeId(node.getId())
                .toNodeId(chamberId)
                .coordinates(simplifier.simplify(coordinates))
                .flowTph(flow)
                .diameterMm(diameters.select(flow).getDn())
                .build();
    }

    private ForestEdge reparent(ForestEdge edge, String chamberId,
                                Coordinate centroid, Map<String, ForestNode> nodes,
                                Map<String, OksApproachResolver.Approach> approaches,
                                ObstacleIndex obstacleIndex, List<String> warnings) {
        String childId = edge.getToNodeId();
        List<Coordinate> coordinates = new ArrayList<>();
        Coordinate end;
        Coordinate endNext = null;
        List<Coordinate> tail = List.of();
        OksApproachResolver.Approach approach = approaches.get(childId);
        if (approach != null && !approach.isBlocked() && approach.getTail().size() == 2) {
            end = approach.getTarget();
            endNext = approach.getTail().get(1);
            tail = approach.getTail();
        } else {
            ForestNode child = nodes.get(childId);
            end = child != null ? child.getCoordinate()
                    : edge.getCoordinates().get(edge.getCoordinates().size() - 1);
        }
        List<Coordinate> path = router.findPath(centroid, end, obstacleIndex, null, endNext);
        if (path == null) {
            warnings.add("BRANCH_ROUTE_UNRESOLVED: участок " + edge.getId()
                    + " к " + childId + " проложен по прямой");
            coordinates.addAll(edge.getCoordinates());
            return copy(edge, chamberId, childId, edge.getFlowTph(), edge.getDiameterMm());
        }
        coordinates.addAll(path);
        coordinates.addAll(tail);
        return ForestEdge.builder()
                .id(edge.getId())
                .fromNodeId(chamberId)
                .toNodeId(childId)
                .coordinates(simplifier.simplify(coordinates))
                .flowTph(edge.getFlowTph())
                .diameterMm(edge.getDiameterMm())
                .build();
    }

    private Map<String, List<ForestEdge>> childrenEdges(List<ForestEdge> edges) {
        String connectionNode = edges.stream()
                .filter(edge -> edge.getId().endsWith("_connection"))
                .map(ForestEdge::getFromNodeId)
                .findFirst().orElse(null);
        Map<String, List<ForestEdge>> result = new LinkedHashMap<>();
        if (connectionNode == null) {
            return result;
        }
        Deque<String> queue = new ArrayDeque<>();
        queue.add(connectionNode);
        Set<String> visited = new HashSet<>();
        visited.add(connectionNode);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            List<ForestEdge> outgoing = new ArrayList<>();
            for (ForestEdge edge : edges) {
                if (edge.getFromNodeId().equals(node)) {
                    outgoing.add(edge);
                    if (visited.add(edge.getToNodeId())) {
                        queue.add(edge.getToNodeId());
                    }
                }
            }
            result.put(node, outgoing);
        }
        return result;
    }

    private Coordinate centroid(List<ForestEdge> excess, Map<String, ForestNode> nodes) {
        double x = 0.0;
        double y = 0.0;
        int count = 0;
        for (ForestEdge edge : excess) {
            ForestNode node = nodes.get(edge.getToNodeId());
            if (node != null) {
                x += node.getCoordinate().x;
                y += node.getCoordinate().y;
                count++;
            }
        }
        return count == 0 ? new Coordinate(0, 0) : new Coordinate(x / count, y / count);
    }

    private int countChildren(String nodeId, List<ForestEdge> edges) {
        int count = 0;
        for (ForestEdge edge : edges) {
            if (edge.getFromNodeId().equals(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private double[] subtreeFlows(List<OksConnectionPointObject> cluster, Map<String, Double> flows,
                                  int[] parent, int[] order, boolean[] reachable) {
        double[] subtree = new double[cluster.size()];
        for (int position = order.length - 1; position >= 0; position--) {
            int node = order[position];
            if (!reachable[node]) {
                continue;
            }
            double flow = flows.get(cluster.get(node).getId());
            for (int other = 0; other < order.length; other++) {
                if (reachable[other] && other != node && parent[other] == node) {
                    flow += subtree[other];
                }
            }
            subtree[node] = flow;
        }
        return subtree;
    }

    private int chooseRoot(List<OksConnectionPointObject> cluster, List<TieInCandidate> candidates) {
        int root = 0;
        double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < cluster.size(); i++) {
            Coordinate coordinate = cluster.get(i).getGeometry().getCoordinate();
            double nearest = nearestCandidateDistance(coordinate, candidates);
            if (nearest < best - EPS) {
                best = nearest;
                root = i;
            }
        }
        return root;
    }

    private double nearestCandidateDistance(Coordinate coordinate, List<TieInCandidate> candidates) {
        double best = Double.POSITIVE_INFINITY;
        for (TieInCandidate candidate : candidates) {
            best = Math.min(best, coordinate.distance(candidate.getCoordinate()));
        }
        return best;
    }

    private int[] euclideanMst(List<OksConnectionPointObject> cluster) {
        int n = cluster.size();
        List<int[]> edges = new ArrayList<>();
        if (n <= 1) {
            return new int[0];
        }
        boolean[] inTree = new boolean[n];
        double[] best = new double[n];
        int[] bestFrom = new int[n];
        for (int i = 0; i < n; i++) {
            best[i] = Double.POSITIVE_INFINITY;
            bestFrom[i] = -1;
        }
        best[0] = 0.0;
        for (int iteration = 0; iteration < n; iteration++) {
            int selected = -1;
            for (int i = 0; i < n; i++) {
                if (!inTree[i] && (selected == -1 || best[i] < best[selected])) {
                    selected = i;
                }
            }
            inTree[selected] = true;
            if (iteration > 0) {
                edges.add(new int[]{bestFrom[selected], selected});
            }
            Coordinate selectedCoordinate = cluster.get(selected).getGeometry().getCoordinate();
            for (int i = 0; i < n; i++) {
                if (!inTree[i]) {
                    double distance = selectedCoordinate
                            .distance(cluster.get(i).getGeometry().getCoordinate());
                    if (distance < best[i] - EPS) {
                        best[i] = distance;
                        bestFrom[i] = selected;
                    }
                }
            }
        }
        return flatten(edges);
    }

    private int[] breadthFirst(int[] parent, int[] mstEdges, int root, int n) {
        Map<Integer, List<Integer>> adjacency = new HashMap<>();
        for (int i = 0; i < mstEdges.length; i += 2) {
            adjacency.computeIfAbsent(mstEdges[i], key -> new ArrayList<>()).add(mstEdges[i + 1]);
            adjacency.computeIfAbsent(mstEdges[i + 1], key -> new ArrayList<>()).add(mstEdges[i]);
        }
        int[] order = new int[n];
        boolean[] visited = new boolean[n];
        Deque<Integer> queue = new ArrayDeque<>();
        queue.add(root);
        visited[root] = true;
        parent[root] = root;
        int count = 0;
        while (!queue.isEmpty()) {
            int node = queue.poll();
            order[count++] = node;
            List<Integer> neighbors = new ArrayList<>(adjacency.getOrDefault(node, List.of()));
            neighbors.sort(Comparator.naturalOrder());
            for (int neighbor : neighbors) {
                if (!visited[neighbor]) {
                    visited[neighbor] = true;
                    parent[neighbor] = node;
                    queue.add(neighbor);
                }
            }
        }
        return order;
    }

    private ForestEdge copy(ForestEdge edge, String from, String to, double flow, int diameter) {
        return ForestEdge.builder()
                .id(edge.getId())
                .fromNodeId(from)
                .toNodeId(to)
                .coordinates(edge.getCoordinates())
                .flowTph(flow)
                .diameterMm(diameter)
                .build();
    }

    private Map<String, Double> flowsByConnectionPoint(NetworkDataset dataset) {
        Map<String, Double> result = new HashMap<>();
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            result.put(connectionPoint.getId(), connectionPoint.getFlowTph());
        }
        return result;
    }

    /**
     * Если ребро к родителю непроходимо, узел подключается к ближайшему уже
     * достижимому узлу дерева (альтернативная топология). Цикл невозможен, так
     * как узел ещё недостижим.
     */
    private Reconnect reconnectParent(List<OksConnectionPointObject> cluster,
                                      Map<String, OksApproachResolver.Approach> approaches,
                                      ObstacleIndex obstacleIndex, boolean[] reachable,
                                      int child, int exclude) {
        Coordinate childCoordinate = cluster.get(child).getGeometry().getCoordinate();
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < reachable.length; i++) {
            if (reachable[i] && i != child && i != exclude) {
                candidates.add(i);
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> childCoordinate.distance(
                cluster.get(candidate).getGeometry().getCoordinate())));
        int tried = 0;
        for (int candidate : candidates) {
            if (tried++ >= RECONNECT_TRIES) {
                break;
            }
            List<Coordinate> path = router.findPath(
                    target(approaches, cluster.get(candidate).getId()),
                    target(approaches, cluster.get(child).getId()), obstacleIndex,
                    tailPoint(approaches, cluster.get(candidate).getId()),
                    tailPoint(approaches, cluster.get(child).getId()));
            if (path != null) {
                return new Reconnect(candidate, path);
            }
        }
        return null;
    }

    private OksApproachResolver.Approach chooseApproach(String pointId,
                                                        List<OksApproachResolver.Approach> list,
                                                        List<TieInCandidate> candidates,
                                                        ObstacleIndex index) {
        if (list == null || list.isEmpty()) {
            return null;
        }
        OksApproachResolver.Approach first = null;
        for (OksApproachResolver.Approach approach : list) {
            if (approach.isBlocked()) {
                continue;
            }
            if (first == null) {
                first = approach;
            }
            boolean reach = approach.getTail().isEmpty()
                    || reachesNetwork(approach.getTarget(), approachPoint(approach),
                            candidates, index);
            if (reach) {
                return approach;
            }
        }
        return first != null ? first : list.get(0);
    }

    private Coordinate approachPoint(OksApproachResolver.Approach approach) {
        return approach.getTail().size() == 2 ? approach.getTail().get(1) : null;
    }

    private Coordinate tailPoint(Map<String, OksApproachResolver.Approach> approaches, String id) {
        OksApproachResolver.Approach approach = approaches.get(id);
        return approach == null ? null : approachPoint(approach);
    }

    private boolean reachesNetwork(Coordinate target, Coordinate startPrevious,
                                   List<TieInCandidate> candidates, ObstacleIndex index) {
        for (Coordinate coordinate : nearestCandidates(target, candidates, NETWORK_PROBE_CANDIDATES)) {
            if (coordinate != null
                    && router.findPath(target, coordinate, index, startPrevious, null) != null) {
                return true;
            }
        }
        return false;
    }

    private Coordinate[] nearestCandidates(Coordinate target, List<TieInCandidate> candidates,
                                           int count) {
        Coordinate[] result = new Coordinate[count];
        double[] distances = new double[count];
        java.util.Arrays.fill(distances, Double.POSITIVE_INFINITY);
        for (TieInCandidate candidate : candidates) {
            double distance = target.distance(candidate.getCoordinate());
            for (int i = 0; i < count; i++) {
                if (distance < distances[i] - EPS) {
                    for (int j = count - 1; j > i; j--) {
                        distances[j] = distances[j - 1];
                        result[j] = result[j - 1];
                    }
                    distances[i] = distance;
                    result[i] = candidate.getCoordinate();
                    break;
                }
            }
        }
        return result;
    }

    private Coordinate target(Map<String, OksApproachResolver.Approach> approaches, String pointId) {
        OksApproachResolver.Approach approach = approaches.get(pointId);
        return approach == null ? null : approach.getTarget();
    }

    private List<Coordinate> combine(OksApproachResolver.Approach previous, List<Coordinate> path,
                                     OksApproachResolver.Approach next) {
        List<Coordinate> result = new ArrayList<>();
        if (previous != null && previous.getTail().size() == 2) {
            result.add(previous.getTail().get(1));
            result.add(previous.getTail().get(0));
        }
        result.addAll(path);
        if (next != null && next.getTail().size() == 2) {
            result.addAll(next.getTail());
        }
        return result;
    }

    private List<Coordinate> reversed(List<Coordinate> coordinates) {
        List<Coordinate> result = new ArrayList<>(coordinates);
        java.util.Collections.reverse(result);
        return result;
    }

    private double pathLength(List<Coordinate> path) {
        double total = 0.0;
        for (int i = 1; i < path.size(); i++) {
            total += path.get(i - 1).distance(path.get(i));
        }
        return total;
    }

    private List<List<OksConnectionPointObject>> cluster(List<OksConnectionPointObject> points,
                                                         double radius) {
        int n = points.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        double cell = Math.max(radius, 1.0);
        Map<String, List<Integer>> grid = new HashMap<>();
        for (int i = 0; i < n; i++) {
            grid.computeIfAbsent(cellKey(points.get(i).getGeometry().getCoordinate(), cell),
                    key -> new ArrayList<>()).add(i);
        }
        for (int i = 0; i < n; i++) {
            Coordinate coordinate = points.get(i).getGeometry().getCoordinate();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    List<Integer> neighbors = grid.get(cellKey(coordinate, cell, dx, dy));
                    if (neighbors == null) {
                        continue;
                    }
                    for (int j : neighbors) {
                        if (j > i && coordinate.distance(
                                points.get(j).getGeometry().getCoordinate()) <= radius) {
                            union(parent, i, j);
                        }
                    }
                }
            }
        }
        Map<Integer, List<OksConnectionPointObject>> groups = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            groups.computeIfAbsent(find(parent, i), key -> new ArrayList<>()).add(points.get(i));
        }
        return new ArrayList<>(groups.values());
    }

    private String cellKey(Coordinate coordinate, double cell) {
        return cellKey(coordinate, cell, 0, 0);
    }

    private String cellKey(Coordinate coordinate, double cell, int dx, int dy) {
        long cx = (long) Math.floor(coordinate.x / cell) + dx;
        long cy = (long) Math.floor(coordinate.y / cell) + dy;
        return cx + ":" + cy;
    }

    private int find(int[] parent, int value) {
        while (parent[value] != value) {
            parent[value] = parent[parent[value]];
            value = parent[value];
        }
        return value;
    }

    private void union(int[] parent, int a, int b) {
        int rootA = find(parent, a);
        int rootB = find(parent, b);
        if (rootA != rootB) {
            parent[Math.max(rootA, rootB)] = Math.min(rootA, rootB);
        }
    }

    private int[] flatten(List<int[]> edges) {
        int[] result = new int[edges.size() * 2];
        for (int i = 0; i < edges.size(); i++) {
            result[i * 2] = edges.get(i)[0];
            result[i * 2 + 1] = edges.get(i)[1];
        }
        return result;
    }

    private static final class ConnectionChoice {
        private final ForestNode node;
        private final List<Coordinate> route;

        private ConnectionChoice(ForestNode node, List<Coordinate> route) {
            this.node = node;
            this.route = route;
        }
    }

    private static final class Reconnect {
        private final int parentIndex;
        private final List<Coordinate> path;

        private Reconnect(int parentIndex, List<Coordinate> path) {
            this.parentIndex = parentIndex;
            this.path = path;
        }
    }
}
