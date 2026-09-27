package ru.lct.heating.routing;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;

/**
 * ADR-0062: глобальный подбор условных диаметров на всё дерево новой сети.
 *
 * <p>В отличие от {@link ru.lct.heating.hydraulics.MaxLengthEnforcer}, который
 * жадно поднимает Ду всей плети, здесь выбирается назначение Ду на все рёбра,
 * минимизирующее стоимость участков и новых камер при ограничениях:
 * пропускная способность (FR-43), невозрастание Ду к точке подключения (FR-48)
 * и предельная длина плети одного Ду по непрерывным путям (FR-44). Геометрия и
 * длины не меняются, поэтому минимум стоимости = минимум {@code S}.</p>
 *
 * <p>Так как Ду не убывает к корню, у любого не-корневого узла наибольший
 * инцидентный Ду — это Ду ребра к родителю; поэтому стоимость камеры в узле
 * зависит только от входного Ду, и поддеревья раскладываются независимо.
 * Единственная связь — камера-корень (её ступень зависит от максимального Ду
 * исходящих рёбер), она обрабатывается отдельным перебором по максимуму.</p>
 */
public class DiameterTreeOptimizer {

    private static final long INF = Long.MAX_VALUE / 4;
    private static final double EPS = 1e-6;

    private final DiameterCatalog diameters;
    private final CostModel costModel;
    private final AppProperties appProperties;

    public DiameterTreeOptimizer(DiameterCatalog diameters, CostModel costModel,
                                 AppProperties appProperties) {
        this.diameters = diameters;
        this.costModel = costModel;
        this.appProperties = appProperties;
    }

    /**
     * @return рёбра с подобранными Ду; {@code null}, если превышен бюджет
     *         состояний (вызывающая сторона использует {@code MaxLengthEnforcer}).
     * @throws IllegalArgumentException если путь длиннее предела даже при
     *         максимальном Ду — как у {@code MaxLengthEnforcer}
     */
    public List<ForestEdge> optimize(Map<String, ForestNode> nodes, List<ForestEdge> edges,
                                     String rootNodeId, SpecialZoneIndex specialZones) {
        if (edges.isEmpty() || rootNodeId == null) {
            return edges;
        }
        return new Solver(nodes, edges, rootNodeId, specialZones).solve();
    }

    /** Состояние одного вызова (безопасно для параллельных деревьев). */
    private final class Solver {

        private final Map<String, ForestNode> nodes;
        private final List<ForestEdge> edges;
        private final String root;
        private final SpecialZoneIndex specialZones;
        private final int[] dns;
        private final Map<Integer, Double> maxLengthByDn = new HashMap<>();
        private final Map<String, List<ForestEdge>> children = new LinkedHashMap<>();
        private final Map<String, Double> lengthCache = new HashMap<>();
        private final Map<String, Double> kSpecialCache = new HashMap<>();
        private final Map<String, Integer> minDnCache = new HashMap<>();
        private final Map<String, Map<Long, Long>> memo = new HashMap<>();
        private final int maxStates;
        private int stateCount = 0;
        private boolean budgetExceeded = false;

        private Solver(Map<String, ForestNode> nodes, List<ForestEdge> edges, String root,
                       SpecialZoneIndex specialZones) {
            this.nodes = nodes;
            this.edges = edges;
            this.root = root;
            this.specialZones = specialZones;
            this.maxStates = Math.max(1, appProperties.getForestDiameterOptimizerMaxStates());
            List<Integer> values = new ArrayList<>();
            for (DiameterRow row : diameters.rows()) {
                values.add(row.getDn());
                maxLengthByDn.put(row.getDn(), row.getMaxLengthM());
            }
            this.dns = values.stream().mapToInt(Integer::intValue).toArray();
            buildChildren();
        }

