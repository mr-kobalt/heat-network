package ru.lct.heating.hydraulics;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.lct.heating.routing.ForestEdge;

/**
 * Проверка предельной длины по каждому непрерывному пути (ТП v2 §2.3, FR-44):
 * общий участок учитывается в каждом пути, длины параллельных ветвей не
 * суммируются; смена Ду начинает новый отсчёт. ДУ повышается до минимального,
 * одновременно удовлетворяющего расходу и длине (FR-43, FR-46), и не убывает к
 * точке подключения (FR-48).
 */
@Component
public class MaxLengthEnforcer {

    private static final int MAX_ITERATIONS = 64;
    private static final double EPS = 1e-9;

    private final DiameterCatalog diameters;

    public MaxLengthEnforcer(DiameterCatalog diameters) {
        this.diameters = diameters;
    }

    public List<ForestEdge> enforce(List<ForestEdge> edges) {
        return enforce(edges, connectionNode(edges));
    }

    /**
     * @param rootNodeId узел присоединения дерева (начало путей); для прежнего
     *                   конвейера определяется по id ребра {@code *_connection}
     */
    public List<ForestEdge> enforce(List<ForestEdge> edges, String rootNodeId) {
        if (edges.isEmpty() || rootNodeId == null) {
            return edges;
        }
        Map<String, ForestEdge> current = new LinkedHashMap<>();
        for (ForestEdge edge : edges) {
            current.put(edge.getId(), edge);
        }
        for (int iteration = 0; iteration < MAX_ITERATIONS; iteration++) {
            boolean changed = lengthPass(current, rootNodeId);
            changed |= nonDecreasingPass(current, rootNodeId);
            if (!changed) {
                break;
            }
        }
        return new ArrayList<>(current.values());
    }

    /**
     * Проход по каждому пути от присоединения к листьям: непрерывные участки
     * одного Ду проверяются по длине, при превышении Ду повышается.
     */
    private boolean lengthPass(Map<String, ForestEdge> current, String root) {
        List<ForestEdge> edges = new ArrayList<>(current.values());
        if (root == null) {
            return false;
        }
        Map<String, Double> required = new HashMap<>();
        dfs(root, -1, 0.0, new ArrayList<>(), outgoing(edges), required);
        if (required.isEmpty()) {
            return false;
        }
        boolean changed = false;
        for (Map.Entry<String, Double> entry : required.entrySet()) {
            ForestEdge edge = current.get(entry.getKey());
            int target = diameters.selectForAtLeast(edge.getDiameterMm(), edge.getFlowTph(),
                    entry.getValue()).getDn();
            if (target > edge.getDiameterMm()) {
                current.put(edge.getId(), withDiameter(edge, target));
                changed = true;
            }
        }
        return changed;
    }

    private void dfs(String node, int runDn, double runLength, List<String> runEdges,
                     Map<String, List<ForestEdge>> outgoing, Map<String, Double> required) {
        List<ForestEdge> children = outgoing.getOrDefault(node, List.of());
        if (children.isEmpty()) {
            record(runDn, runLength, runEdges, required);
            return;
        }
        for (ForestEdge edge : children) {
            if (edge.getDiameterMm() == runDn) {
                List<String> next = new ArrayList<>(runEdges);
                next.add(edge.getId());
                dfs(edge.getToNodeId(), runDn, runLength + edge.lengthM(), next, outgoing, required);
            } else {
                record(runDn, runLength, runEdges, required);
                List<String> next = new ArrayList<>();
                next.add(edge.getId());
                dfs(edge.getToNodeId(), edge.getDiameterMm(), edge.lengthM(), next,
                        outgoing, required);
            }
        }
    }

    private void record(int runDn, double runLength, List<String> runEdges,
                        Map<String, Double> required) {
        if (runDn <= 0 || runEdges.isEmpty()) {
            return;
        }
        if (runLength <= diameters.maxLengthM(runDn) + EPS) {
            return;
        }
        for (String edgeId : runEdges) {
            required.merge(edgeId, runLength, Math::max);
        }
    }

    /**
     * Ду не убывает от точки подключения к присоединению (FR-48).
     */
    private boolean nonDecreasingPass(Map<String, ForestEdge> current, String root) {
        List<ForestEdge> edges = new ArrayList<>(current.values());
        if (root == null) {
            return false;
        }
        Map<String, List<ForestEdge>> outgoing = outgoing(edges);
        Map<String, ForestEdge> incoming = incoming(edges);
        List<String> order = breadthFirst(root, edges);
        boolean changed = false;
        for (int i = order.size() - 1; i >= 0; i--) {
            String node = order.get(i);
            ForestEdge parent = incoming.get(node);
            if (parent == null) {
                continue;
            }
            int maxChildDn = 0;
            for (ForestEdge child : outgoing.getOrDefault(node, List.of())) {
                maxChildDn = Math.max(maxChildDn, child.getDiameterMm());
            }
            if (maxChildDn > parent.getDiameterMm()) {
                int target = diameters.selectForAtLeast(maxChildDn, parent.getFlowTph(), 0.0).getDn();
                if (target > parent.getDiameterMm()) {
                    current.put(parent.getId(), withDiameter(parent, target));
                    changed = true;
                }
            }
        }
        return changed;
    }

    private String connectionNode(List<ForestEdge> edges) {
        return edges.stream()
                .filter(edge -> edge.getId().endsWith("_connection"))
                .map(ForestEdge::getFromNodeId)
                .findFirst().orElse(null);
    }

    private Map<String, List<ForestEdge>> outgoing(List<ForestEdge> edges) {
        Map<String, List<ForestEdge>> outgoing = new LinkedHashMap<>();
        for (ForestEdge edge : edges) {
            outgoing.computeIfAbsent(edge.getFromNodeId(), key -> new ArrayList<>()).add(edge);
        }
        return outgoing;
    }

    private Map<String, ForestEdge> incoming(List<ForestEdge> edges) {
        Map<String, ForestEdge> incoming = new HashMap<>();
        for (ForestEdge edge : edges) {
            incoming.put(edge.getToNodeId(), edge);
        }
        return incoming;
    }

    private List<String> breadthFirst(String root, List<ForestEdge> edges) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (ForestEdge edge : edges) {
            adjacency.computeIfAbsent(edge.getFromNodeId(), key -> new ArrayList<>())
                    .add(edge.getToNodeId());
        }
        List<String> order = new ArrayList<>();
        java.util.Set<String> visited = new java.util.HashSet<>();
        java.util.Deque<String> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        visited.add(root);
        while (!queue.isEmpty()) {
            String node = queue.poll();
            order.add(node);
            for (String next : adjacency.getOrDefault(node, List.of())) {
                if (visited.add(next)) {
                    queue.add(next);
                }
            }
        }
        return order;
    }

    private ForestEdge withDiameter(ForestEdge edge, int diameterMm) {
        return ForestEdge.builder()
                .id(edge.getId())
                .fromNodeId(edge.getFromNodeId())
                .toNodeId(edge.getToNodeId())
                .coordinates(edge.getCoordinates())
                .flowTph(edge.getFlowTph())
                .diameterMm(diameterMm)
                .build();
    }
}
