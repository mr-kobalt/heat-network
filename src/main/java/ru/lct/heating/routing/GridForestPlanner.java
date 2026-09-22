package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.ObstacleMask;
import ru.lct.heating.geometry.ObstacleMaskBuilder;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;

/**
 * Построение единого леса поиском по сетке (ADR-0034). Многоисточниковый
 * Дейкстра от клеток существующей сети даёт топологию и общие стволы; геометрия
 * рёбер уточняется string pulling с точной проверкой запретов. Консервативная
 * проходимость клеток гарантирует, что отрезки между центрами свободных клеток
 * не пересекают запретные зоны.
 */
@Component
public class GridForestPlanner {

    private static final Logger log = LoggerFactory.getLogger(GridForestPlanner.class);

    private static final double EPS = 1e-6;
    private static final double ANGLE_EPS = 1e-6;
    private static final double SQRT2 = Math.sqrt(2.0);
    private static final int[][] NEIGHBORS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private final TieInCandidateProvider candidateProvider;
    private final DiameterCatalog diameters;
    private final CostModel costModel;
    private final MaxLengthEnforcer maxLengthEnforcer;
    private final LineStringSimplifier simplifier;
    private final ObstacleMaskBuilder maskBuilder;
    private final CellStoreFactory cellStoreFactory;
    private final AppProperties appProperties;

    public GridForestPlanner(TieInCandidateProvider candidateProvider, DiameterCatalog diameters,
                             CostModel costModel, MaxLengthEnforcer maxLengthEnforcer,
                             LineStringSimplifier simplifier, ObstacleMaskBuilder maskBuilder,
                             CellStoreFactory cellStoreFactory, AppProperties appProperties) {
        this.candidateProvider = candidateProvider;
        this.diameters = diameters;
        this.costModel = costModel;
        this.maxLengthEnforcer = maxLengthEnforcer;
        this.simplifier = simplifier;
        this.maskBuilder = maskBuilder;
        this.cellStoreFactory = cellStoreFactory;
        this.appProperties = appProperties;
    }

    private double maxTurnDeg() {
        return appProperties.getForestMaxTurnDeg() > 0
                ? appProperties.getForestMaxTurnDeg() : 90.0;
    }

    private boolean hardTurn() {
        return !"warn".equalsIgnoreCase(appProperties.getForestTurnEnforcement());
    }

    private boolean exitGridDogleg() {
        return appProperties.isForestExitGridDogleg();
    }