        List<ForestEdge> solve() {
            List<ForestEdge> rootEdges = children.getOrDefault(root, List.of());
            if (rootEdges.isEmpty()) {
                return edges;
            }
            long best = INF;
            int bestMax = -1;
            for (int m : dns) {
                long sum = 0L;
                boolean feasible = true;
                for (ForestEdge edge : rootEdges) {
                    long value = bestChild(edge, root, 0, m, 0.0, true);
                    if (value >= INF) {
                        feasible = false;
                        break;
                    }
                    sum = sat(sum, value);
                }
                if (!feasible || sum >= INF) {
                    continue;
                }
                long chamber = isNewChamber(nodes.get(root)) ? costModel.chamberCost(m) : 0L;
                long total = sat(sum, chamber);
                if (total < best) {
                    best = total;
                    bestMax = m;
                }
            }
            if (budgetExceeded) {
                return null;
            }
            if (bestMax < 0 || best >= INF) {
                throw new IllegalArgumentException(
                        "Глобальный подбор Ду: предельная длина не выполнима");
            }
            Map<String, Integer> chosen = new HashMap<>();
            for (ForestEdge edge : rootEdges) {
                assignChild(edge, root, 0, bestMax, 0.0, true, chosen);
            }
            List<ForestEdge> result = new ArrayList<>(edges.size());
            for (ForestEdge edge : edges) {
                Integer dn = chosen.get(edge.getId());
                result.add(dn == null ? edge : withDiameter(edge, dn));
            }
            return result;
        }

        /**
         * Стоимость ребра и его поддерева: перебор Ду ребра в
         * {@code [minDn(edge), upperDn]} с учётом продолжения/сброса плети.
         */
        private long bestChild(ForestEdge edge, String parent, int parentDn, int upperDn,
                               double runLen, boolean fresh) {
            long best = INF;
            for (int cn : dns) {
                if (cn > upperDn || cn < minDn(edge)) {
                    continue;
                }
                double nextRun;
                if (!fresh && cn == parentDn) {
                    nextRun = runLen + length(edge);
                    if (nextRun > maxLen(parentDn) + EPS) {
                        continue;
                    }
                } else {
                    nextRun = length(edge);
                    if (nextRun > maxLen(cn) + EPS) {
                        continue;
                    }
                }
                long child = dp(childNode(edge, parent), cn, nextRun);
                if (child >= INF) {
                    continue;
                }
                long total = sat(edgeCost(edge, cn), child);
                if (total < best) {
                    best = total;
                }
            }
            return best;
        }

        /** Минимальная стоимость поддерева узла {@code v} при входном Ду. */
        private long dp(String v, int parentDn, double runLen) {
            long key = ((long) parentDn << 40) | Math.round(runLen * 1000.0);
            Map<Long, Long> byNode = memo.computeIfAbsent(v, k -> new HashMap<>());
            Long cached = byNode.get(key);
            if (cached != null) {
                return cached;
            }
            if (stateCount > maxStates) {
                budgetExceeded = true;
                return INF;
            }
            stateCount++;
            long cost = isNewChamber(nodes.get(v)) ? costModel.chamberCost(parentDn) : 0L;
            for (ForestEdge edge : children.getOrDefault(v, List.of())) {
                long value = INF;
                for (int cn : dns) {
                    if (cn > parentDn || cn < minDn(edge)) {
                        continue;
                    }
                    double nextRun;
                    if (cn == parentDn) {
                        nextRun = runLen + length(edge);
                        if (nextRun > maxLen(parentDn) + EPS) {
                            continue;
                        }
                    } else {
                        nextRun = length(edge);
                        if (nextRun > maxLen(cn) + EPS) {
                            continue;
                        }
                    }
                    long child = dp(childNode(edge, v), cn, nextRun);
                    if (child >= INF) {
                        continue;
                    }
                    long total = sat(edgeCost(edge, cn), child);
                    if (total < value) {
                        value = total;
                    }
                }
                if (value >= INF) {
                    byNode.put(key, INF);
                    return INF;
                }
                cost = sat(cost, value);
            }
            byNode.put(key, cost);
            return cost;
        }

