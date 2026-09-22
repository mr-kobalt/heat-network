package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import ru.lct.heating.geometry.GridShape;
import ru.lct.heating.geometry.HexGridShape;
import ru.lct.heating.geometry.SquareGridShape;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;
import ru.lct.heating.trace.GridMaskCodec;
import ru.lct.heating.trace.GridMaskPayload;
import ru.lct.heating.trace.StageFeature;
import ru.lct.heating.trace.StageTrace;

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
    /** Предел числа планов-вариантов (FR-75, ADR-0037). */
    private static final int MAX_PLANS = 3;

    private final TieInCandidateProvider candidateProvider;
    private final DiameterCatalog diameters;
    private final CostModel costModel;
    private final MaxLengthEnforcer maxLengthEnforcer;
    private final LineStringSimplifier simplifier;
    private final ObstacleMaskBuilder maskBuilder;
    private final CellStoreFactory cellStoreFactory;
    private final AppProperties appProperties;
    private final OksApproachResolver approachResolver;

    public GridForestPlanner(TieInCandidateProvider candidateProvider, DiameterCatalog diameters,
                             CostModel costModel, MaxLengthEnforcer maxLengthEnforcer,
                             LineStringSimplifier simplifier, ObstacleMaskBuilder maskBuilder,
                             CellStoreFactory cellStoreFactory, AppProperties appProperties,
                             OksApproachResolver approachResolver) {
        this.candidateProvider = candidateProvider;
        this.diameters = diameters;
        this.costModel = costModel;
        this.maxLengthEnforcer = maxLengthEnforcer;
        this.simplifier = simplifier;
        this.maskBuilder = maskBuilder;
        this.cellStoreFactory = cellStoreFactory;
        this.appProperties = appProperties;
        this.approachResolver = approachResolver;
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

    /** Форма сетки поиска пути (ADR-0041): square | hex. */
    private GridShape gridShape() {
        return "hex".equalsIgnoreCase(appProperties.getForestGridShape())
                ? HexGridShape.INSTANCE : SquareGridShape.INSTANCE;
    }

    public List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                           ObstacleIndex obstacleIndex, List<String> warnings,
                                           Map<String, ConnectionExit> exits) {
        return plan(dataset, graph, obstacleIndex, warnings, exits, StageTrace.disabled());
    }

    /**
     * @return до {@link #MAX_PLANS} содержательно различающихся планов — по одному
     *         на выполненный проход поиска (ADR-0037): каждый лес прогоняется
     *         дальше по конвейеру как отдельный вариант.
     */
    public List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                           ObstacleIndex obstacleIndex, List<String> warnings,
                                           Map<String, ConnectionExit> exits, StageTrace trace) {
        List<Terminal> terminals = terminals(dataset, exits, warnings);
        List<String> baseUnconnected = new ArrayList<>();
        Set<String> terminalIds = new HashSet<>();
        for (Terminal terminal : terminals) {
            terminalIds.add(terminal.pointId);
        }
        for (OksConnectionPointObject point : dataset.getConnectionPoints()) {
            if (!terminalIds.contains(point.getId())) {
                baseUnconnected.add(point.getId());
            }
        }
        if (terminals.isEmpty()) {
            return List.of(result(List.of(), baseUnconnected));
        }
        List<TieInCandidate> ties = tieCandidates(dataset, terminals);
        if (ties.isEmpty()) {
            warnings.add("FOREST_NO_TIE_IN_CANDIDATES: не найдено кандидатов врезки");
            baseUnconnected.addAll(terminalIds);
            return List.of(result(List.of(), baseUnconnected));
        }

        long start = System.nanoTime();
        double cell = appProperties.getForestGridCellM() > 0
                ? appProperties.getForestGridCellM() : 2.0;
        Envelope bounds = bounds(dataset, terminals, ties, cell);
        GridShape shape = gridShape();
        long estimatedCells = (long) shape.columns(bounds.getWidth(), cell)
                * shape.rows(bounds.getHeight(), cell);
        boolean spill = cellStoreFactory.spillEnabled(estimatedCells);
        long maxBytes = spill ? Long.MAX_VALUE / 4
                : Math.max(1L, appProperties.getForestGridMaxCells() / 4);
        ObstacleMask pass = maskBuilder.buildPassability(obstacleIndex, bounds, cell, maxBytes,
                warnings, shape);
        if (pass == null) {
            baseUnconnected.addAll(terminalIds);
            return List.of(result(List.of(), baseUnconnected));
        }
        log.info("Grid: cell={} size={}x{} blocked={} build={}ms storage={}", pass.cellM(),
                pass.width(), pass.height(), pass.blockedCells(), pass.buildMs(),
                spill ? "postgis" : "memory");

        Map<Integer, TiePoint> sources = mapSources(pass, ties);
        Map<String, List<ConnectionExit>> exitCandidates = exitCandidates(dataset, exits, terminals);
        long[] reachable = reachableCells(pass, sources.keySet());
        // ADR-0037: если клетка основного выхода заблокирована или недостижима,
        // берём ближайший альтернативный выход, ведущий в достижимую клетку.
        for (Terminal terminal : terminals) {
            if (exitReachable(pass, reachable, terminal.target)) {
                continue;
            }
            for (ConnectionExit candidate : exitCandidates.getOrDefault(terminal.pointId, List.of())) {
                if (!candidate.isBlocked() && candidate.getTarget() != null
                        && exitReachable(pass, reachable, candidate.getTarget())) {
                    terminal.target = candidate.getTarget();
                    terminal.tail = candidate.getTail() == null ? List.of() : candidate.getTail();
                    break;
                }
            }
        }
        // Клетки выхода ОКС принудительно проходимы (цель — на границе буфера ОКС).
        Map<Integer, Terminal> terminalCells = new LinkedHashMap<>();
        List<Integer> exitCells = new ArrayList<>();
        for (Terminal terminal : terminals) {
            int terminalCell = pass.cellAt(terminal.target.x, terminal.target.y);
            terminalCells.putIfAbsent(terminalCell, terminal);
            exitCells.add(terminalCell);
        }
        pass = pass.withClearedCells(exitCells);
        if (trace.isEnabled()) {
            reachable = reachableCells(pass, sources.keySet());
            captureGrid(trace, pass, sources, terminalCells, reachable);
        }
        Map<String, Double> terminalFlow = new HashMap<>();
        double totalFlow = 0.0;
        for (Terminal terminal : terminals) {
            terminalFlow.put(terminal.pointId, terminal.flow);
            totalFlow += terminal.flow;
        }

        int iterations = Math.max(1, appProperties.getForestCostIterations());
        int[] dnEstimate = new int[pass.width() * pass.height()];
        Arrays.fill(dnEstimate, selectDiameter(totalFlow));
        List<GridBuild> builds = new ArrayList<>();
        List<GridReport.Pass> passStats = new ArrayList<>();
        double bestScore = Double.POSITIVE_INFINITY;
        int bestPassIndex = 0;
        GridBuild bestBuild = null;
        for (int passIndex = 0; passIndex < iterations; passIndex++) {
            long passStart = System.nanoTime();
            GridBuild build = buildTrees(pass, sources, terminalCells, obstacleIndex, warnings,
                    passIndex > 0, dnEstimate, terminalFlow, exitCandidates, trace, passIndex + 1);
            long passMs = elapsedMs(passStart);
            builds.add(build);
            passStats.add(GridReport.Pass.builder().index(passIndex + 1).score(build.score)
                    .trees(build.trees.size()).timeMs(passMs).build());
            if (trace.isEnabled()) {
                trace.addTreePass(passIndex + 1, build.rawFeatures);
            }
            log.info("Grid pass {}/{}: score={} trees={} time={}ms", passIndex + 1, iterations,
                    build.score, build.trees.size(), passMs);
            if (build.score < bestScore - EPS) {
                bestScore = build.score;
                bestPassIndex = passIndex;
                bestBuild = build;
            } else if (passIndex > 0) {
                break;
            }
        }
        if (trace.isEnabled() && bestBuild != null) {
            trace.addStage(StageTrace.RELINK, treeFeatures(bestBuild.relinkedTrees));
            trace.addStage(StageTrace.REFINE, treeFeatures(bestBuild.refinedTrees));
            trace.setPasses(passStats.size());
            trace.setBestPass(bestPassIndex + 1);
        }

        long totalMs = elapsedMs(start);
        List<ForestPlanningResult> variants = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (GridBuild build : builds) {
            if (variants.size() >= MAX_PLANS) {
                break;
            }
            if (!signatures.add(signature(build.trees))) {
                continue;
            }
            variants.add(toResult(build, terminals, baseUnconnected, pass, spill, sources,
                    terminalCells, passStats, totalMs));
        }
        if (variants.isEmpty()) {
            variants.add(result(List.of(), baseUnconnected));
        }
        log.info("Grid total: sources={} terminals={} plans={} unconnected={} time={}ms",
                sources.size(), distinctTerminals(terminalCells), variants.size(),
                variants.get(0).getUnconnectedConnectionPointIds().size(), totalMs);
        return variants;
    }

    private ForestPlanningResult toResult(GridBuild build, List<Terminal> terminals,
                                          List<String> baseUnconnected, ObstacleMask pass,
                                          boolean spill, Map<Integer, TiePoint> sources,
                                          Map<Integer, Terminal> terminalCells,
                                          List<GridReport.Pass> passStats, long totalMs) {
        List<String> unconnected = new ArrayList<>(baseUnconnected);
        for (Terminal terminal : terminals) {
            if (!build.connected.contains(terminal.pointId)) {
                unconnected.add(terminal.pointId);
            }
        }
        GridReport report = GridReport.builder()
                .cellM(pass.cellM()).width(pass.width()).height(pass.height())
                .blockedCells(pass.blockedCells()).storage(spill ? "postgis" : "memory")
                .sources(sources.size()).terminals(distinctTerminals(terminalCells))
                .trees(build.trees.size()).unconnected(unconnected.size()).timeMs(totalMs)
                .passes(passStats).build();
        return ForestPlanningResult.builder().trees(build.trees)
                .unconnectedConnectionPointIds(unconnected).gridReport(report).build();
    }

    /** Сигнатура геометрии леса для дедупликации одинаковых проходов. */
    private String signature(List<ForestTree> trees) {
        List<String> parts = new ArrayList<>();
        for (ForestTree tree : trees) {
            for (ForestEdge edge : tree.getEdges()) {
                StringBuilder part = new StringBuilder(edge.getFromNodeId())
                        .append('>').append(edge.getToNodeId());
                for (Coordinate coordinate : edge.getCoordinates()) {
                    part.append(';').append(Math.round(coordinate.x * 100.0))
                            .append(',').append(Math.round(coordinate.y * 100.0));
                }
                parts.add(part.toString());
            }
        }
        Collections.sort(parts);
        return String.join("|", parts);
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

    /**
     * ADR-0039: выходы-кандидаты каждой точки ({@code candidatesFor}), которые
     * используются и для выбора достижимой цели роста, и для переприсоединения.
     * Если резолвер не дал кандидатов, берётся канонический выход из
     * {@code exits}.
     */
    private Map<String, List<ConnectionExit>> exitCandidates(NetworkDataset dataset,
                                                             Map<String, ConnectionExit> exits,
                                                             List<Terminal> terminals) {
        Map<String, List<ConnectionExit>> result = new HashMap<>();
        for (Terminal terminal : terminals) {
            List<ConnectionExit> candidates = approachResolver.candidatesFor(dataset,
                    terminal.pointId);
            if (candidates.isEmpty()) {
                ConnectionExit canonical = exits == null ? null : exits.get(terminal.pointId);
                candidates = canonical == null ? List.of() : List.of(canonical);
            }
            result.put(terminal.pointId, candidates);
        }
        return result;
    }

    /**
     * ADR-0039: выходы точки для переприсоединения. По флагу
     * {@code forest-relink-exit-candidates} — все валидные кандидаты (не более
     * {@code forest-relink-exit-candidates-max}), иначе — только канонический.
     */
    private List<ConnectionExit> relinkExits(Map<String, List<ConnectionExit>> exitCandidates,
                                             Terminal term) {
        ConnectionExit canonical = ConnectionExit.builder().connectionPointId(term.pointId)
                .target(term.target).tail(term.tail).blocked(false).build();
        if (!appProperties.isForestRelinkExitCandidates()) {
            return List.of(canonical);
        }
        int max = Math.max(1, appProperties.getForestRelinkExitCandidatesMax());
        List<ConnectionExit> result = new ArrayList<>();
        for (ConnectionExit candidate : exitCandidates.getOrDefault(term.pointId, List.of())) {
            if (candidate == null || candidate.isBlocked() || candidate.getTarget() == null) {
                continue;
            }
            result.add(candidate);
            if (result.size() >= max) {
                break;
            }
        }
        if (result.isEmpty()) {
            result.add(canonical);
        }
        return result;
    }

    private List<TieInCandidate> tieCandidates(NetworkDataset dataset, List<Terminal> terminals) {
        List<TieInCandidate> all = new ArrayList<>(candidateProvider.candidates(dataset));
        for (Terminal terminal : terminals) {
            all.addAll(candidateProvider.projections(dataset, terminal.target));
        }
        return candidateProvider.distinct(candidateProvider.excludeNearChambers(dataset, all));
    }

    /**
     * Границы сетки: терминалы и кандидаты врезки, а также (ADR-0037) bbox всех
     * входных объектов — иначе обход препятствия, выходящий за точки сети,
     * окажется неисследованным. Отключается {@code forest-grid-include-input-bounds}.
     */
    private Envelope bounds(NetworkDataset dataset, List<Terminal> terminals,
                            List<TieInCandidate> ties, double cell) {
        Envelope bounds = new Envelope();
        for (Terminal terminal : terminals) {
            bounds.expandToInclude(terminal.target);
        }
        for (TieInCandidate tie : ties) {
            bounds.expandToInclude(tie.getCoordinate());
        }
        Envelope datasetBounds = dataset.getBounds();
        if (appProperties.isForestGridIncludeInputBounds() && datasetBounds != null
                && !datasetBounds.isNull()) {
            bounds.expandToInclude(datasetBounds);
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

    private int distinctTerminals(Map<Integer, Terminal> terminalCells) {
        return new LinkedHashSet<>(terminalCells.values()).size();
    }

    /** Достижима ли клетка выхода (или её сосед) от сети — с учётом открытия клетки. */
    private boolean exitReachable(ObstacleMask pass, long[] reachable, Coordinate coordinate) {
        int cell = pass.cellAt(coordinate.x, coordinate.y);
        int col = cell % pass.width();
        int row = cell / pass.width();
        if (reachableAt(pass, reachable, col, row)) {
            return true;
        }
        for (int[] step : pass.neighbors(col, row)) {
            if (reachableAt(pass, reachable, col + step[0], row + step[1])) {
                return true;
            }
        }
        return false;
    }

    private boolean reachableAt(ObstacleMask pass, long[] reachable, int col, int row) {
        if (col < 0 || row < 0 || col >= pass.width() || row >= pass.height()) {
            return false;
        }
        int cell = row * pass.width() + col;
        return (reachable[cell >>> 6] & (1L << (cell & 63))) != 0;
    }

    private int nearestFreeCell(ObstacleMask pass, Coordinate coordinate, Set<Integer> used) {
        return nearestFreeCell(pass, coordinate, used, null);
    }

    private int nearestFreeCell(ObstacleMask pass, Coordinate coordinate, Set<Integer> used,
                                long[] reachable) {
        int start = pass.cellAt(coordinate.x, coordinate.y);
        if (isFree(pass, start % pass.width(), start / pass.width(), used, reachable)) {
            return start;
        }
        if ("square".equals(pass.shapeId())) {
            return nearestFreeCellRings(pass, start, used, reachable);
        }
        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();
        Set<Integer> visited = new HashSet<>();
        queue.add(start);
        visited.add(start);
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            int col = cell % pass.width();
            int row = cell / pass.width();
            for (int[] step : pass.neighbors(col, row)) {
                int c = col + step[0];
                int r = row + step[1];
                if (c < 0 || r < 0 || c >= pass.width() || r >= pass.height()) {
                    continue;
                }
                int next = r * pass.width() + c;
                if (!visited.add(next)) {
                    continue;
                }
                if (isFree(pass, c, r, used, reachable)) {
                    return next;
                }
                queue.add(next);
            }
        }
        return -1;
    }

    /** Ближайшая свободная клетка кольцевым сканом (квадратная сетка, ADR-0034). */
    private int nearestFreeCellRings(ObstacleMask pass, int start, Set<Integer> used,
                                     long[] reachable) {
        int col = start % pass.width();
        int row = start / pass.width();
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
            for (int[] step : pass.neighbors(col, row)) {
                int nc = col + step[0];
                int nr = row + step[1];
                if (nc < 0 || nr < 0 || nc >= width || nr >= height || pass.blockedCell(nc, nr)) {
                    continue;
                }
                if (pass.diagonalStep(step[0], step[1])
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
                                 Map<String, Double> terminalFlow,
                                 Map<String, List<ConnectionExit>> exitCandidates,
                                 StageTrace trace, int passNumber) {
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
                for (int[] step : pass.neighbors(col, row)) {
                    int nc = col + step[0];
                    int nr = row + step[1];
                    if (nc < 0 || nr < 0 || nc >= width || nr >= height
                            || pass.blockedCell(nc, nr)) {
                        continue;
                    }
                    if (pass.diagonalStep(step[0], step[1])
                            && (pass.blockedCell(col, nr) || pass.blockedCell(nc, row))) {
                        continue;
                    }
                    int next = nr * width + nc;
                    if (store.settled(next)) {
                        continue;
                    }
                    int parentCell = store.parent(current);
                    if (hardTurn() && parentCell != -1
                            && turnDegrees(center(pass, parentCell), center(pass, current),
                                    center(pass, next)) > maxTurnDeg() + ANGLE_EPS) {
                        continue;
                    }
                    double length = pass.stepLength(step[0], step[1]);
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
            List<StageFeature> rawFeatures = trace.isEnabled()
                    ? rawTreeFeatures(pass, treeCells, parentMap, passNumber) : List.of();
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
                        childCount, parentMap, terminalsByCell, flow, dnEstimate);
                if (tree != null) {
                    trees.add(tree);
                }
                treeIndex++;
            }

            if (appProperties.isForestReattachPass() && !trees.isEmpty()) {
                Map<String, List<ConnectionExit>> exits = new HashMap<>();
                Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>> own =
                        new HashMap<>();
                for (Terminal term : new LinkedHashSet<>(terminalCells.values())) {
                    if (term.connected) {
                        exits.put(term.pointId, relinkExits(exitCandidates, term));
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
            List<ForestTree> relinkedTrees = trace.isEnabled() ? new ArrayList<>(trees) : trees;
            trees = refineTrees(trees, pass, obstacleIndex, terminalCells, warnings);
            List<ForestTree> refinedTrees = trace.isEnabled() ? new ArrayList<>(trees) : trees;

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
            return new GridBuild(trees, score, connected, rawFeatures, refinedTrees, relinkedTrees);
        } finally {
            store.close();
        }
    }

    /** Сырое дерево прохода: отрезки сетки «родитель → клетка» до сглаживания. */
    private List<StageFeature> rawTreeFeatures(ObstacleMask pass, Set<Integer> treeCells,
                                               Map<Integer, Integer> parentMap, int passNumber) {
        List<StageFeature> features = new ArrayList<>();
        for (int cell : treeCells) {
            int parent = parentMap.getOrDefault(cell, -1);
            if (parent == -1) {
                continue;
            }
            features.add(StageFeature.builder()
                    .geometry(line(center(pass, parent), center(pass, cell)))
                    .objectType("tree_cell")
                    .properties(Map.of("pass", passNumber))
                    .build());
        }
        return features;
    }

    /** Рёбра и узлы леса в виде объектов этапа (refine / relink). */
    private List<StageFeature> treeFeatures(List<ForestTree> trees) {
        List<StageFeature> features = new ArrayList<>();
        if (trees == null) {
            return features;
        }
        for (ForestTree tree : trees) {
            for (ForestEdge edge : tree.getEdges()) {
                features.add(StageFeature.builder()
                        .geometry(GeometrySupport.GEOMETRY_FACTORY.createLineString(
                                edge.getCoordinates().toArray(new Coordinate[0])))
                        .objectType("forest_edge")
                        .properties(Map.of(
                                "id", edge.getId(),
                                "from", edge.getFromNodeId(),
                                "to", edge.getToNodeId(),
                                "diameter_mm", edge.getDiameterMm(),
                                "flow_tph", edge.getFlowTph()))
                        .build());
            }
            for (ForestNode node : tree.getNodes().values()) {
                features.add(StageFeature.builder()
                        .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(node.getCoordinate()))
                        .objectType("forest_node")
                        .properties(Map.of(
                                "id", node.getId(),
                                "node_type", node.getType().name(),
                                "existing", node.isExisting()))
                        .build());
            }
        }
        return features;
    }

    /** Диагностика сетки для этапа «сетка» (ADR-0036). */
    private void captureGrid(StageTrace trace, ObstacleMask pass, Map<Integer, TiePoint> sources,
                             Map<Integer, Terminal> terminalCells, long[] reachable) {
        int maxPixels = appProperties.getTraceGridMaxPixels();
        GridMaskCodec.Downscale ds = GridMaskCodec.downscale(pass.width(), pass.height(), maxPixels);
        int width = pass.width();
        String blocked = GridMaskCodec.encode(width, pass.height(), ds,
                index -> pass.blockedCell(index % width, index / width));
        String reach = GridMaskCodec.encode(width, pass.height(), ds,
                index -> (reachable[index >>> 6] & (1L << (index & 63))) != 0);
        List<double[]> sourceCells = new ArrayList<>();
        for (int cell : sources.keySet()) {
            sourceCells.add(cellCenter(pass, cell));
        }
        List<double[]> terminals = new ArrayList<>();
        for (int cell : terminalCells.keySet()) {
            terminals.add(cellCenter(pass, cell));
        }
        trace.addGrid(GridMaskPayload.builder()
                .originX(pass.originX()).originY(pass.originY()).cellM(pass.cellM())
                .gridShape(pass.shapeId()).rowSpacing(pass.rowSpacing())
                .width(pass.width()).height(pass.height())
                .imageWidth(ds.imageWidth).imageHeight(ds.imageHeight)
                .imageCellM(pass.cellM() * ds.factor)
                .downscaled(ds.factor > 1)
                .blocked(blocked).reachable(reach)
                .sources(sourceCells).terminalCells(terminals)
                .build());
    }

    private double[] cellCenter(ObstacleMask pass, int cell) {
        return new double[]{pass.cellCenterX(cell), pass.cellCenterY(cell)};
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
                                 Map<Integer, Double> flow, int[] dnEstimate) {
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
                if (chain.terminal != null) {
                    trunk.add(chain.terminal.target);
                    if (!chain.terminal.tail.isEmpty()) {
                        trunk.add(chain.terminal.point);
                    }
                }
                double edgeFlow = flow.getOrDefault(chain.end, 0.0);
                int dn = selectDiameter(edgeFlow);
                updateEstimate(dnEstimate, cell, chain, dn);
                edges.add(ForestEdge.builder()
                        .id("e_" + treeIndex + "_" + index++)
                        .fromNodeId(fromId)
                        .toNodeId(ids.get(chain.end))
                        .coordinates(trunk)
                        .flowTph(edgeFlow)
                        .diameterMm(dn)
                        .build());
            }
        }
        if (edges.isEmpty()) {
            return null;
        }
        return ForestTree.builder().tieInNodeId(ids.get(root)).nodes(nodes).edges(edges).build();
    }

    /**
     * Финальное уточнение геометрии леса (ADR-0038): string pulling, ремонт
     * поворотов и grid-заход на выход выполняются после {@code relink}, по
     * финальной топологии — включая ветки, созданные переприсоединением.
     * Предельная длина проверяется здесь же, по уже уточнённым путям.
     */
    private List<ForestTree> refineTrees(List<ForestTree> trees, ObstacleMask pass,
                                         ObstacleIndex obstacleIndex,
                                         Map<Integer, Terminal> terminalCells,
                                         List<String> warnings) {
        Map<String, Terminal> terminalsById = new HashMap<>();
        for (Terminal terminal : new LinkedHashSet<>(terminalCells.values())) {
            terminalsById.put(terminal.pointId, terminal);
        }
        List<ForestTree> result = new ArrayList<>();
        for (ForestTree tree : trees) {
            Map<String, ForestNode> nodes = tree.getNodes();
            Map<String, String> parent = parentByRoot(tree);
            List<ForestEdge> edges = new ArrayList<>();
            for (ForestEdge edge : tree.getEdges()) {
                ForestNode from = nodes.get(edge.getFromNodeId());
                if (from == null || !nodes.containsKey(edge.getToNodeId())) {
                    edges.add(edge);
                    continue;
                }
                List<Coordinate> coordinates = new ArrayList<>(edge.getCoordinates());
                Terminal terminal = terminalsById.get(edge.getToNodeId());
                boolean isTerminal = terminal != null;
                Coordinate endNext = isTerminal && !terminal.tail.isEmpty()
                        ? terminal.point : null;
                String parentId = parent.get(edge.getFromNodeId());
                Coordinate startPrevious = parentId != null && nodes.containsKey(parentId)
                        ? nodes.get(parentId).getCoordinate() : null;
                List<Coordinate> refined;
                if (isTerminal && !terminal.tail.isEmpty() && coordinates.size() >= 2) {
                    Coordinate point = coordinates.get(coordinates.size() - 1);
                    List<Coordinate> trunk = new ArrayList<>(
                            coordinates.subList(0, coordinates.size() - 1));
                    refined = new ArrayList<>(refine(trunk, obstacleIndex, startPrevious,
                            endNext, true));
                    refined.add(point);
                } else {
                    refined = new ArrayList<>(refine(coordinates, obstacleIndex, startPrevious,
                            endNext, isTerminal));
                }
                if (isTerminal && exitGridDogleg()) {
                    refined = rebuildExitJoint(refined, pass, obstacleIndex);
                }
                if (!turnsWithinLimit(refined, refined.size() - 2 - (isTerminal ? 2 : 0))) {
                    warnings.add("FOREST_TURN_UNRESOLVED: участок " + edge.getId());
                }
                edges.add(ForestEdge.builder()
                        .id(edge.getId())
                        .fromNodeId(edge.getFromNodeId())
                        .toNodeId(edge.getToNodeId())
                        .coordinates(refined)
                        .flowTph(edge.getFlowTph())
                        .diameterMm(edge.getDiameterMm())
                        .build());
            }
            try {
                edges = maxLengthEnforcer.enforce(edges, tree.getTieInNodeId());
            } catch (IllegalArgumentException noDiameter) {
                warnings.add("FOREST_MAX_LENGTH_UNRESOLVED: " + noDiameter.getMessage());
            }
            result.add(ForestTree.builder().tieInNodeId(tree.getTieInNodeId())
                    .nodes(nodes).edges(edges).build());
        }
        return result;
    }

    /** Ориентация рёбер дерева от корня: узел → родительский узел. */
    private Map<String, String> parentByRoot(ForestTree tree) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (ForestEdge edge : tree.getEdges()) {
            adjacency.computeIfAbsent(edge.getFromNodeId(), key -> new ArrayList<>())
                    .add(edge.getToNodeId());
            adjacency.computeIfAbsent(edge.getToNodeId(), key -> new ArrayList<>())
                    .add(edge.getFromNodeId());
        }
        Map<String, String> parent = new HashMap<>();
        Set<String> visited = new HashSet<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        if (!tree.getNodes().containsKey(tree.getTieInNodeId())) {
            return parent;
        }
        visited.add(tree.getTieInNodeId());
        queue.add(tree.getTieInNodeId());
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
        Coordinate best = null;
        double bestExtra = Double.POSITIVE_INFINITY;
        for (Coordinate base : new Coordinate[]{a, b}) {
            int baseCell = pass.cellAt(base.x, base.y);
            int baseCol = baseCell % pass.width();
            int baseRow = baseCell / pass.width();
            for (int[] step : pass.neighbors(baseCol, baseRow)) {
                int nc = baseCol + step[0];
                int nr = baseRow + step[1];
                if (nc < 0 || nr < 0 || nc >= pass.width() || nr >= pass.height()) {
                    continue;
                }
                Coordinate q = new Coordinate(pass.centerX(nc, nr), pass.centerY(nc, nr));
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

    private double turnDegrees(Coordinate previous, Coordinate vertex, Coordinate next) {
        return turnDegrees(vertex.x - previous.x, vertex.y - previous.y,
                next.x - vertex.x, next.y - vertex.y);
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
        return new Coordinate(pass.cellCenterX(cell), pass.cellCenterY(cell));
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
        private Coordinate target;
        private List<Coordinate> tail;
        private final double flow;
        private Integer startCell;
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
        private final List<StageFeature> rawFeatures;
        private final List<ForestTree> refinedTrees;
        private final List<ForestTree> relinkedTrees;

        private GridBuild(List<ForestTree> trees, double score, Set<String> connected,
                          List<StageFeature> rawFeatures, List<ForestTree> refinedTrees,
                          List<ForestTree> relinkedTrees) {
            this.trees = trees;
            this.score = score;
            this.connected = connected;
            this.rawFeatures = rawFeatures;
            this.refinedTrees = refinedTrees;
            this.relinkedTrees = relinkedTrees;
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