    public ForestPlanningResult plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                     ObstacleIndex obstacleIndex, List<String> warnings,
                                     Map<String, ConnectionExit> exits) {
        List<Terminal> terminals = terminals(dataset, exits, warnings);
        List<String> unconnected = new ArrayList<>();
        Set<String> terminalIds = new HashSet<>();
        for (Terminal terminal : terminals) {
            terminalIds.add(terminal.pointId);
        }
        for (OksConnectionPointObject point : dataset.getConnectionPoints()) {
            if (!terminalIds.contains(point.getId())) {
                unconnected.add(point.getId());
            }
        }
        if (terminals.isEmpty()) {
            return result(List.of(), unconnected);
        }
        List<TieInCandidate> ties = tieCandidates(dataset, terminals);
        if (ties.isEmpty()) {
            warnings.add("FOREST_NO_TIE_IN_CANDIDATES: не найдено кандидатов врезки");
            unconnected.addAll(terminalIds);
            return result(List.of(), unconnected);
        }

        long start = System.nanoTime();
        double cell = appProperties.getForestGridCellM() > 0
                ? appProperties.getForestGridCellM() : 2.0;
        Envelope bounds = bounds(terminals, ties, cell);
        long estimatedCells = (long) Math.ceil(bounds.getWidth() / cell + 2)
                * (long) Math.ceil(bounds.getHeight() / cell + 2);
        boolean spill = cellStoreFactory.spillEnabled(estimatedCells);
        long maxBytes = spill ? Long.MAX_VALUE / 4
                : Math.max(1L, appProperties.getForestGridMaxCells() / 4);
        ObstacleMask pass = maskBuilder.buildPassability(obstacleIndex, bounds, cell, maxBytes,
                warnings);
        if (pass == null) {
            unconnected.addAll(terminalIds);
            return result(List.of(), unconnected);
        }
        log.info("Grid: cell={} size={}x{} blocked={} build={}ms storage={}", pass.cellM(),
                pass.width(), pass.height(), pass.blockedCells(), pass.buildMs(),
                spill ? "postgis" : "memory");

        Map<Integer, TiePoint> sources = mapSources(pass, ties);
        long[] reachable = reachableCells(pass, sources.keySet());
        Map<Integer, Terminal> terminalCells = mapTerminals(pass, terminals, reachable,
                new ArrayList<>(sources.values()));
        Map<String, Double> terminalFlow = new HashMap<>();
        double totalFlow = 0.0;
        for (Terminal terminal : terminals) {
            terminalFlow.put(terminal.pointId, terminal.flow);
            totalFlow += terminal.flow;
        }

        int iterations = Math.max(1, appProperties.getForestCostIterations());
        int[] dnEstimate = new int[pass.width() * pass.height()];
        Arrays.fill(dnEstimate, selectDiameter(totalFlow));
        List<ForestTree> trees = List.of();
        Set<String> connected = new HashSet<>();
        List<GridReport.Pass> passStats = new ArrayList<>();
        double bestScore = Double.POSITIVE_INFINITY;
        for (int passIndex = 0; passIndex < iterations; passIndex++) {
            long passStart = System.nanoTime();
            GridBuild build = buildTrees(pass, sources, terminalCells, obstacleIndex, warnings,
                    passIndex > 0, dnEstimate, terminalFlow);
            long passMs = elapsedMs(passStart);
            passStats.add(GridReport.Pass.builder().index(passIndex + 1).score(build.score)
                    .trees(build.trees.size()).timeMs(passMs).build());
            log.info("Grid pass {}/{}: score={} trees={} time={}ms", passIndex + 1, iterations,
                    build.score, build.trees.size(), passMs);
            if (build.score < bestScore - EPS) {
                bestScore = build.score;
                trees = build.trees;
                connected = build.connected;
            } else if (passIndex > 0) {
                break;
            }
        }

        for (Terminal terminal : terminals) {
            if (!connected.contains(terminal.pointId)) {
                unconnected.add(terminal.pointId);
            }
        }
        long totalMs = elapsedMs(start);
        log.info("Grid total: sources={} terminals={} trees={} unconnected={} time={}ms",
                sources.size(), distinctTerminals(terminalCells), trees.size(), unconnected.size(),
                totalMs);
        GridReport report = GridReport.builder()
                .cellM(pass.cellM()).width(pass.width()).height(pass.height())
                .blockedCells(pass.blockedCells()).storage(spill ? "postgis" : "memory")
                .sources(sources.size()).terminals(distinctTerminals(terminalCells))
                .trees(trees.size()).unconnected(unconnected.size()).timeMs(totalMs)
                .passes(passStats).build();
        return ForestPlanningResult.builder().trees(trees)
                .unconnectedConnectionPointIds(unconnected).gridReport(report).build();
    }

    private ForestPlanningResult result(List<ForestTree> trees, List<String> unconnected) {
        return ForestPlanningResult.builder().trees(trees)
                .unconnectedConnectionPointIds(unconnected).build();
    }

    private List<Terminal> terminals(NetworkDataset dataset, Map<String, ConnectionExit> exits,
                                     List<String> warnings) {
        List<Terminal> result = new ArrayList<>();
        for (OksConnectionPointObject point : dataset.getConnectionPoints()) {
            ConnectionExit exit = exits == null ? null : exits.get(point.getId());
            if (exit == null) {
                warnings.add("NO_FLOW: для точки подключения " + point.getId()
                        + " не найден расчётный расход");
                continue;
            }
            if (exit.isBlocked() || exit.getTarget() == null || point.getFlowTph() == null) {
                warnings.add("OKS_APPROACH_BLOCKED: для точки подключения " + point.getId()
                        + " не найден допустимый вывод из ОКС");
                continue;
            }
            List<Coordinate> tail = exit.hasTail() ? exit.getTail() : List.of();
            result.add(new Terminal(point.getId(), point.getGeometry().getCoordinate(),
                    exit.getTarget(), tail, point.getFlowTph()));
        }
        return result;
    }

    private List<TieInCandidate> tieCandidates(NetworkDataset dataset, List<Terminal> terminals) {
        List<TieInCandidate> all = new ArrayList<>(candidateProvider.candidates(dataset));
        for (Terminal terminal : terminals) {
            all.addAll(candidateProvider.projections(dataset, terminal.target));
        }
        return candidateProvider.distinct(all);
    }

    private Envelope bounds(List<Terminal> terminals, List<TieInCandidate> ties, double cell) {
        Envelope bounds = new Envelope();
        for (Terminal terminal : terminals) {
            bounds.expandToInclude(terminal.target);
        }
        for (TieInCandidate tie : ties) {
            bounds.expandToInclude(tie.getCoordinate());
        }
        bounds.expandBy(Math.max(cell * 2.0, 10.0));
        return bounds;
    }

    private Map<Integer, TiePoint> mapSources(ObstacleMask pass, List<TieInCandidate> ties) {
        Map<Integer, TiePoint> sources = new LinkedHashMap<>();
        for (TieInCandidate tie : ties) {
            int cell = nearestFreeCell(pass, tie.getCoordinate(), null);
            if (cell < 0) {
                continue;
            }
            String chamberId = "heat_chamber".equals(tie.getExistingObjectType())
                    ? tie.getExistingObjectId() : null;
            TiePoint existing = sources.get(cell);
            if (existing == null || (existing.chamberId == null && chamberId != null)) {
                sources.put(cell, new TiePoint(tie.getCoordinate(), chamberId));
            }
        }
        return sources;
    }

    private Map<Integer, Terminal> mapTerminals(ObstacleMask pass, List<Terminal> terminals,
                                                long[] reachable, List<TiePoint> sources) {
        Map<Integer, Terminal> cells = new LinkedHashMap<>();
        Set<Integer> used = new HashSet<>();
        int count = multiEntry() ? entryCellCount() : 1;
        for (Terminal terminal : terminals) {
            List<Integer> entry = pickTerminalCells(pass, terminal.target, used, reachable, sources,
                    count);
            if (entry.isEmpty()) {
                continue;
            }
            terminal.entryCells = entry;
            for (int cell : entry) {
                cells.putIfAbsent(cell, terminal);
            }
        }
        return cells;
    }

    private int distinctTerminals(Map<Integer, Terminal> terminalCells) {
        return new LinkedHashSet<>(terminalCells.values()).size();
    }

    /**
     * ADR-0035: клетки-кандидаты входа терминала. Первичная — по правилу
     * {@code forest-terminal-cell-search}; при мультивходе добавляются ближайшие
     * свободные клетки разных секторов вокруг {@code target}.
     */
    private List<Integer> pickTerminalCells(ObstacleMask pass, Coordinate coordinate,
                                            Set<Integer> used, long[] reachable,
                                            List<TiePoint> sources, int count) {
        int width = pass.width();
        int col = pass.colOf(coordinate.x);
        int row = pass.rowOf(coordinate.y);
        if (!multiEntry() && "raster".equalsIgnoreCase(cellSearch())) {
            int cell = nearestFreeCell(pass, coordinate, used, reachable);
            if (cell < 0) {
                return List.of();
            }
            used.add(cell);
            return List.of(cell);
        }
        if (isFree(pass, col, row, used, reachable)) {
            int cell = row * width + col;
            used.add(cell);
            return List.of(cell);
        }
        List<Integer> cells = new ArrayList<>();
        int maxRing = 128;
        for (int ring = 1; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) {
                        continue;
                    }
                    int c = col + dx;
                    int r = row + dy;
                    if (c < 0 || r < 0 || c >= pass.width() || r >= pass.height()) {
                        continue;
                    }
                    if (isFree(pass, c, r, used, reachable)) {
                        cells.add(r * width + c);
                    }
                }
            }
            if (cells.size() >= count * 4 && ring >= 2) {
                break;
            }
            if (cells.size() >= count && ring >= 3) {
                break;
            }
        }
        if (cells.isEmpty()) {
            return List.of();
        }
        final double cx = coordinate.x;
        final double cy = coordinate.y;
        cells.sort(Comparator.comparingDouble(cell -> {
            double x = pass.centerX(cell % width) - cx;
            double y = pass.centerY(cell / width) - cy;
            return x * x + y * y;
        }));
        List<Integer> chosen = new ArrayList<>();
        chosen.add(choosePrimaryCell(pass, cells, coordinate, sources));
        if (count > 1) {
            boolean[] sectors = new boolean[16];
            sectors[sectorOf(pass, chosen.get(0), coordinate)] = true;
            for (int cell : cells) {
                if (chosen.size() >= count) {
                    break;
                }
                if (chosen.contains(cell)) {
                    continue;
                }
                int sector = sectorOf(pass, cell, coordinate);
                if (sectors[sector]) {
                    continue;
                }
                sectors[sector] = true;
                chosen.add(cell);
            }
            for (int cell : cells) {
                if (chosen.size() >= count) {
                    break;
                }
                if (!chosen.contains(cell)) {
                    chosen.add(cell);
                }
            }
        }
        used.add(chosen.get(0));
        return chosen;
    }

    private int choosePrimaryCell(ObstacleMask pass, List<Integer> sorted, Coordinate coordinate,
                                  List<TiePoint> sources) {
        if (!"toward-network".equalsIgnoreCase(cellSearch()) || sources.isEmpty()
                || sorted.size() < 2) {
            return sorted.get(0);
        }
        double sx = 0.0;
        double sy = 0.0;
        for (TiePoint tie : sources) {
            sx += tie.coordinate.x;
            sy += tie.coordinate.y;
        }
        sx /= sources.size();
        sy /= sources.size();
        double vx = sx - coordinate.x;
        double vy = sy - coordinate.y;
        double vlen = Math.hypot(vx, vy);
        if (vlen < EPS) {
            return sorted.get(0);
        }
        double nearest = distance(pass, sorted.get(0), coordinate);
        double tolerance = pass.cellM();
        int best = sorted.get(0);
        double bestAlign = Double.NEGATIVE_INFINITY;
        for (int cell : sorted) {
            if (distance(pass, cell, coordinate) > nearest + tolerance) {
                break;
            }
            double x = pass.centerX(cell % pass.width()) - coordinate.x;
            double y = pass.centerY(cell / pass.width()) - coordinate.y;
            double align = (x * vx + y * vy) / (Math.hypot(x, y) * vlen + EPS);
            if (align > bestAlign) {
                bestAlign = align;
                best = cell;
            }
        }
        return best;
    }

    private double distance(ObstacleMask pass, int cell, Coordinate coordinate) {
        double x = pass.centerX(cell % pass.width()) - coordinate.x;
        double y = pass.centerY(cell / pass.width()) - coordinate.y;
        return Math.hypot(x, y);
    }

    private int sectorOf(ObstacleMask pass, int cell, Coordinate coordinate) {
        double x = pass.centerX(cell % pass.width()) - coordinate.x;
        double y = pass.centerY(cell / pass.width()) - coordinate.y;
        double angle = (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0;
        return (int) (angle / 22.5) % 16;
    }

    private boolean multiEntry() {
        return appProperties.isForestTerminalMultiEntry();
    }

    private int entryCellCount() {
        return Math.max(1, appProperties.getForestTerminalEntryCells());
    }

    private String cellSearch() {
        return appProperties.getForestTerminalCellSearch();
    }


    private int nearestFreeCell(ObstacleMask pass, Coordinate coordinate, Set<Integer> used) {
        return nearestFreeCell(pass, coordinate, used, null);
    }

    private int nearestFreeCell(ObstacleMask pass, Coordinate coordinate, Set<Integer> used,
                                long[] reachable) {
        int col = pass.colOf(coordinate.x);
        int row = pass.rowOf(coordinate.y);
        if (isFree(pass, col, row, used, reachable)) {
            return row * pass.width() + col;
        }
        for (int ring = 1; ring <= 128; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dy = -ring; dy <= ring; dy++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) {
                        continue;
                    }
                    int c = col + dx;
                    int r = row + dy;
                    if (c < 0 || r < 0 || c >= pass.width() || r >= pass.height()) {
                        continue;
                    }
                    if (isFree(pass, c, r, used, reachable)) {
                        return r * pass.width() + c;
                    }
                }
            }
        }
        return -1;
    }

    private boolean isFree(ObstacleMask pass, int col, int row, Set<Integer> used) {
        return isFree(pass, col, row, used, null);
    }

    private boolean isFree(ObstacleMask pass, int col, int row, Set<Integer> used,
                           long[] reachable) {
        if (pass.blockedCell(col, row)) {
            return false;
        }
        int cell = row * pass.width() + col;
        if (used != null && used.contains(cell)) {
            return false;
        }
        return reachable == null || (reachable[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    /**
     * Связность с сетью (8-связность): битсет клеток, достижимых от источников
     * без пересечения запретов. Терминал подключается только к достижимой
     * клетке, иначе его клетка могла бы оказаться в изолированном «кармане».
     */
    private long[] reachableCells(ObstacleMask pass, Set<Integer> sources) {
        int width = pass.width();
        int height = pass.height();
        int n = width * height;
        long[] reachable = new long[(n + 63) / 64];
        int[] queue = new int[Math.min(n, 1 << 20) + 1];
        int head = 0;
        int tail = 0;
        for (Integer source : sources) {
            if ((reachable[source >>> 6] & (1L << (source & 63))) != 0) {
                continue;
            }
            reachable[source >>> 6] |= 1L << (source & 63);
            if (tail == queue.length) {
                queue = Arrays.copyOf(queue, Math.min(n + 1, queue.length * 2));
            }
            queue[tail++] = source;
        }
        while (head < tail) {
            int cell = queue[head++];
            int col = cell % width;
            int row = cell / width;
            for (int[] step : NEIGHBORS) {
                int nc = col + step[0];
                int nr = row + step[1];
                if (nc < 0 || nr < 0 || nc >= width || nr >= height || pass.blockedCell(nc, nr)) {
                    continue;
                }
                if (step[0] != 0 && step[1] != 0
                        && (pass.blockedCell(col, nr) || pass.blockedCell(nc, row))) {
                    continue;
                }
                int next = nr * width + nc;
                if ((reachable[next >>> 6] & (1L << (next & 63))) != 0) {
                    continue;
                }
                reachable[next >>> 6] |= 1L << (next & 63);
                if (tail == queue.length) {
                    queue = Arrays.copyOf(queue, Math.min(n + 1, Math.max(1024, queue.length * 2)));
                }
                queue[tail++] = next;
            }
        }
        return reachable;
    }

    private GridBuild buildTrees(ObstacleMask pass, Map<Integer, TiePoint> sources,
                                 Map<Integer, Terminal> terminalCells, ObstacleIndex obstacleIndex,
                                 List<String> warnings, boolean costWeighted, int[] dnEstimate,
                                 Map<String, Double> terminalFlow) {
        int width = pass.width();
        int height = pass.height();
        int n = width * height;
        CellStore store = cellStoreFactory.create(n, warnings);
        try {
            for (Terminal terminal : new LinkedHashSet<>(terminalCells.values())) {
                terminal.connected = false;
                terminal.startCell = null;
            }
            CellHeap heap = new CellHeap(store);
            double totalFlow = terminalFlow.values().stream()
                    .mapToDouble(Double::doubleValue).sum();
            int baseDn = selectDiameter(totalFlow);
            for (Map.Entry<Integer, TiePoint> entry : sources.entrySet()) {
                int cell = entry.getKey();
                store.setInForest(cell, true);
                double initial = 0.0;
                if (costWeighted) {
                    long tieCost = entry.getValue().chamberId != null
                            ? costModel.existingChamberTieInCost()
                            : costModel.chamberCost(baseDn);
                    initial = costModel.score(tieCost, 0.0);
                }
                store.setDist(cell, initial);
                heap.push(cell);
            }
            // Инкрементальный рост (Prim-подобный): терминалы подключаются в
            // порядке возрастания расстояния до текущего леса; промежуточные
            // клетки пути становятся частью леса (T-присоединение).
            int remaining = distinctTerminals(terminalCells);
            while (!heap.isEmpty() && remaining > 0) {
                int current = heap.pop();
                if (store.settled(current)) {
                    continue;
                }
                store.setSettled(current, true);
                Terminal hit = terminalCells.get(current);
                if (hit != null && !hit.connected) {
                    int node = current;
                    List<Integer> path = new ArrayList<>();
                    Set<Integer> visited = new HashSet<>();
                    while (node != -1 && !store.inForest(node) && visited.add(node)) {
                        path.add(node);
                        node = store.parent(node);
                    }
                    if (node == -1 || !store.inForest(node)) {
                        warnings.add("FOREST_PARENT_CHAIN_BROKEN: точка "
                                + hit.pointId);
                        continue;
                    }
                    for (int cell : path) {
                        if (cell == current) {
                            continue;
                        }
                        store.setInForest(cell, true);
                        store.setSettled(cell, false);
                        store.setDist(cell, 0.0);
                        heap.push(cell);
                    }
                    hit.connected = true;
                    hit.startCell = current;
                    remaining--;
                    continue;
                }
                int col = current % width;
                int row = current / width;
                for (int[] step : NEIGHBORS) {
                    int nc = col + step[0];
                    int nr = row + step[1];
                    if (nc < 0 || nr < 0 || nc >= width || nr >= height
                            || pass.blockedCell(nc, nr)) {
                        continue;
                    }
                    if (step[0] != 0 && step[1] != 0
                            && (pass.blockedCell(col, nr) || pass.blockedCell(nc, row))) {
                        continue;
                    }
                int next = nr * width + nc;
                if (store.settled(next)) {
                    continue;
                }
                int parentCell = store.parent(current);
                if (hardTurn() && parentCell != -1) {
                    int pcol = parentCell % width;
                    int prow = parentCell / width;
                    if (turnDegrees(col - pcol, row - prow, step[0], step[1])
                            > maxTurnDeg() + ANGLE_EPS) {
                        continue;
                    }
                }
                double length = step[0] != 0 && step[1] != 0
                        ? pass.cellM() * SQRT2 : pass.cellM();
                    double weight = length;
                    if (costWeighted) {
                        int dn = Math.max(dnEstimate[current], dnEstimate[next]);
                        weight = costModel.score(Math.round(length * diameters.newCostPerM(dn)),
                                length);
                    }
                    double candidate = store.dist(current) + weight;
                    if (candidate < store.dist(next) - EPS) {
                        store.setDist(next, candidate);
                        store.setParent(next, current);
                        heap.push(next);
                    }
                }
            }

            Map<Integer, List<Terminal>> terminalsByCell = new HashMap<>();
            Map<Integer, Terminal> connectedTerminalCells = new HashMap<>();
            for (Terminal term : new LinkedHashSet<>(terminalCells.values())) {
                if (term.connected && term.startCell != null) {
                    terminalsByCell.computeIfAbsent(term.startCell, key -> new ArrayList<>())
                            .add(term);
                    connectedTerminalCells.put(term.startCell, term);
                }
            }
            Set<Integer> treeCells = new LinkedHashSet<>();
            Map<Integer, Integer> parentMap = new HashMap<>();
            for (Terminal term : connectedTerminalCells.values()) {
                int current = term.startCell;
                while (current != -1 && treeCells.add(current)) {
                    int parent = store.parent(current);
                    parentMap.put(current, parent);
                    current = parent;
                }
            }
            Map<Integer, List<Integer>> children = new HashMap<>();
            Map<Integer, Integer> childCount = new HashMap<>();
            for (int cell : treeCells) {
                int parent = parentMap.get(cell);
                if (parent != -1) {
                    childCount.merge(parent, 1, Integer::sum);
                    children.computeIfAbsent(parent, key -> new ArrayList<>()).add(cell);
                }
            }
            Map<Integer, Double> flow = new HashMap<>();
            List<Integer> order = new ArrayList<>();
            java.util.Deque<Integer> queue = new java.util.ArrayDeque<>();
            for (int cell : treeCells) {
                if (parentMap.get(cell) == -1) {
                    queue.add(cell);
                }
            }
            while (!queue.isEmpty()) {
                int cell = queue.poll();
                order.add(cell);
                for (int child : children.getOrDefault(cell, List.of())) {
                    queue.add(child);
                }
            }
            for (int i = order.size() - 1; i >= 0; i--) {
                int cell = order.get(i);
                double accumulated = 0.0;
                for (Terminal term : terminalsByCell.getOrDefault(cell, List.of())) {
                    accumulated += term.flow;
                }
                for (int child : children.getOrDefault(cell, List.of())) {
                    accumulated += flow.getOrDefault(child, 0.0);
                }
                flow.put(cell, accumulated);
            }

            Map<Integer, Integer> rootCache = new HashMap<>();
            Map<Integer, TiePoint> rootTies = new HashMap<>();
            Map<Integer, List<Integer>> topologyByRoot = new LinkedHashMap<>();
            for (int cell : treeCells) {
                int root = rootOf(cell, parentMap, rootCache);
                if (parentMap.get(cell) == -1) {
                    rootTies.put(root, sources.get(root));
                }
                if (isTopology(cell, parentMap, childCount, terminalsByCell)) {
                    topologyByRoot.computeIfAbsent(root, key -> new ArrayList<>()).add(cell);
                }
            }

            List<ForestTree> trees = new ArrayList<>();
            int treeIndex = 0;
            for (Map.Entry<Integer, List<Integer>> entry : topologyByRoot.entrySet()) {
                int root = entry.getKey();
                TiePoint tie = rootTies.get(root);
                if (tie == null) {
                    continue;
                }
                List<Integer> topology = entry.getValue();
                topology.sort(Comparator.naturalOrder());
                Map<Integer, String> ids = assignIds(topology, root, tie, connectedTerminalCells,
                        treeIndex);
                ForestTree tree = buildTree(treeIndex, root, tie, topology, ids, pass, children,
                        childCount, parentMap, terminalsByCell, flow, obstacleIndex, warnings,
                        dnEstimate);
                if (tree != null) {
                    trees.add(tree);
                }
                treeIndex++;
            }

            if (appProperties.isForestReattachPass() && !trees.isEmpty()) {
                Map<String, ConnectionExit> exits = new HashMap<>();
                Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>> own =
                        new HashMap<>();
                for (Terminal term : new LinkedHashSet<>(terminalCells.values())) {
                    if (term.connected) {
                        exits.put(term.pointId, ConnectionExit.builder()
                                .connectionPointId(term.pointId).target(term.target)
                                .tail(term.tail).blocked(false).build());
                        if (obstacleIndex != null) {
                            own.put(term.pointId, obstacleIndex.obstaclesContaining(
                                    GeometrySupport.GEOMETRY_FACTORY
                                            .createPoint(term.point)));
                        }
                    }
                }
                trees = new TerminalRelinker(costModel, diameters, appProperties)
                        .relink(trees, exits, terminalFlow, obstacleIndex, own);
            }
            Set<String> connected = new HashSet<>();
            List<String> unconnected = new ArrayList<>();
            for (Terminal term : new LinkedHashSet<>(terminalCells.values())) {
                if (term.connected) {
                    connected.add(term.pointId);
                } else {
                    unconnected.add(term.pointId);
                }
            }
            double score = estimateScore(trees, unconnected, terminalFlow);
            return new GridBuild(trees, score, connected);
        } finally {
            store.close();
        }
    }

    private void updateEstimate(int[] dnEstimate, int parentCell, Chain chain, int dn) {
        dnEstimate[parentCell] = Math.max(dnEstimate[parentCell], dn);
        for (int cell : chain.intermediate) {
            dnEstimate[cell] = Math.max(dnEstimate[cell], dn);
        }
        dnEstimate[chain.end] = Math.max(dnEstimate[chain.end], dn);
    }

    private double estimateScore(List<ForestTree> trees, List<String> unconnected,
                                 Map<String, Double> terminalFlow) {
        long cost = 0L;
        double length = 0.0;
        for (ForestTree tree : trees) {
            Map<String, Integer> maxIncident = new HashMap<>();
            Map<String, Integer> outgoing = new HashMap<>();
            for (ForestEdge edge : tree.getEdges()) {
                double edgeLength = edge.lengthM();
                cost += costModel.segmentCost(edgeLength, edge.getDiameterMm(), 1.0, 1.0);
                length += edgeLength;
                maxIncident.merge(edge.getFromNodeId(), edge.getDiameterMm(), Math::max);
                maxIncident.merge(edge.getToNodeId(), edge.getDiameterMm(), Math::max);
                outgoing.merge(edge.getFromNodeId(), 1, Integer::sum);
            }
            for (ForestNode node : tree.getNodes().values()) {
                if (node.getType() != NodeType.CHAMBER) {
                    continue;
                }
                if (node.isExisting()) {
                    cost += (long) outgoing.getOrDefault(node.getId(), 0)
                            * costModel.existingChamberTieInCost();
                } else {
                    cost += costModel.chamberCost(maxIncident.getOrDefault(node.getId(), 0));
                }
            }
        }
        for (String id : unconnected) {
            cost += costModel.unconnectedPenalty(terminalFlow.getOrDefault(id, 0.0));
        }
        return costModel.score(cost, length);
    }

    private boolean isTopology(int cell, Map<Integer, Integer> parent,
                               Map<Integer, Integer> childCount,
                               Map<Integer, List<Terminal>> terminalsByCell) {
        return parent.get(cell) == -1 || terminalsByCell.containsKey(cell)
                || childCount.getOrDefault(cell, 0) >= 2;
    }

    private Map<Integer, String> assignIds(List<Integer> topology, int root, TiePoint tie,
                                           Map<Integer, Terminal> terminalCells, int treeIndex) {
        Map<Integer, String> ids = new HashMap<>();
        int junction = 0;
        for (int cell : topology) {
            Terminal term = terminalCells.get(cell);
            if (cell == root) {
                ids.put(cell, tie.chamberId != null ? tie.chamberId : "ch_" + treeIndex);
            } else if (term != null) {
                ids.put(cell, term.pointId);
            } else {
                ids.put(cell, "br_" + treeIndex + "_" + junction++);
            }
        }
        return ids;
    }

    private int rootOf(int cell, Map<Integer, Integer> parent, Map<Integer, Integer> cache) {
        Integer known = cache.get(cell);
        if (known != null) {
            return known;
        }
        int current = cell;
        int guard = parent.size() + 1;
        while (parent.getOrDefault(current, -1) != -1 && guard-- > 0) {
            current = parent.get(current);
        }
        cache.put(cell, current);
        return current;
    }

    private Terminal terminal(Map<Integer, List<Terminal>> terminalsByCell, int cell) {
        List<Terminal> list = terminalsByCell.get(cell);
        return list == null || list.isEmpty() ? null : list.get(0);
    }

    private ForestTree buildTree(int treeIndex, int root, TiePoint tie, List<Integer> topology,
                                 Map<Integer, String> ids, ObstacleMask pass,
                                 Map<Integer, List<Integer>> children,
                                 Map<Integer, Integer> childCount, Map<Integer, Integer> parent,
                                 Map<Integer, List<Terminal>> terminalsByCell,
                                 Map<Integer, Double> flow, ObstacleIndex obstacleIndex,
                                 List<String> warnings, int[] dnEstimate) {
        Map<String, ForestNode> nodes = new HashMap<>();
        for (int cell : topology) {
            String id = ids.get(cell);
            Terminal term = terminal(terminalsByCell, cell);
            boolean existing = cell == root && tie.chamberId != null;
            Coordinate coordinate = cell == root ? tie.coordinate
                    : (term != null ? term.point : center(pass, cell));
            nodes.put(id, ForestNode.builder()
                    .id(id)
                    .type(term == null ? NodeType.CHAMBER : NodeType.CONNECTION_POINT)
                    .coordinate(coordinate)
                    .existing(existing)
                    .existingObjectId(existing ? tie.chamberId : null)
                    .build());
        }

        List<ForestEdge> edges = new ArrayList<>();
        int index = 0;
        for (int cell : topology) {
            String fromId = ids.get(cell);
            if (terminal(terminalsByCell, cell) != null && cell != root) {
                continue;
            }
            for (int child : children.getOrDefault(cell, List.of())) {
                Chain chain = walkChain(child, parent, children, childCount, terminalsByCell);
                if (chain == null || !ids.containsKey(chain.end)) {
                    continue;
                }
                List<Coordinate> trunk = new ArrayList<>();
                trunk.add(cell == root ? tie.coordinate : center(pass, cell));
                for (int intermediate : chain.intermediate) {
                    trunk.add(center(pass, intermediate));
                }
                trunk.add(center(pass, chain.end));
                Coordinate startPrevious = null;
                Integer parentCell = parent.get(cell);
                if (parentCell != null && parentCell != -1) {
                    startPrevious = center(pass, parentCell);
                }
                Coordinate endNext = null;
                if (chain.terminal != null) {
                    if (!chain.terminal.tail.isEmpty()) {
                        endNext = chain.terminal.point;
                    }
                    trunk.add(chain.terminal.target);
                }
                List<Coordinate> coordinates = new ArrayList<>(refine(trunk, obstacleIndex,
                        startPrevious, endNext, chain.terminal != null));
                if (chain.terminal != null && !chain.terminal.tail.isEmpty()) {
                    coordinates.add(chain.terminal.point);
                }
                if (chain.terminal != null && exitGridDogleg()) {
                    coordinates = rebuildExitJoint(coordinates, pass, obstacleIndex);
                }
                if (!turnsWithinLimit(coordinates, coordinates.size() - 2 - (chain.terminal != null
                        ? 2 : 0))) {
                    warnings.add("FOREST_TURN_UNRESOLVED: участок e_" + treeIndex + "_" + index);
                }
                double edgeFlow = flow.getOrDefault(chain.end, 0.0);
                int dn = selectDiameter(edgeFlow);
                updateEstimate(dnEstimate, cell, chain, dn);
                edges.add(ForestEdge.builder()
                        .id("e_" + treeIndex + "_" + index++)
                        .fromNodeId(fromId)
                        .toNodeId(ids.get(chain.end))
                        .coordinates(coordinates)
                        .flowTph(edgeFlow)
                        .diameterMm(dn)
                        .build());
            }
        }
        if (edges.isEmpty()) {
            return null;
        }
        try {
            edges = maxLengthEnforcer.enforce(edges, ids.get(root));
        } catch (IllegalArgumentException noDiameter) {
            warnings.add("FOREST_MAX_LENGTH_UNRESOLVED: " + noDiameter.getMessage());
        }
        return ForestTree.builder().tieInNodeId(ids.get(root)).nodes(nodes).edges(edges).build();
    }

    private Chain walkChain(int child, Map<Integer, Integer> parent,
                            Map<Integer, List<Integer>> children, Map<Integer, Integer> childCount,
                            Map<Integer, List<Terminal>> terminalsByCell) {
        List<Integer> intermediate = new ArrayList<>();
        int current = child;
        while (!isTopology(current, parent, childCount, terminalsByCell)) {
            intermediate.add(current);
            List<Integer> next = children.get(current);
            if (next == null || next.size() != 1) {
                return null;
            }
            current = next.get(0);
        }
        return new Chain(current, intermediate, terminal(terminalsByCell, current));
    }

    /**
     * Уточнение ствола (string pulling) с точной проверкой запретов и
     * ограничением угла поворота (FR-34). {@code endNext} — направление
     * финального вывода, чтобы угол на стыке тоже был ≤ {@code maxTurnDeg}.
     */
    private List<Coordinate> refine(List<Coordinate> coordinates, ObstacleIndex index,
                                    Coordinate startPrevious, Coordinate endNext,
                                    boolean terminal) {
        List<Coordinate> unique;
        if (endNext != null && coordinates.size() >= 2) {
            // Финальный вывод (target) не упрощаем, чтобы не потерять направление.
            Coordinate end = coordinates.get(coordinates.size() - 1);
            unique = simplifier.simplify(
                    new ArrayList<>(coordinates.subList(0, coordinates.size() - 1)));
            unique.add(end);
        } else {
            unique = simplifier.simplify(coordinates);
        }
        if (unique.size() <= 1) {
            return unique;
        }
        List<Coordinate> result = new ArrayList<>();
        result.add(unique.get(0));
        int current = 0;
        while (current < unique.size() - 1) {
            int next = current + 1;
            for (int candidate = unique.size() - 1; candidate > current + 1; candidate--) {
                Coordinate vertex = unique.get(current);
                Coordinate target = unique.get(candidate);
                if (index != null && index.isInteriorBlocked(line(vertex, target))) {
                    continue;
                }
                Coordinate previous = result.size() >= 2
                        ? result.get(result.size() - 2) : startPrevious;
                if (!turnAllowed(previous, vertex, target)) {
                    continue;
                }
                if (candidate == unique.size() - 1 && endNext != null
                        && !turnAllowed(vertex, target, endNext)) {
                    continue;
                }
                next = candidate;
                break;
            }
            result.add(unique.get(next));
            current = next;
        }
        if (!hardTurn()) {
            return result;
        }
        if (turnsWithinLimit(result, terminal ? result.size() - 3 : result.size() - 2)) {
            return result;
        }
        // Локальный ремонт: спрямление сохраняется везде, кроме окрестности
        // недопустимого поворота (стык вывода обрабатывается отдельно).
        return repairLocalTurns(result, unique, terminal);
    }

    private List<Coordinate> repairLocalTurns(List<Coordinate> result, List<Coordinate> unique,
                                              boolean terminal) {
        Map<Coordinate, Integer> indexOf = new HashMap<>();
        for (int i = 0; i < unique.size(); i++) {
            indexOf.putIfAbsent(unique.get(i), i);
        }
        for (int pass = 0; pass < localPasses(); pass++) {
            int scanLast = terminal ? result.size() - 3 : result.size() - 2;
            int violation = firstViolation(result, scanLast);
            if (violation < 0) {
                break;
            }
            Integer from = indexOf.get(result.get(violation - 1));
            Integer to = indexOf.get(result.get(violation + 1));
            if (from == null || to == null || to <= from + 1) {
                break;
            }
            List<Coordinate> repaired = new ArrayList<>(result.subList(0, violation));
            for (int k = from + 1; k < to; k++) {
                repaired.add(unique.get(k));
            }
            repaired.addAll(result.subList(violation + 1, result.size()));
            result = repaired;
        }
        return result;
    }

    private int firstViolation(List<Coordinate> coordinates, int lastIndex) {
        for (int i = 1; i <= lastIndex && i < coordinates.size() - 1; i++) {
            if (!turnAllowed(coordinates.get(i - 1), coordinates.get(i), coordinates.get(i + 1))) {
                return i;
            }
        }
        return -1;
    }

    private int localPasses() {
        return Math.max(1, appProperties.getForestRefineLocalPasses());
    }

    /**
     * Локальный заход на выход: если последний поворот у точки подключения
     * превышает предел, вставляем промежуточную вершину по сетке (8 соседей),
     * заменяя один большой поворот двумя допустимыми. Если не удаётся (запрет) —
     * стык остаётся (документированный случай, ADR-0034).
     */
    private List<Coordinate> rebuildExitJoint(List<Coordinate> coordinates, ObstacleMask pass,
                                              ObstacleIndex index) {
        int last = coordinates.size() - 1;
        for (int b = last - 1; b >= 1 && b >= last - 2; b--) {
            Coordinate a = coordinates.get(b - 1);
            Coordinate mid = coordinates.get(b);
            Coordinate c = coordinates.get(b + 1);
            if (turnAllowed(a, mid, c)) {
                continue;
            }
            Coordinate q = bestGridVia(coordinates, b, pass, index);
            if (q == null) {
                continue;
            }
            List<Coordinate> repaired = new ArrayList<>(coordinates.subList(0, b));
            repaired.add(q);
            repaired.addAll(coordinates.subList(b, coordinates.size()));
            return repaired;
        }
        return coordinates;
    }

    private Coordinate bestGridVia(List<Coordinate> coordinates, int bIndex, ObstacleMask pass,
                                   ObstacleIndex index) {
        Coordinate a = coordinates.get(bIndex - 1);
        Coordinate b = coordinates.get(bIndex);
        Coordinate c = coordinates.get(bIndex + 1);
        Coordinate aPrevious = bIndex >= 2 ? coordinates.get(bIndex - 2) : null;
        double cell = pass.cellM();
        Coordinate best = null;
        double bestExtra = Double.POSITIVE_INFINITY;
        for (int[] step : NEIGHBORS) {
            for (Coordinate base : new Coordinate[]{a, b}) {
                Coordinate q = new Coordinate(base.x + step[0] * cell, base.y + step[1] * cell);
                if (q.equals2D(a) || q.equals2D(b)) {
                    continue;
                }
                if (!turnAllowed(aPrevious, a, q) || !turnAllowed(a, q, b)
                        || !turnAllowed(q, b, c)) {
                    continue;
                }
                if (index != null && (index.isInteriorBlocked(line(a, q))
                        || index.isInteriorBlocked(line(q, b)))) {
                    continue;
                }
                double extra = q.distance(a) + q.distance(b) - a.distance(b);
                if (extra < bestExtra) {
                    bestExtra = extra;
                    best = q;
                }
            }
        }
        return best;
    }

    private boolean turnAllowed(Coordinate previous, Coordinate vertex, Coordinate next) {
        if (previous == null || vertex == null || next == null) {
            return true;
        }
        double inX = vertex.x - previous.x;
        double inY = vertex.y - previous.y;
        double outX = next.x - vertex.x;
        double outY = next.y - vertex.y;
        return turnDegrees(inX, inY, outX, outY) <= maxTurnDeg() + ANGLE_EPS;
    }

    private double turnDegrees(double inX, double inY, double outX, double outY) {
        double dot = inX * outX + inY * outY;
        double cross = inX * outY - inY * outX;
        if (dot == 0.0 && cross == 0.0) {
            return 0.0;
        }
        return Math.toDegrees(Math.atan2(Math.abs(cross), dot));
    }

    private boolean turnsWithinLimit(List<Coordinate> coordinates) {
        return turnsWithinLimit(coordinates, coordinates.size() - 2);
    }

    private boolean turnsWithinLimit(List<Coordinate> coordinates, int lastIndex) {
        for (int i = 1; i <= lastIndex && i < coordinates.size() - 1; i++) {
            if (!turnAllowed(coordinates.get(i - 1), coordinates.get(i), coordinates.get(i + 1))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Если угол на финальном выводе нарушен, приближаемся к {@code target},
     * убирая вершину перед ним (до 3 попыток).
     */
    private List<Coordinate> relaxApproach(List<Coordinate> coordinates, Terminal terminal) {
        if (terminal == null) {
            return coordinates;
        }
        List<Coordinate> candidate = new ArrayList<>(coordinates);
        for (int attempt = 0; attempt < 3 && !turnsWithinLimit(candidate); attempt++) {
            int targetIndex = terminal.tail.isEmpty()
                    ? candidate.size() - 1 : candidate.size() - 2;
            if (targetIndex - 1 <= 0) {
                break;
            }
            candidate.remove(targetIndex - 1);
        }
        return candidate;
    }

    private Coordinate center(ObstacleMask pass, int cell) {
        int col = cell % pass.width();
        int row = cell / pass.width();
        return new Coordinate(pass.centerX(col), pass.centerY(row));
    }

    private static final class Chain {
        private final int end;
        private final List<Integer> intermediate;
        private final Terminal terminal;

        private Chain(int end, List<Integer> intermediate, Terminal terminal) {
            this.end = end;
            this.intermediate = intermediate;
            this.terminal = terminal;
        }
    }

    private int selectDiameter(double flow) {
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

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private static final class Terminal {
        private final String pointId;
        private final Coordinate point;
        private final Coordinate target;
        private final List<Coordinate> tail;
        private final double flow;
        private Integer startCell;
        private List<Integer> entryCells = List.of();
        private boolean connected;

        private Terminal(String pointId, Coordinate point, Coordinate target,
                         List<Coordinate> tail, double flow) {
            this.pointId = pointId;
            this.point = point;
            this.target = target;
            this.tail = tail;
            this.flow = flow;
        }
    }

    private static final class TiePoint {
        private final Coordinate coordinate;
        private final String chamberId;

        private TiePoint(Coordinate coordinate, String chamberId) {
            this.coordinate = coordinate;
            this.chamberId = chamberId;
        }
    }

    private static final class GridBuild {
        private final List<ForestTree> trees;
        private final double score;
        private final Set<String> connected;

        private GridBuild(List<ForestTree> trees, double score, Set<String> connected) {
            this.trees = trees;
            this.score = score;
            this.connected = connected;
        }
    }

    private static final class CellHeap {
        private int[] heap;
        private final CellStore store;
        private int size;

        private CellHeap(CellStore store) {
            this.store = store;
            this.heap = new int[1024];
        }

        private boolean isEmpty() {
            return size == 0;
        }

        private void push(int cell) {
            if (size == heap.length) {
                heap = Arrays.copyOf(heap, heap.length * 2);
            }
            heap[size] = cell;
            int index = size++;
            while (index > 0) {
                int parent = (index - 1) / 2;
                if (store.dist(heap[parent]) <= store.dist(heap[index])) {
                    break;
                }
                swap(parent, index);
                index = parent;
            }
        }

        private int pop() {
            int top = heap[0];
            size--;
            if (size > 0) {
                heap[0] = heap[size];
                int index = 0;
                while (true) {
                    int left = 2 * index + 1;
                    int right = left + 1;
                    int smallest = index;
                    if (left < size && store.dist(heap[left]) < store.dist(heap[smallest])) {
                        smallest = left;
                    }
                    if (right < size && store.dist(heap[right]) < store.dist(heap[smallest])) {
                        smallest = right;
                    }
                    if (smallest == index) {
                        break;
                    }
                    swap(index, smallest);
                    index = smallest;
                }
            }
            return top;
        }

        private void swap(int first, int second) {
            int value = heap[first];
            heap[first] = heap[second];
            heap[second] = value;
        }
    }
}