        /** Восстановление выбора Ду: ребро и его поддерево. */
        private void assignChild(ForestEdge edge, String parent, int parentDn, int upperDn,
                                 double runLen, boolean fresh, Map<String, Integer> chosen) {
            long best = INF;
            int bestDn = -1;
            for (int cn : dns) {
                if (cn > upperDn || cn < minDn(edge)) {
                    continue;
                }
                double nextRun;
                if (!fresh && cn == parentDn) {
                    nextRun = runLen + length(edge);
                    if (nextRun > maxLen(parentDn) + EPS) {
                        continue;
                    }
                } else {
                    nextRun = length(edge);
                    if (nextRun > maxLen(cn) + EPS) {
                        continue;
                    }
                }
                long child = dp(childNode(edge, parent), cn, nextRun);
                if (child >= INF) {
                    continue;
                }
                long total = sat(edgeCost(edge, cn), child);
                if (total < best) {
                    best = total;
                    bestDn = cn;
                }
            }
            if (bestDn < 0) {
                return;
            }
            chosen.put(edge.getId(), bestDn);
            String child = childNode(edge, parent);
            double nextRun = (!fresh && bestDn == parentDn) ? runLen + length(edge) : length(edge);
            for (ForestEdge next : children.getOrDefault(child, List.of())) {
                assignChild(next, child, bestDn, bestDn, nextRun, false, chosen);
            }
        }

        private void buildChildren() {
            Map<String, List<ForestEdge>> adjacency = new HashMap<>();
            for (ForestEdge edge : edges) {
                adjacency.computeIfAbsent(edge.getFromNodeId(), k -> new ArrayList<>()).add(edge);
                adjacency.computeIfAbsent(edge.getToNodeId(), k -> new ArrayList<>()).add(edge);
            }
            Deque<String> queue = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            queue.add(root);
            visited.add(root);
            while (!queue.isEmpty()) {
                String node = queue.poll();
                for (ForestEdge edge : adjacency.getOrDefault(node, List.of())) {
                    String next = childNode(edge, node);
                    if (next != null && !next.equals(node) && visited.add(next)) {
                        children.computeIfAbsent(node, k -> new ArrayList<>()).add(edge);
                        queue.add(next);
                    }
                }
            }
        }

        private String childNode(ForestEdge edge, String parent) {
            if (edge.getFromNodeId().equals(parent)) {
                return edge.getToNodeId();
            }
            return edge.getFromNodeId();
        }

        private double length(ForestEdge edge) {
            return lengthCache.computeIfAbsent(edge.getId(), k -> edge.lengthM());
        }

        private double kSpecial(ForestEdge edge) {
            return kSpecialCache.computeIfAbsent(edge.getId(), k -> specialZones == null ? 1.0
                    : specialZones.maxKSpecial(edge.getCoordinates(), new ArrayList<>()));
        }

        private long edgeCost(ForestEdge edge, int dn) {
            return costModel.segmentCost(length(edge), dn, 1.0, kSpecial(edge));
        }

        private int minDn(ForestEdge edge) {
            return minDnCache.computeIfAbsent(edge.getId(), k -> {
                try {
                    return diameters.select(edge.getFlowTph()).getDn();
                } catch (IllegalArgumentException overflow) {
                    return dns[dns.length - 1];
                }
            });
        }

        private double maxLen(int dn) {
            Double value = maxLengthByDn.get(dn);
            return value != null ? value : diameters.maxLengthM(dn);
        }
    }

    private static boolean isNewChamber(ForestNode node) {
        return node != null && node.getType() == NodeType.CHAMBER && !node.isExisting();
    }

    private static long sat(long a, long b) {
        if (a >= INF || b >= INF) {
            return INF;
        }
        long sum = a + b;
        return sum < 0 ? INF : sum;
    }

    private static ForestEdge withDiameter(ForestEdge edge, int dn) {
        return ForestEdge.builder()
                .id(edge.getId())
                .fromNodeId(edge.getFromNodeId())
                .toNodeId(edge.getToNodeId())
                .coordinates(edge.getCoordinates())
                .flowTph(edge.getFlowTph())
                .diameterMm(dn)
                .build();
    }
}
