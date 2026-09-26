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
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.ObstacleMask;
import ru.lct.heating.geometry.ObstacleMaskBuilder;
import ru.lct.heating.geometry.GridShape;
import ru.lct.heating.geometry.HexGridShape;
import ru.lct.heating.geometry.SpecialSpan;
import ru.lct.heating.geometry.SpecialZone;
import ru.lct.heating.geometry.SpecialZoneIndex;
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

    /** Переиспользуемые буферы turn-aware поиска (по потоку — для параллелизма). */
    private static final ThreadLocal<GridPathWorkspace> GRID_PATH =
            ThreadLocal.withInitial(GridPathWorkspace::new);

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
        return plan(dataset, graph, obstacleIndex, new SpecialZoneIndex(List.of()), warnings, exits,
                StageTrace.disabled());
    }

    public List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                           ObstacleIndex obstacleIndex, List<String> warnings,
                                           Map<String, ConnectionExit> exits, StageTrace trace) {
        return plan(dataset, graph, obstacleIndex, new SpecialZoneIndex(List.of()), warnings, exits,
                trace);
    }

    /**
     * @param specialZones спецзоны для учёта {@code Kспец} в целевой функции
     *                     поиска (E25-04).
     * @return до {@link #MAX_PLANS} содержательно различающихся планов — по одному
     *         на выполненный проход поиска (ADR-0037): каждый лес прогоняется
     *         дальше по конвейеру как отдельный вариант.
     */
    public List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                           ObstacleIndex obstacleIndex,
                                           SpecialZoneIndex specialZones, List<String> warnings,
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
        List<TieInCandidate> ties = tieCandidates(dataset, terminals, graph);
        if (ties.isEmpty()) {
            warnings.add("FOREST_NO_TIE_IN_CANDIDATES: не найдено кандидатов врезки");
            baseUnconnected.addAll(terminalIds);
            return List.of(result(List.of(), baseUnconnected));
        }
        if (trace.isEnabled()) {
            trace.addStage(StageTrace.TIES, tieFeatures(ties));
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
        List<Integer> openCells = new ArrayList<>();
        for (Terminal terminal : terminals) {
            int terminalCell = pass.cellAt(terminal.target.x, terminal.target.y);
            terminalCells.putIfAbsent(terminalCell, terminal);
            openCells.add(terminalCell);
            // E23-04: финальный коридор ОКС (target→point) принудительно проходим,
            // иначе gridPath не может подойти к выходу (клетки в буфере ОКС закрыты).
            List<Coordinate> tail = terminal.tail;
            for (int i = 0; i + 1 < tail.size(); i++) {
                addCorridorCells(openCells, pass, tail.get(i), tail.get(i + 1));
            }
        }
        // E25-07: клетки источников врезки тоже открываем, иначе источник,
        // попавший в строгую спецполосу, не может «выйти» из неё.
        openCells.addAll(sources.keySet());
        pass = pass.withClearedCells(openCells);
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
        // E25-04: Kспец по клеткам (max при наложении) для целевой функции.
        // E41: растр угловых спецзон, чтобы не звать angleOk на каждом ребре.
        ZoneRasters rasters = zoneRasters(specialZones, pass);
        double[] specialK = rasters.k;
        long[] angleMask = rasters.angleMask;
        List<GridBuild> builds = new ArrayList<>();
        List<GridReport.Pass> passStats = new ArrayList<>();
        double bestScore = Double.POSITIVE_INFINITY;
        int bestPassIndex = 0;
        for (int passIndex = 0; passIndex < iterations; passIndex++) {
            trace.reportPass(passIndex + 1, iterations);
            long passStart = System.nanoTime();
            GridBuild build = buildTrees(pass, dataset, sources, terminalCells, obstacleIndex,
                    specialZones, specialK, angleMask, graph, warnings, passIndex > 0, dnEstimate,
                    terminalFlow, exitCandidates, trace, passIndex + 1);
            long passMs = elapsedMs(passStart);
            builds.add(build);
            passStats.add(GridReport.Pass.builder().index(passIndex + 1).score(build.score)
                    .trees(build.trees.size()).timeMs(passMs).build());
            if (trace.isEnabled()) {
                int treePass = passIndex + 1;
                trace.addTreePass(treePass, build.rawFeatures);
                trace.addPassStage(StageTrace.RELINK, treePass, treeFeatures(build.relinkedTrees));
                trace.addPassStage(StageTrace.CONTRACT, treePass, treeFeatures(build.contractedTrees));
                trace.addPassStage(StageTrace.REFINE, treePass, treeFeatures(build.refinedTrees));
                trace.addPassStage(StageTrace.CHAMBERS, treePass, treeFeatures(build.optimizedTrees));
            }
            log.info("Grid pass {}/{}: score={} trees={} time={}ms", passIndex + 1, iterations,
                    build.score, build.trees.size(), passMs);
            if (build.score < bestScore - EPS) {
                bestScore = build.score;
                bestPassIndex = passIndex;
            } else if (passIndex > 0) {
                break;
            }
        }
        if (trace.isEnabled()) {
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
                .unconnectedConnectionPointIds(unconnected).gridReport(report)
                .passNumber(build.passNumber).build();
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
     * {@code forest-relink-exit-relocation} — все валидные кандидаты (не более
     * {@code forest-relink-exit-candidates-max}), иначе — только выход growth.
     */
    private List<ConnectionExit> relinkExits(Map<String, List<ConnectionExit>> exitCandidates,
                                             Terminal term) {
        ConnectionExit canonical = ConnectionExit.builder().connectionPointId(term.pointId)
                .target(term.target).tail(term.tail).blocked(false).build();
        if (!appProperties.isForestRelinkExitRelocation()) {
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

    private List<TieInCandidate> tieCandidates(NetworkDataset dataset, List<Terminal> terminals,
                                               ExistingNetworkGraph graph) {
        List<TieInCandidate> all = new ArrayList<>(candidateProvider.candidates(dataset));
        for (Terminal terminal : terminals) {
            all.addAll(candidateProvider.projections(dataset, terminal.target));
        }
        all = candidateProvider.distinct(candidateProvider.excludeNearChambers(dataset, all));
        return preferExistingChambers(dataset, graph, all);
    }

    /**
     * E26-01/FR-22: если точка врезки на участке сети находится в пределах
     * {@code chamber-tie-in-radius-m} (10 м) от существующей камеры с запасом
     * примыканий — врезаемся в камеру: сетевые кандидаты рядом с такой камерой
     * исключаются (камера остаётся кандидатом сама).
     */
    private List<TieInCandidate> preferExistingChambers(NetworkDataset dataset,
                                                        ExistingNetworkGraph graph,
                                                        List<TieInCandidate> candidates) {
        double radius = appProperties.getChamberTieInRadiusM();
        if (!appProperties.isForestChamberTieInRules() || radius <= 0.0
                || dataset.getHeatChambers() == null || dataset.getHeatChambers().isEmpty()) {
            return candidates;
        }
        int maxDegree = appProperties.getForestMaxChamberDegree();
        List<Coordinate> eligible = new ArrayList<>();
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            if (chamber.getGeometry() == null) {
                continue;
            }
            int used = graph == null ? 0 : graph.chamberAttachments(chamber.getId());
            if (maxDegree <= 0 || used < maxDegree) {
                eligible.add(chamber.getGeometry().getCoordinate());
            }
        }
        if (eligible.isEmpty()) {
            return candidates;
        }
        List<TieInCandidate> result = new ArrayList<>(candidates.size());
        for (TieInCandidate candidate : candidates) {
            if (!"heat_chamber".equals(candidate.getExistingObjectType())
                    && nearAny(candidate.getCoordinate(), eligible, radius)) {
                continue;
            }
            result.add(candidate);
        }
        return result;
    }

    private boolean nearAny(Coordinate coordinate, List<Coordinate> points, double radius) {
        for (Coordinate point : points) {
            if (coordinate.distance(point) <= radius) {
                return true;
            }
        }
        return false;
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

    /** Клетки вдоль отрезка (для принудительно открытого коридора выхода). */
    private void addCorridorCells(List<Integer> cells, ObstacleMask pass, Coordinate a, Coordinate b) {
        double length = a.distance(b);
        int steps = Math.max(1, (int) Math.ceil(length / Math.max(0.25, pass.cellM() * 0.5)));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            cells.add(pass.cellAt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t));
        }
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

    private GridBuild buildTrees(ObstacleMask pass, NetworkDataset dataset,
                                 Map<Integer, TiePoint> sources,
                                 Map<Integer, Terminal> terminalCells, ObstacleIndex obstacleIndex,
                                 SpecialZoneIndex specialZones, double[] specialK, long[] angleMask,
                                 ExistingNetworkGraph graph, List<String> warnings,
                                 boolean costWeighted, int[] dnEstimate,
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
            long dijkstraStart = System.nanoTime();
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
                    // E41: жёсткий контроль минимального угла пересечения спецзон.
                    // Гейтинг по растру: точная проверка только у самих зон.
                    if (angleMask != null && (bitSet(angleMask, current) || bitSet(angleMask, next))
                            && !specialZones.angleOk(line(center(pass, current), center(pass, next)))) {
                        continue;
                    }
                    double length = pass.stepLength(step[0], step[1]);
                    double weight = length;
                    if (costWeighted) {
                        int dn = Math.max(dnEstimate[current], dnEstimate[next]);
                        double k = Math.max(specialK[current], specialK[next]);
                        weight = costModel.score(Math.round(length * diameters.newCostPerM(dn) * k),
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
            long dijkstraMs = elapsedMs(dijkstraStart);

            long extractStart = System.nanoTime();
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
            long extractMs = elapsedMs(extractStart);

            long relinkStart = System.nanoTime();
            Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>> own = new HashMap<>();
            if (obstacleIndex != null && !trees.isEmpty()) {
                for (Terminal term : new LinkedHashSet<>(terminalCells.values())) {
                    if (term.connected) {
                        own.put(term.pointId, obstacleIndex.obstaclesContaining(
                                GeometrySupport.GEOMETRY_FACTORY.createPoint(term.point)));
                    }
                }
            }
            if (appProperties.isForestReattachPass() && !trees.isEmpty()) {
                Map<String, List<ConnectionExit>> exits = new HashMap<>();
                for (Terminal term : new LinkedHashSet<>(terminalCells.values())) {
                    if (term.connected) {
                        exits.put(term.pointId, relinkExits(exitCandidates, term));
                    }
                }
                trees = new TerminalRelinker(costModel, diameters, appProperties, graph)
                        .relink(trees, exits, terminalFlow, obstacleIndex, own);
            }
            List<ForestTree> relinkedTrees = trace.isEnabled() ? new ArrayList<>(trees) : trees;
            // FR-30: сквозные (degree-2) узлы без смены параметра — не технические
            // узлы; после relink склеиваем их рёбра в одну LineString.
            trees = contractPassThroughNodes(trees);
            // E50: единая ориентация рёбер от корня к листьям — терминал всегда
            // конечный узел ребра (иначе `contractPassThroughNodes`/`relink` могут
            // развернуть ребро и канонический выход потеряется).
            trees = orientEdgesFromRoot(trees);
            List<ForestTree> contractedTrees = trace.isEnabled() ? new ArrayList<>(trees) : trees;
            long relinkMs = elapsedMs(relinkStart);
            long refineStart = System.nanoTime();
            trees = refineTrees(trees, pass, obstacleIndex, specialZones, terminalCells, own, graph,
                    warnings);
            List<ForestTree> refinedTrees = trace.isEnabled() ? new ArrayList<>(trees) : trees;
            if (appProperties.isForestChamberOptimization()) {
                trees = optimizeChambers(trees, dataset, pass, obstacleIndex, specialZones,
                        terminalCells, own);
            } else if (appProperties.isForestRootOptimization()) {
                trees = optimizeRoots(trees, dataset, pass, obstacleIndex, warnings);
            }
            List<ForestTree> optimizedTrees = trace.isEnabled() ? new ArrayList<>(trees) : trees;
            long refineMs = elapsedMs(refineStart);
            log.info("Grid pass {}: dijkstra={}ms extract={}ms relink={}ms refine={}ms", passNumber,
                    dijkstraMs, extractMs, relinkMs, refineMs);

            Set<String> connected = new HashSet<>();
            List<String> unconnected = new ArrayList<>();
            for (Terminal term : new LinkedHashSet<>(terminalCells.values())) {
                if (term.connected) {
                    connected.add(term.pointId);
                } else {
                    unconnected.add(term.pointId);
                }
            }
            double score = estimateScore(trees, unconnected, terminalFlow, specialZones);
            return new GridBuild(trees, score, connected, rawFeatures, relinkedTrees,
                    contractedTrees, refinedTrees, optimizedTrees, passNumber);
        } finally {
            store.close();
        }
    }

    /**
     * FR-30: технический узел — только смена параметра без разветвления, а не
     * обычный поворот. После {@code relink} бывшие развилки могут стать
     * сквозными (degree 2); контрактируем такие узлы, склеивая рёбра в одну
     * LineString. Узлы смены Ду, камеры, корень и терминалы сохраняются.
     */
    static List<ForestTree> contractPassThroughNodes(List<ForestTree> trees) {
        List<ForestTree> result = new ArrayList<>(trees.size());
        for (ForestTree tree : trees) {
            result.add(contractTree(tree));
        }
        return result;
    }

    static ForestTree contractTree(ForestTree tree) {
        Map<String, ForestNode> nodes = new LinkedHashMap<>(tree.getNodes());
        List<ForestEdge> edges = new ArrayList<>(tree.getEdges());
        String rootId = tree.getTieInNodeId();
        boolean changed = true;
        while (changed) {
            changed = false;
            Map<String, List<Integer>> incident = new HashMap<>();
            for (int i = 0; i < edges.size(); i++) {
                ForestEdge edge = edges.get(i);
                incident.computeIfAbsent(edge.getFromNodeId(), key -> new ArrayList<>()).add(i);
                incident.computeIfAbsent(edge.getToNodeId(), key -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<String, List<Integer>> entry : incident.entrySet()) {
                String nodeId = entry.getKey();
                List<Integer> ids = entry.getValue();
                if (ids.size() != 2 || nodeId.equals(rootId)) {
                    continue;
                }
                ForestNode node = nodes.get(nodeId);
                if (node == null || node.isExisting()
                        || node.getType() != NodeType.TECHNICAL_NODE) {
                    continue;
                }
                ForestEdge first = edges.get(ids.get(0));
                ForestEdge second = edges.get(ids.get(1));
                if (first.getDiameterMm() != second.getDiameterMm()) {
                    continue;
                }
                ForestEdge merged = mergeAt(first, second, nodeId);
                if (merged == null) {
                    continue;
                }
                nodes.remove(nodeId);
                edges.remove(first);
                edges.remove(second);
                edges.add(merged);
                changed = true;
                break;
            }
        }
        return ForestTree.builder().tieInNodeId(rootId).nodes(nodes).edges(edges).build();
    }

    /** Склейка двух рёбер, сходящихся в сквозном узле {@code nodeId}. */
    private static ForestEdge mergeAt(ForestEdge first, ForestEdge second, String nodeId) {
        List<Coordinate> firstCoords = new ArrayList<>(first.getCoordinates());
        List<Coordinate> secondCoords = new ArrayList<>(second.getCoordinates());
        String fromId;
        if (nodeId.equals(first.getToNodeId())) {
            fromId = first.getFromNodeId();
        } else if (nodeId.equals(first.getFromNodeId())) {
            Collections.reverse(firstCoords);
            fromId = first.getToNodeId();
        } else {
            return null;
        }
        String toId;
        if (nodeId.equals(second.getFromNodeId())) {
            toId = second.getToNodeId();
        } else if (nodeId.equals(second.getToNodeId())) {
            Collections.reverse(secondCoords);
            toId = second.getFromNodeId();
        } else {
            return null;
        }
        List<Coordinate> coordinates = new ArrayList<>(firstCoords);
        for (int i = 1; i < secondCoords.size(); i++) {
            coordinates.add(secondCoords.get(i));
        }
        return ForestEdge.builder()
                .id(first.getId())
                .fromNodeId(fromId)
                .toNodeId(toId)
                .coordinates(coordinates)
                .flowTph(first.getFlowTph())
                .diameterMm(first.getDiameterMm())
                .build();
    }

    /**
     * E50: ориентировать все рёбра дерева от корня к листьям. После контракции
     * сквозных узлов ребро может быть развёрнуто (`a` — лист, `b` — корень);
     * терминалы обязаны быть конечным (`to`) узлом, иначе `refine`/`simplify`
     * не защищают канонический выход `target→point`.
     */
    static List<ForestTree> orientEdgesFromRoot(List<ForestTree> trees) {
        List<ForestTree> result = new ArrayList<>(trees.size());
        for (ForestTree tree : trees) {
            result.add(orientTree(tree));
        }
        return result;
    }

    private static ForestTree orientTree(ForestTree tree) {
        String rootId = tree.getTieInNodeId();
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
        List<ForestEdge> edges = new ArrayList<>(tree.getEdges().size());
        for (ForestEdge edge : tree.getEdges()) {
            String from = edge.getFromNodeId();
            String to = edge.getToNodeId();
            boolean reverse;
            if (from.equals(rootId)) {
                reverse = false;
            } else if (to.equals(rootId)) {
                reverse = true;
            } else {
                String parentOfFrom = parent.get(from);
                reverse = parentOfFrom != null && parentOfFrom.equals(to);
            }
            List<Coordinate> coordinates = edge.getCoordinates();
            if (reverse) {
                coordinates = new ArrayList<>(coordinates);
                Collections.reverse(coordinates);
            }
            edges.add(ForestEdge.builder()
                    .id(edge.getId())
                    .fromNodeId(reverse ? to : from)
                    .toNodeId(reverse ? from : to)
                    .coordinates(coordinates)
                    .flowTph(edge.getFlowTph())
                    .diameterMm(edge.getDiameterMm())
                    .build());
        }
        return ForestTree.builder().tieInNodeId(rootId).nodes(tree.getNodes()).edges(edges).build();
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

    /** Кандидаты врезки в существующую сеть (этап «Кандидаты врезки»). */
    private List<StageFeature> tieFeatures(List<TieInCandidate> ties) {
        List<StageFeature> features = new ArrayList<>();
        for (TieInCandidate tie : ties) {
            features.add(StageFeature.builder()
                    .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(tie.getCoordinate()))
                    .objectType("tie_in_candidate")
                    .properties(Map.of(
                            "existing_id", String.valueOf(tie.getExistingObjectId()),
                            "existing_type", String.valueOf(tie.getExistingObjectType()),
                            "diameter_mm", tie.getExistingDiameterMm() == null
                                    ? 0 : tie.getExistingDiameterMm()))
                    .build());
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
                                 Map<String, Double> terminalFlow, SpecialZoneIndex specialZones) {
        long cost = 0L;
        double length = 0.0;
        List<String> ignoredWarnings = new ArrayList<>();
        for (ForestTree tree : trees) {
            Map<String, Integer> maxIncident = new HashMap<>();
            Map<String, Integer> outgoing = new HashMap<>();
            for (ForestEdge edge : tree.getEdges()) {
                double edgeLength = edge.lengthM();
                cost += costModel.segmentCost(edgeLength, edge.getDiameterMm(), 1.0,
                        maxKSpecial(edge, specialZones, ignoredWarnings));
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

    /**
     * FR-28: жадное упрощение рёбер — удаление «лишних» внутренних вершин
     * (ступени/зигзаги), если объединяющий отрезок не заблокирован и углы на
     * соседях остаются допустимыми. Последняя внутренняя вершина (target вывода)
     * сохраняется.
     */
    private List<ForestEdge> simplifyEdges(List<ForestEdge> edges, ObstacleIndex obstacleIndex,
                                           SpecialZoneIndex specialZones,
                                           Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                                   ownObstacles) {
        List<ForestEdge> result = new ArrayList<>(edges.size());
        for (ForestEdge edge : edges) {
            List<Coordinate> coords = new ArrayList<>(edge.getCoordinates());
            // E50: свой ОКС можно игнорировать только на хвосте выхода
            // `target→point`; упрощение никогда его не объединяет (последние две
            // вершины защищены), поэтому здесь изоляция не применяется.
            Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored = Set.of();
            boolean changed = true;
            int guard = 0;
            while (changed && guard++ <= coords.size()) {
                changed = false;
                for (int i = 1; i < coords.size() - 2; i++) {
                    // E41: не срезать вершину, если отрезок пойдёт под острым углом
                    // к спецобъекту.
                    if (specialZones != null
                            && !specialZones.angleOk(line(coords.get(i - 1), coords.get(i + 1)))) {
                        continue;
                    }
                    if (canDropVertex(coords, i, obstacleIndex, ignored)) {
                        coords.remove(i);
                        changed = true;
                        break;
                    }
                }
            }
            result.add(ForestEdge.builder().id(edge.getId()).fromNodeId(edge.getFromNodeId())
                    .toNodeId(edge.getToNodeId()).coordinates(coords)
                    .flowTph(edge.getFlowTph()).diameterMm(edge.getDiameterMm()).build());
        }
        return result;
    }

    /**
     * E27/ADR-0047: разрешение самопересечений нового леса средствами сетки.
     * Пересекающаяся пара рёбер (без общих узлов) — «жертва» (меньшее по длине)
     * перепрокладывается через {@code gridPath}, остальные рёбра дерева добавляются
     * временными препятствиями с вырезами вокруг узлов. При неудаче — диагностика
     * {@code FOREST_CROSSING_UNRESOLVED}.
     */
    private List<ForestEdge> repairCrossings(List<ForestEdge> edges, Map<String, ForestNode> nodes,
                                             ObstacleMask pass, ObstacleIndex obstacleIndex,
                                             Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                                     ownObstacles,
                                             List<String> warnings) {
        List<ForestEdge> current = new ArrayList<>(edges);
        Set<String> reported = new HashSet<>();
        int maxPasses = Math.max(4, localPasses() * 2);
        for (int passIndex = 0; passIndex < maxPasses; passIndex++) {
            boolean changed = false;
            for (int i = 0; i < current.size(); i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    ForestEdge first = current.get(i);
                    ForestEdge second = current.get(j);
                    if (sharesNode(first, second) || !crosses(first, second)) {
                        continue;
                    }
                    // Пробуем оба ребра «жертвой»: короткое, затем длинное.
                    ForestEdge shorter = line(first).getLength() <= line(second).getLength()
                            ? first : second;
                    ForestEdge longer = shorter == first ? second : first;
                    ForestEdge rebuilt = rebuildCrossing(shorter, current, longer, nodes, pass,
                            obstacleIndex, ignoredFor(shorter, ownObstacles));
                    if (rebuilt != null) {
                        current.set(current.indexOf(shorter), rebuilt);
                        changed = true;
                    } else {
                        rebuilt = rebuildCrossing(longer, current, shorter, nodes, pass, obstacleIndex,
                                ignoredFor(longer, ownObstacles));
                        if (rebuilt != null) {
                            current.set(current.indexOf(longer), rebuilt);
                            changed = true;
                        } else if (reported.add(first.getId() + "|" + second.getId())) {
                            warnings.add("FOREST_CROSSING_UNRESOLVED: участки " + first.getId()
                                    + " и " + second.getId());
                        }
                    }
                    break;
                }
                if (changed) {
                    break;
                }
            }
            if (!changed) {
                break;
            }
        }
        return current;
    }

    /**
     * E44: финальный ремонт стыка вывода терминальных рёбер — если поворот на
     * target превышает предел, перестраиваем подход, не меняя target и point.
     */
    private List<ForestEdge> repairExitJoints(List<ForestEdge> edges, Set<String> terminalNodes,
                                              ObstacleMask pass, ObstacleIndex obstacleIndex,
                                              Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                                      ownObstacles) {
        List<ForestEdge> result = new ArrayList<>(edges.size());
        for (ForestEdge edge : edges) {
            boolean terminal = terminalNodes.contains(edge.getToNodeId());
            List<Coordinate> coords = new ArrayList<>(edge.getCoordinates());
            if (terminal && coords.size() >= 3
                    && !turnAllowed(coords.get(coords.size() - 3), coords.get(coords.size() - 2),
                            coords.get(coords.size() - 1))) {
                List<Coordinate> fixed = repairExitApproach(coords, null, pass, obstacleIndex,
                        ownObstacles, edge.getToNodeId());
                if (fixed != null) {
                    coords = fixed;
                }
            }
            result.add(ForestEdge.builder().id(edge.getId()).fromNodeId(edge.getFromNodeId())
                    .toNodeId(edge.getToNodeId()).coordinates(coords).flowTph(edge.getFlowTph())
                    .diameterMm(edge.getDiameterMm()).build());
        }
        return result;
    }

    /**
     * E50: свой ОКС не игнорируется — изоляция допустима только на хвосте
     * выхода, который {@link #rebuildCrossing} сохраняет отдельно.
     */
    private Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignoredFor(ForestEdge edge,
            Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>> ownObstacles) {
        return Set.of();
    }

    private ForestEdge rebuildCrossing(ForestEdge victim, List<ForestEdge> all, ForestEdge avoid,
                                       Map<String, ForestNode> nodes, ObstacleMask pass,
                                       ObstacleIndex obstacleIndex,
                                       Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored) {
        ForestNode from = nodes.get(victim.getFromNodeId());
        ForestNode to = nodes.get(victim.getToNodeId());
        if (from == null || to == null) {
            return null;
        }
        Coordinate start = from.getCoordinate();
        Coordinate goal = to.getCoordinate();
        // E50: терминальный хвост `target→point` неприкосновенен — перепрокладываем
        // только ствол до `target`, затем возвращаем канонический хвост.
        List<Coordinate> tail = List.of();
        if (to.getType() == NodeType.CONNECTION_POINT && victim.getCoordinates().size() >= 2) {
            List<Coordinate> victimCoords = victim.getCoordinates();
            int last = victimCoords.size() - 1;
            goal = victimCoords.get(last - 1);
            tail = List.of(goal, victimCoords.get(last));
        }
        List<Geometry> extras = new ArrayList<>();
        List<Geometry> clearance = new ArrayList<>();
        for (ForestNode node : nodes.values()) {
            clearance.add(GeometrySupport.GEOMETRY_FACTORY.createPoint(node.getCoordinate())
                    .buffer(3.0));
        }
        for (ForestEdge edge : all) {
            if (edge.getId().equals(victim.getId())) {
                continue;
            }
            Geometry buffer = line(edge).buffer(1.0);
            for (Geometry disk : clearance) {
                if (buffer.intersects(disk)) {
                    buffer = buffer.difference(disk);
                }
            }
            if (!buffer.isEmpty()) {
                extras.add(buffer);
            }
        }
        Coordinate incoming = incomingDirection(victim, all);
        Coordinate nextAfter = tail.isEmpty() ? null : tail.get(1);
        List<Coordinate> path = gridPath(pass, obstacleIndex.withAdditional(extras), start, incoming,
                goal, nextAfter, ignored == null ? Set.of() : ignored, 40000, false);
        if (path == null && avoid != null) {
            // Fallback: обходим только пересекаемое ребро (без остальных).
            List<Geometry> onlyAvoid = new ArrayList<>();
            Geometry buffer = line(avoid).buffer(1.0);
            for (Geometry disk : clearance) {
                if (buffer.intersects(disk)) {
                    buffer = buffer.difference(disk);
                }
            }
            if (!buffer.isEmpty()) {
                onlyAvoid.add(buffer);
            }
            path = gridPath(pass, obstacleIndex.withAdditional(onlyAvoid), start, incoming, goal,
                    nextAfter, ignored == null ? Set.of() : ignored, 40000, false);
        }
        if (path == null || path.size() < 2) {
            return null;
        }
        if (!tail.isEmpty()) {
            path = new ArrayList<>(path);
            path.add(tail.get(1));
        }
        return ForestEdge.builder()
                .id(victim.getId())
                .fromNodeId(victim.getFromNodeId())
                .toNodeId(victim.getToNodeId())
                .coordinates(path)
                .flowTph(victim.getFlowTph())
                .diameterMm(victim.getDiameterMm())
                .build();
    }

    /** Направление подхода к началу ребра по другому инцидентному ребру. */
    private Coordinate incomingDirection(ForestEdge victim, List<ForestEdge> all) {
        for (ForestEdge edge : all) {
            if (edge.getId().equals(victim.getId())) {
                continue;
            }
            if (edge.getToNodeId().equals(victim.getFromNodeId())
                    && edge.getCoordinates().size() >= 2) {
                return edge.getCoordinates().get(edge.getCoordinates().size() - 2);
            }
            if (edge.getFromNodeId().equals(victim.getFromNodeId())
                    && edge.getCoordinates().size() >= 2) {
                return edge.getCoordinates().get(1);
            }
        }
        return null;
    }

    private boolean sharesNode(ForestEdge first, ForestEdge second) {
        return first.getFromNodeId().equals(second.getFromNodeId())
                || first.getFromNodeId().equals(second.getToNodeId())
                || first.getToNodeId().equals(second.getFromNodeId())
                || first.getToNodeId().equals(second.getToNodeId());
    }

    private boolean crosses(ForestEdge first, ForestEdge second) {
        return !line(first).intersection(line(second)).isEmpty();
    }

    private LineString line(ForestEdge edge) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                edge.getCoordinates().toArray(new Coordinate[0]));
    }

    /** Растр {@code Kспец} и (опционально) маска угловых спецзон для поиска. */
    private static final class ZoneRasters {
        private final double[] k;
        private final long[] angleMask;

        private ZoneRasters(double[] k, long[] angleMask) {
            this.k = k;
            this.angleMask = angleMask;
        }
    }

    /**
     * E25-04: растор {@code Kспец} по клеткам (максимум при наложении зон).
     * Клетки вне спецзон имеют коэффициент 1.0. E41: параллельно строится
     * маска клеток, близких к оси угловой зоны (полоса {@code 2·cell}), чтобы
     * вызывать точный {@code angleOk} только у зон, а не на каждом ребре роста.
     */
    private ZoneRasters zoneRasters(SpecialZoneIndex specialZones, ObstacleMask pass) {
        int width = pass.width();
        int height = pass.height();
        double[] k = new double[width * height];
        Arrays.fill(k, 1.0);
        if (specialZones == null || specialZones.size() == 0) {
            return new ZoneRasters(k, null);
        }
        long[] angleMask = specialZones.hasAngleZones()
                ? new long[(width * height + 63) >>> 6] : null;
        double cell = pass.cellM();
        Coordinate probe = new Coordinate();
        for (SpecialZone zone : specialZones.zones()) {
            Geometry geometry = zone.getZone();
            if (geometry == null || geometry.isEmpty()) {
                continue;
            }
            Geometry band = angleMask != null && zone.getAngleMinDeg() != null
                    && zone.getAxis() != null ? zone.getAxis().buffer(2.0 * cell) : null;
            Envelope envelope = new Envelope(geometry.getEnvelopeInternal());
            if (band != null) {
                envelope.expandToInclude(band.getEnvelopeInternal());
            }
            double rowSpacing = pass.rowSpacing();
            int c0 = Math.max(0, (int) Math.floor((envelope.getMinX() - pass.originX()) / cell) - 1);
            int c1 = Math.min(width - 1,
                    (int) Math.floor((envelope.getMaxX() - pass.originX()) / cell) + 1);
            int r0 = Math.max(0,
                    (int) Math.floor((envelope.getMinY() - pass.originY()) / rowSpacing) - 1);
            int r1 = Math.min(height - 1,
                    (int) Math.floor((envelope.getMaxY() - pass.originY()) / rowSpacing) + 1);
            PreparedGeometry zonePrepared = PreparedGeometryFactory.prepare(geometry);
            PreparedGeometry bandPrepared = band == null ? null
                    : PreparedGeometryFactory.prepare(band);
            double kSpecial = zone.getKSpecial();
            for (int row = r0; row <= r1; row++) {
                for (int col = c0; col <= c1; col++) {
                    probe.x = pass.centerX(col, row);
                    probe.y = pass.centerY(col, row);
                    Geometry point = GeometrySupport.GEOMETRY_FACTORY.createPoint(probe);
                    if (zonePrepared.covers(point)) {
                        int index = row * width + col;
                        if (kSpecial > k[index]) {
                            k[index] = kSpecial;
                        }
                    }
                    if (bandPrepared != null && bandPrepared.covers(point)) {
                        int index = row * width + col;
                        angleMask[index >>> 6] |= 1L << (index & 63);
                    }
                }
            }
        }
        if (angleMask != null) {
            long bits = 0L;
            for (long word : angleMask) {
                bits += Long.bitCount(word);
            }
            log.info("Angle mask: bits={} zones={}", bits, specialZones.size());
        }
        return new ZoneRasters(k, angleMask);
    }

    private boolean bitSet(long[] bits, int index) {
        return (bits[index >>> 6] & (1L << (index & 63))) != 0;
    }

    /** E25-04: максимальный {@code Kспец} зон, накрывающих ребро (иначе 1.0). */
    private double maxKSpecial(ForestEdge edge, SpecialZoneIndex specialZones,
                               List<String> ignoredWarnings) {
        if (specialZones == null || specialZones.size() == 0
                || edge.getCoordinates().size() < 2) {
            return 1.0;
        }
        LineString line = GeometrySupport.GEOMETRY_FACTORY.createLineString(
                edge.getCoordinates().toArray(new Coordinate[0]));
        double k = 1.0;
        for (SpecialSpan span : specialZones.spans(line, ignoredWarnings)) {
            k = Math.max(k, span.getKSpecial());
        }
        return k;
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
                                         ObstacleIndex obstacleIndex, SpecialZoneIndex specialZones,
                                         Map<Integer, Terminal> terminalCells,
                                         Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                                 ownObstacles,
                                         ExistingNetworkGraph graph, List<String> warnings) {
        Map<String, Terminal> terminalsById = new HashMap<>();
        for (Terminal terminal : new LinkedHashSet<>(terminalCells.values())) {
            terminalsById.put(terminal.pointId, terminal);
        }
        Set<String> terminalNodes = new HashSet<>(terminalsById.keySet());
        int count = trees.size();
        ForestTree[] refinedTrees = new ForestTree[count];
        @SuppressWarnings("unchecked")
        List<String>[] localWarnings = new List[count];
        long[][] timings = new long[count][7];
        // Деревья уточняются независимо: распараллеливаем, порядок сохраняется по
        // индексу; предупреждения собираются по дереву и сливаются по порядку.
        java.util.stream.IntStream.range(0, count).parallel().forEach(index -> {
            List<String> treeWarnings = new ArrayList<>();
            refinedTrees[index] = refineTree(trees.get(index), pass, obstacleIndex, specialZones,
                    terminalsById, ownObstacles, graph, terminalNodes, treeWarnings,
                    timings[index]);
            localWarnings[index] = treeWarnings;
        });
        long tEdge = 0L;
        long tNodeTurns = 0L;
        long tInteriorTurns = 0L;
        long tSimplify = 0L;
        long tCrossings = 0L;
        long tExitJoints = 0L;
        long tMaxLength = 0L;
        List<ForestTree> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(refinedTrees[index]);
            warnings.addAll(localWarnings[index]);
            tEdge += timings[index][0];
            tNodeTurns += timings[index][1];
            tInteriorTurns += timings[index][2];
            tSimplify += timings[index][3];
            tCrossings += timings[index][4];
            tExitJoints += timings[index][5];
            tMaxLength += timings[index][6];
        }
        // E27-05: меж-древесные пересечения (резолвер внутри дерева их не видит).
        long globalStart = System.nanoTime();
        result = repairGlobalCrossings(result, pass, obstacleIndex, warnings);
        long tGlobal = elapsedMs(globalStart);
        log.info("Refine breakdown: edge={}ms nodeTurns={}ms interiorTurns={}ms simplify={}ms "
                        + "crossings={}ms exitJoints={}ms maxLength={}ms globalCrossings={}ms",
                tEdge, tNodeTurns, tInteriorTurns, tSimplify, tCrossings, tExitJoints, tMaxLength,
                tGlobal);
        return result;
    }

    private ForestTree refineTree(ForestTree tree, ObstacleMask pass, ObstacleIndex obstacleIndex,
                                  SpecialZoneIndex specialZones,
                                  Map<String, Terminal> terminalsById,
                                  Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                          ownObstacles,
                                  ExistingNetworkGraph graph, Set<String> terminalNodes,
                                  List<String> warnings, long[] timings) {
        Map<String, ForestNode> nodes = tree.getNodes();
        Map<String, String> parent = parentByRoot(tree);
        List<ForestEdge> edges = new ArrayList<>();
        long edgeStart = System.nanoTime();
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
                refined = new ArrayList<>(refine(trunk, obstacleIndex, specialZones, startPrevious,
                        endNext, true));
                refined.add(point);
            } else {
                refined = new ArrayList<>(refine(coordinates, obstacleIndex, specialZones, startPrevious,
                        endNext, isTerminal));
            }
            if (isTerminal && exitGridDogleg()) {
                refined = rebuildExitJoint(refined, pass, obstacleIndex);
            }
            if (isTerminal && !terminal.tail.isEmpty() && refined.size() >= 3
                    && !turnAllowed(refined.get(refined.size() - 3),
                            refined.get(refined.size() - 2), refined.get(refined.size() - 1))) {
                List<Coordinate> fixed = repairExitApproach(refined, startPrevious, pass,
                        obstacleIndex, ownObstacles, edge.getToNodeId());
                if (fixed != null) {
                    // E43/E44: repairExitApproach уже turn-aware; повторный
                    // refine удалял бы вставленную «пятку» и возвращал излом.
                    refined = fixed;
                }
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
        timings[0] = elapsedMs(edgeStart);
        // ADR-0043: refine — владелец углов; чиним повороты во всех узлах
        // (включая пары «ветка↔ветка»), relink углы не проверяет.
        long stageStart = System.nanoTime();
        edges = repairNodeTurns(edges, nodes, pass, obstacleIndex, ownObstacles, warnings);
        timings[1] = elapsedMs(stageStart);
        // E23-04: недопустимые повороты не только в узлах, но и во внутренних
        // вершинах рёбер (после relink стыки геометрий дают изломы/шипы).
        stageStart = System.nanoTime();
        edges = repairInteriorTurns(edges, pass, obstacleIndex, ownObstacles, terminalNodes,
                warnings);
        timings[2] = elapsedMs(stageStart);
        // FR-28: убрать ступенчатые фрагменты (лишние вершины), не создавая
        // нарушений углов и не задевая запреты.
        stageStart = System.nanoTime();
        edges = simplifyEdges(edges, obstacleIndex, specialZones, ownObstacles);
        timings[3] = elapsedMs(stageStart);
        // E27/ADR-0047: пересечения рёбер вне общих узлов (FR-29) —
        // перепроложение «жертвы» средствами сетки.
        stageStart = System.nanoTime();
        edges = repairCrossings(edges, nodes, pass, obstacleIndex, ownObstacles, warnings);
        timings[4] = elapsedMs(stageStart);
        // E44: финальный ремонт стыка вывода (после правок ствола) — target
        // сохраняется, подход перестраивается turn-aware.
        stageStart = System.nanoTime();
        edges = repairExitJoints(edges, terminalNodes, pass, obstacleIndex, ownObstacles);
        timings[5] = elapsedMs(stageStart);
        stageStart = System.nanoTime();
        try {
            edges = maxLengthEnforcer.enforce(edges, tree.getTieInNodeId());
        } catch (IllegalArgumentException noDiameter) {
            warnings.add("FOREST_MAX_LENGTH_UNRESOLVED: " + noDiameter.getMessage());
        }
        timings[6] = elapsedMs(stageStart);
        warnChamberDegree(edges, nodes, graph, warnings);
        return ForestTree.builder().tieInNodeId(tree.getTieInNodeId())
                .nodes(nodes).edges(edges).build();
    }

    /**
     * E35: оптимизация точки врезки корня (только для новых камер) — перпендикулярная
     * проекция на существующую сеть, если корневое ребро становится короче и при
     * этом не нарушаются запреты, углы на ветвлении и самопересечения.
     */
    private List<ForestTree> optimizeRoots(List<ForestTree> trees, NetworkDataset dataset,
                                           ObstacleMask pass, ObstacleIndex obstacleIndex,
                                           List<String> warnings) {
        int count = trees.size();
        ForestTree[] optimized = new ForestTree[count];
        @SuppressWarnings("unchecked")
        List<String>[] localWarnings = new List[count];
        // Корни деревьев оптимизируются независимо: распараллеливаем по индексу.
        java.util.stream.IntStream.range(0, count).parallel().forEach(index -> {
            List<String> treeWarnings = new ArrayList<>();
            optimized[index] = optimizeRoot(trees.get(index), dataset, pass, obstacleIndex,
                    treeWarnings);
            localWarnings[index] = treeWarnings;
        });
        List<ForestTree> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            result.add(optimized[index]);
            warnings.addAll(localWarnings[index]);
        }
        return result;
    }

    private ForestTree optimizeRoot(ForestTree tree, NetworkDataset dataset, ObstacleMask pass,
                                    ObstacleIndex obstacleIndex, List<String> warnings) {
        ForestNode root = tree.getNodes().get(tree.getTieInNodeId());
        if (root == null || root.isExisting()) {
            return tree;
        }
        List<ForestEdge> rootEdges = new ArrayList<>();
        for (ForestEdge edge : tree.getEdges()) {
            if (edge.getFromNodeId().equals(root.getId())) {
                rootEdges.add(edge);
            }
        }
        if (rootEdges.size() != 1) {
            return tree;
        }
        ForestEdge rootEdge = rootEdges.get(0);
        ForestNode branch = tree.getNodes().get(rootEdge.getToNodeId());
        List<Coordinate> edgeCoords = rootEdge.getCoordinates();
        if (branch == null || edgeCoords.size() < 2) {
            return tree;
        }
        boolean terminal = branch.getType() == NodeType.CONNECTION_POINT;
        // E45: у терминального ребра двигаем только корневую (первую) вершину,
        // сохраняя выход и остальную геометрию.
        Coordinate anchor = terminal ? edgeCoords.get(1) : branch.getCoordinate();
        double currentLen = root.getCoordinate().distance(anchor);
        Coordinate best = null;
        double bestLen = currentLen;
        List<Coordinate> bestConnector = null;
        for (TieInCandidate candidate : candidateProvider.projections(dataset, anchor)) {
            Coordinate q = candidate.getCoordinate();
            double len = q.distance(anchor);
            if (len >= bestLen - 0.5) {
                continue;
            }
            boolean blocked = obstacleIndex != null
                    && obstacleIndex.isInteriorBlocked(line(q, anchor));
            if (!blocked && terminal && edgeCoords.size() >= 3
                    && !turnAllowed(q, anchor, edgeCoords.get(2))) {
                blocked = true;
            }
            if (!blocked && !terminal && !rootConnectorTurnsOk(q, branch, tree)) {
                blocked = true;
            }
            if (!blocked && rootConnectorCrosses(q, anchor, tree, rootEdge)) {
                blocked = true;
            }
            List<Coordinate> connector = null;
            if (blocked && terminal) {
                // Fallback: коннектор по сетке в обход запретов.
                Coordinate nextAfter = edgeCoords.size() >= 3 ? edgeCoords.get(2) : null;
                List<Coordinate> path = gridPath(pass, obstacleIndex, q, null, anchor, nextAfter,
                        Set.of(), 20000);
                if (path != null && path.size() >= 2) {
                    double pathLen = 0.0;
                    for (int k = 0; k + 1 < path.size(); k++) {
                        pathLen += path.get(k).distance(path.get(k + 1));
                    }
                    if (pathLen < bestLen - 0.5 && !rootConnectorCrosses(q, anchor, tree,
                            rootEdge)) {
                        connector = path;
                        len = pathLen;
                    }
                }
            }
            if (blocked && connector == null) {
                continue;
            }
            if (len < bestLen - 0.5) {
                best = q;
                bestLen = len;
                bestConnector = connector;
            }
        }
        if (best == null) {
            return tree;
        }
        Map<String, ForestNode> nodes = new LinkedHashMap<>(tree.getNodes());
        nodes.put(root.getId(), root.toBuilder().coordinate(best).build());
        List<ForestEdge> edges = new ArrayList<>(tree.getEdges());
        for (int i = 0; i < edges.size(); i++) {
            ForestEdge edge = edges.get(i);
            if (edge.getId().equals(rootEdge.getId())) {
                List<Coordinate> coordinates;
                if (terminal && bestConnector != null) {
                    coordinates = new ArrayList<>(bestConnector);
                    if (edgeCoords.size() > 2) {
                        coordinates.addAll(edgeCoords.subList(2, edgeCoords.size()));
                    }
                } else if (terminal) {
                    coordinates = new ArrayList<>(edgeCoords);
                    coordinates.set(0, new Coordinate(best));
                } else {
                    coordinates = List.of(new Coordinate(best),
                            new Coordinate(branch.getCoordinate()));
                }
                edges.set(i, ForestEdge.builder().id(edge.getId())
                        .fromNodeId(root.getId()).toNodeId(branch.getId())
                        .coordinates(coordinates)
                        .flowTph(edge.getFlowTph()).diameterMm(edge.getDiameterMm()).build());
            }
        }
        warnings.add("FOREST_ROOT_OPTIMIZED: " + root.getId() + " короче на "
                + Math.round(currentLen - bestLen) + " м");
        return ForestTree.builder().tieInNodeId(tree.getTieInNodeId()).nodes(nodes)
                .edges(edges).build();
    }

    /**
     * ADR-0051: оптимизация положения новых камер (включая корень) — сдвиг к
     * геометрической медиане соседних вершин (для корня — к проекциям на
     * существующую сеть) с локальной перепрокладкой стыков; принимается строго
     * лучшее по S при сохранении длины, углов, запретов и выходов.
     */
    private List<ForestTree> optimizeChambers(List<ForestTree> trees, NetworkDataset dataset,
                                              ObstacleMask pass, ObstacleIndex obstacleIndex,
                                              SpecialZoneIndex specialZones,
                                              Map<Integer, Terminal> terminalCells,
                                              Map<String, Set<PreparedGeometry>> ownObstacles) {
        long start = System.nanoTime();
        int count = trees.size();
        ForestTree[] result = new ForestTree[count];
        java.util.stream.IntStream.range(0, count).parallel().forEach(index -> result[index] =
                optimizeChamberTree(trees.get(index), dataset, pass, obstacleIndex, specialZones,
                        ownObstacles));
        log.info("Chamber optimization: trees={} time={}ms", count, elapsedMs(start));
        return new ArrayList<>(Arrays.asList(result));
    }

    private ForestTree optimizeChamberTree(ForestTree tree, NetworkDataset dataset, ObstacleMask pass,
                                           ObstacleIndex obstacleIndex,
                                           SpecialZoneIndex specialZones,
                                           Map<String, Set<PreparedGeometry>> ownObstacles) {
        Map<String, ForestNode> nodes = new LinkedHashMap<>(tree.getNodes());
        List<ForestEdge> edges = new ArrayList<>(tree.getEdges());
        String rootId = tree.getTieInNodeId();
        int passes = Math.max(1, appProperties.getForestChamberPasses());
        for (int passIndex = 0; passIndex < passes; passIndex++) {
            List<String> movable = new ArrayList<>();
            for (ForestNode node : nodes.values()) {
                if (node.getType() == NodeType.CHAMBER && !node.isExisting()) {
                    movable.add(node.getId());
                }
            }
            Collections.sort(movable);
            boolean changed = false;
            for (String nodeId : movable) {
                if (relocateChamber(nodeId, nodes, edges, rootId, dataset, pass, obstacleIndex,
                        specialZones, ownObstacles)) {
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return ForestTree.builder().tieInNodeId(rootId).nodes(nodes).edges(edges).build();
    }

    private boolean relocateChamber(String nodeId, Map<String, ForestNode> nodes,
                                    List<ForestEdge> edges, String rootId, NetworkDataset dataset,
                                    ObstacleMask pass, ObstacleIndex obstacleIndex,
                                    SpecialZoneIndex specialZones,
                                    Map<String, Set<PreparedGeometry>> ownObstacles) {
        ForestNode node = nodes.get(nodeId);
        if (node == null) {
            return false;
        }
        List<Integer> incident = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            ForestEdge edge = edges.get(i);
            if (edge.getFromNodeId().equals(nodeId) || edge.getToNodeId().equals(nodeId)) {
                incident.add(i);
            }
        }
        if (incident.isEmpty()) {
            return false;
        }
        Set<Integer> incidentSet = new HashSet<>(incident);
        List<String> ignoredWarnings = new ArrayList<>();
        List<Stub> stubs = new ArrayList<>();
        for (int index : incident) {
            Stub stub = stub(index, edges.get(index), nodeId, ownObstacles, specialZones,
                    ignoredWarnings);
            if (stub == null) {
                return false;
            }
            stubs.add(stub);
        }
        double currentStubSum = 0.0;
        for (Stub stub : stubs) {
            currentStubSum += stub.oldStubLen;
        }
        boolean isRoot = nodeId.equals(rootId);
        List<Coordinate> candidates = chamberCandidates(node, stubs, isRoot, dataset, pass);
        // Выбор кандидата по дешёвой локальной дельте стоимости стыков; полная
        // пересборка (enforce + оценка S) — только для лучшего кандидата.
        long bestCheap = 0L;
        Coordinate bestPosition = null;
        Map<Integer, ForestEdge> bestReplaced = null;
        for (Coordinate candidate : candidates) {
            // Дешёвый префильтр: даже без обхода новый ствол не может быть
            // короче суммы прямых отрезков «кандидат → сосед».
            double straightSum = 0.0;
            for (Stub stub : stubs) {
                straightSum += candidate.distance(stub.endpoint);
            }
            if (straightSum >= currentStubSum - EPS) {
                continue;
            }
            ChamberMove move = tryChamberMove(candidate, stubs, edges, incidentSet, obstacleIndex,
                    specialZones, pass, isRoot);
            if (move == null || move.stubSum >= currentStubSum - EPS) {
                continue;
            }
            long cheap = 0L;
            for (int i = 0; i < stubs.size(); i++) {
                Stub stub = stubs.get(i);
                cheap += costModel.segmentCost(stub.oldRestLen + move.stubLens.get(i), stub.dn, 1.0,
                        stub.kSpecial)
                        - costModel.segmentCost(stub.oldRestLen + stub.oldStubLen, stub.dn, 1.0,
                                stub.kSpecial);
            }
            if (cheap < bestCheap - EPS) {
                bestCheap = cheap;
                bestPosition = candidate;
                bestReplaced = move.replaced;
            }
        }
        if (bestReplaced == null) {
            return false;
        }
        List<ForestEdge> candidateEdges = new ArrayList<>(edges);
        for (Map.Entry<Integer, ForestEdge> entry : bestReplaced.entrySet()) {
            candidateEdges.set(entry.getKey(), entry.getValue());
        }
        try {
            candidateEdges = maxLengthEnforcer.enforce(candidateEdges, rootId);
        } catch (IllegalArgumentException noDiameter) {
            return false;
        }
        Map<String, ForestNode> candidateNodes = new LinkedHashMap<>(nodes);
        candidateNodes.put(nodeId, node.toBuilder().coordinate(bestPosition).build());
        double currentScore = treeScore(nodes, edges, rootId, specialZones);
        if (treeScore(candidateNodes, candidateEdges, rootId, specialZones) >= currentScore - EPS) {
            return false;
        }
        edges.clear();
        edges.addAll(candidateEdges);
        nodes.put(nodeId, node.toBuilder().coordinate(bestPosition).build());
        return true;
    }

    private Stub stub(int edgeIndex, ForestEdge edge, String nodeId,
                      Map<String, Set<PreparedGeometry>> ownObstacles,
                      SpecialZoneIndex specialZones, List<String> ignoredWarnings) {
        List<Coordinate> coords = edge.getCoordinates();
        if (coords.size() < 2) {
            return null;
        }
        boolean nodeIsFrom = edge.getFromNodeId().equals(nodeId);
        if (!nodeIsFrom && !edge.getToNodeId().equals(nodeId)) {
            return null;
        }
        String farId = nodeIsFrom ? edge.getToNodeId() : edge.getFromNodeId();
        Coordinate endpoint = nodeIsFrom ? coords.get(1) : coords.get(coords.size() - 2);
        Coordinate nextAfter = coords.size() > 2
                ? (nodeIsFrom ? coords.get(2) : coords.get(coords.size() - 3)) : null;
        double oldStubLen = nodeIsFrom
                ? coords.get(0).distance(coords.get(1))
                : coords.get(coords.size() - 1).distance(coords.get(coords.size() - 2));
        Stub stub = new Stub();
        stub.edgeIndex = edgeIndex;
        stub.dn = edge.getDiameterMm();
        stub.kSpecial = maxKSpecial(edge, specialZones, ignoredWarnings);
        stub.costPerM = diameters.newCostPerM(edge.getDiameterMm());
        stub.oldStubLen = oldStubLen;
        stub.oldRestLen = edge.lengthM() - oldStubLen;
        stub.endpoint = endpoint;
        stub.nextAfter = nextAfter;
        stub.nodeIsFrom = nodeIsFrom;
        // E50: стык камеры — ствол (не хвост выхода), свой ОКС не игнорируется.
        stub.ignored = Set.of();
        return stub;
    }

    private List<Coordinate> chamberCandidates(ForestNode node, List<Stub> stubs, boolean isRoot,
                                               NetworkDataset dataset, ObstacleMask pass) {
        List<Coordinate> points = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (Stub stub : stubs) {
            points.add(stub.endpoint);
            weights.add(stub.costPerM > 0 ? stub.costPerM : 1.0);
        }
        Coordinate median = weightedMedian(points, weights);
        List<Coordinate> candidates = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        addCandidate(candidates, seen, node.getCoordinate());
        int max = Math.max(1, appProperties.getForestChamberMaxCandidates());
        if (isRoot) {
            // E50: проекции на сеть сортируются по расстоянию до текущего
            // положения корня — иначе ближайшая допустимая точка может попасть
            // за предел `max` из-за порядка участков в наборе.
            List<Coordinate> projections = new ArrayList<>();
            for (TieInCandidate candidate : candidateProvider.projections(dataset, median)) {
                projections.add(candidate.getCoordinate());
            }
            projections.sort(Comparator.comparingDouble(node.getCoordinate()::distance));
            for (Coordinate projection : projections) {
                if (candidates.size() >= max) {
                    break;
                }
                addCandidate(candidates, seen, projection);
            }
        } else {
            int width = pass.width();
            int height = pass.height();
            int cell = pass.cellAt(median.x, median.y);
            int col = cell % width;
            int row = cell / width;
            int radius = (int) Math.ceil(appProperties.getForestChamberSearchRadiusCells());
            List<Coordinate> cells = new ArrayList<>();
            for (int dr = -radius; dr <= radius; dr++) {
                for (int dc = -radius; dc <= radius; dc++) {
                    int c = col + dc;
                    int r = row + dr;
                    if (c < 0 || r < 0 || c >= width || r >= height || pass.blockedCell(c, r)) {
                        continue;
                    }
                    cells.add(new Coordinate(pass.centerX(c, r), pass.centerY(c, r)));
                }
            }
            cells.sort(Comparator.comparingDouble(median::distance));
            for (Coordinate cellCenter : cells) {
                if (candidates.size() >= max) {
                    break;
                }
                addCandidate(candidates, seen, cellCenter);
            }
        }
        return candidates;
    }

    private void addCandidate(List<Coordinate> candidates, Set<String> seen, Coordinate candidate) {
        if (candidate == null) {
            return;
        }
        String key = Math.round(candidate.x * 1000.0) + ":" + Math.round(candidate.y * 1000.0);
        if (seen.add(key)) {
            candidates.add(new Coordinate(candidate));
        }
    }

    private Coordinate weightedMedian(List<Coordinate> points, List<Double> weights) {
        int n = points.size();
        if (n == 1) {
            return points.get(0);
        }
        if (n == 2) {
            return new Coordinate((points.get(0).x + points.get(1).x) / 2.0,
                    (points.get(0).y + points.get(1).y) / 2.0);
        }
        double x = 0.0;
        double y = 0.0;
        double totalWeight = 0.0;
        for (int i = 0; i < n; i++) {
            x += points.get(i).x * weights.get(i);
            y += points.get(i).y * weights.get(i);
            totalWeight += weights.get(i);
        }
        x /= totalWeight;
        y /= totalWeight;
        for (int iteration = 0; iteration < 50; iteration++) {
            double nx = 0.0;
            double ny = 0.0;
            double den = 0.0;
            for (int i = 0; i < n; i++) {
                double distance = Math.sqrt(Math.pow(x - points.get(i).x, 2)
                        + Math.pow(y - points.get(i).y, 2));
                if (distance < 1e-9) {
                    return new Coordinate(points.get(i));
                }
                double w = weights.get(i) / distance;
                nx += points.get(i).x * w;
                ny += points.get(i).y * w;
                den += w;
            }
            double nextX = nx / den;
            double nextY = ny / den;
            if (Math.sqrt(Math.pow(nextX - x, 2) + Math.pow(nextY - y, 2)) < 1e-4) {
                x = nextX;
                y = nextY;
                break;
            }
            x = nextX;
            y = nextY;
        }
        return new Coordinate(x, y);
    }

    private ChamberMove tryChamberMove(Coordinate candidate, List<Stub> stubs, List<ForestEdge> edges,
                                       Set<Integer> incidentSet, ObstacleIndex obstacleIndex,
                                       SpecialZoneIndex specialZones, ObstacleMask pass,
                                       boolean allowFallback) {
        List<Coordinate> firstSteps = new ArrayList<>();
        List<List<Coordinate>> connectors = new ArrayList<>();
        List<Double> stubLens = new ArrayList<>();
        double stubSum = 0.0;
        for (Stub stub : stubs) {
            // Прямой отрезок «кандидат → соседняя вершина» (как в refine/string
            // pulling); если запрет — при `allowFallback` (корень) пробуем
            // обойти по сетке, иначе кандидат отклоняется.
            List<Coordinate> connector;
            if (obstacleIndex == null || !obstacleIndex.isInteriorBlocked(
                    line(candidate, stub.endpoint), stub.ignored)) {
                if (specialZones != null && !specialZones.angleOk(line(candidate, stub.endpoint))) {
                    return null;
                }
                if (!turnAllowed(candidate, stub.endpoint, stub.nextAfter)) {
                    return null;
                }
                connector = List.of(new Coordinate(candidate), new Coordinate(stub.endpoint));
            } else if (allowFallback) {
                List<Coordinate> path = gridPath(pass, obstacleIndex, candidate, null,
                        stub.endpoint, stub.nextAfter, stub.ignored, 40000);
                if (path == null || path.size() < 2
                        || (specialZones != null && !polylineSpecialOk(path, specialZones))) {
                    return null;
                }
                connector = path;
            } else {
                return null;
            }
            connectors.add(connector);
            firstSteps.add(connector.size() >= 2 ? connector.get(1) : stub.endpoint);
            double length = polylineLength(connector);
            stubLens.add(length);
            stubSum += length;
        }
        for (int i = 0; i < firstSteps.size(); i++) {
            for (int j = i + 1; j < firstSteps.size(); j++) {
                if (!turnAllowed(firstSteps.get(i), candidate, firstSteps.get(j))) {
                    return null;
                }
            }
        }
        Map<Integer, ForestEdge> replaced = new HashMap<>();
        for (int s = 0; s < stubs.size(); s++) {
            Stub stub = stubs.get(s);
            List<Coordinate> connector = connectors.get(s);
            ForestEdge old = edges.get(stub.edgeIndex);
            List<Coordinate> coords = new ArrayList<>();
            if (stub.nodeIsFrom) {
                // старый хвост: [chamber, endpoint, rest...]; новый: connector(candidate..endpoint) + rest
                coords.addAll(connector);
                coords.addAll(old.getCoordinates().subList(2, old.getCoordinates().size()));
            } else {
                int count = old.getCoordinates().size();
                coords.addAll(old.getCoordinates().subList(0, count - 2));
                List<Coordinate> reversed = new ArrayList<>(connector);
                Collections.reverse(reversed);
                coords.addAll(reversed);
            }
            replaced.put(stub.edgeIndex, ForestEdge.builder().id(old.getId())
                    .fromNodeId(old.getFromNodeId()).toNodeId(old.getToNodeId())
                    .coordinates(coords).flowTph(old.getFlowTph()).diameterMm(old.getDiameterMm())
                    .build());
        }
        for (ForestEdge replacement : replaced.values()) {
            LineString replacementLine = line(replacement);
            Envelope envelope = replacementLine.getEnvelopeInternal();
            for (int i = 0; i < edges.size(); i++) {
                if (incidentSet.contains(i)) {
                    continue;
                }
                ForestEdge other = edges.get(i);
                if (sharesNode(replacement, other)) {
                    continue;
                }
                LineString otherLine = line(other);
                if (!envelope.intersects(otherLine.getEnvelopeInternal())) {
                    continue;
                }
                if (!replacementLine.intersection(otherLine).isEmpty()) {
                    return null;
                }
            }
        }
        ChamberMove move = new ChamberMove();
        move.replaced = replaced;
        move.stubSum = stubSum;
        move.stubLens = stubLens;
        return move;
    }

    private double treeScore(Map<String, ForestNode> nodes, List<ForestEdge> edges, String rootId,
                             SpecialZoneIndex specialZones) {
        ForestTree tree = ForestTree.builder().tieInNodeId(rootId).nodes(nodes).edges(edges).build();
        return estimateScore(List.of(tree), List.of(), Map.of(), specialZones);
    }

    /** Стык ребра у перемещаемой камеры (для локальной перепрокладки). */
    private static final class Stub {
        private int edgeIndex;
        private int dn;
        private double kSpecial;
        private double costPerM;
        private double oldStubLen;
        private double oldRestLen;
        private Coordinate endpoint;
        private Coordinate nextAfter;
        private boolean nodeIsFrom;
        private Set<PreparedGeometry> ignored;
    }

    private static final class ChamberMove {
        private Map<Integer, ForestEdge> replaced;
        private double stubSum;
        private List<Double> stubLens;
    }

    private boolean rootConnectorTurnsOk(Coordinate q, ForestNode branch, ForestTree tree) {
        for (ForestEdge edge : tree.getEdges()) {
            if (!edge.getFromNodeId().equals(branch.getId()) || edge.getCoordinates().size() < 2) {
                continue;
            }
            if (!turnAllowed(q, branch.getCoordinate(), edge.getCoordinates().get(1))) {
                return false;
            }
        }
        return true;
    }

    private boolean rootConnectorCrosses(Coordinate q, Coordinate anchor, ForestTree tree,
                                         ForestEdge rootEdge) {
        LineString connector = GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{q, anchor});
        for (ForestEdge edge : tree.getEdges()) {
            if (edge.getId().equals(rootEdge.getId())) {
                continue;
            }
            List<Coordinate> coords = edge.getCoordinates();
            if ((!coords.isEmpty() && coords.get(0).distance(anchor) < 1e-6)
                    || (!coords.isEmpty()
                            && coords.get(coords.size() - 1).distance(anchor) < 1e-6)) {
                continue;
            }
            if (!connector.intersection(line(edge)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * E27-05: разрешение пересечений рёбер разных деревьев (FR-29). Пересекающаяся
     * пара (без общих узлов) — «жертва» (меньшее по длине) перепрокладывается через
     * {@code gridPath}, где препятствиями служат рёбра **всех** деревьев, а вокруг
     * всех узлов делаются вырезы (чтобы сохранить стыки).
     */
    private List<ForestTree> repairGlobalCrossings(List<ForestTree> trees, ObstacleMask pass,
                                                   ObstacleIndex obstacleIndex,
                                                   List<String> warnings) {
        if (trees.size() < 2) {
            return trees;
        }
        List<List<ForestEdge>> treeEdges = new ArrayList<>();
        Map<String, ForestNode> allNodes = new LinkedHashMap<>();
        for (ForestTree tree : trees) {
            treeEdges.add(new ArrayList<>(tree.getEdges()));
            allNodes.putAll(tree.getNodes());
        }
        List<ForestEdge> allEdges = new ArrayList<>();
        for (List<ForestEdge> edges : treeEdges) {
            allEdges.addAll(edges);
        }
        Set<String> reported = new HashSet<>();
        int maxPasses = Math.max(4, localPasses() * 2);
        for (int passIndex = 0; passIndex < maxPasses; passIndex++) {
            boolean changed = false;
            for (int a = 0; a < treeEdges.size() && !changed; a++) {
                for (int b = a + 1; b < treeEdges.size() && !changed; b++) {
                    for (ForestEdge first : treeEdges.get(a)) {
                        for (ForestEdge second : treeEdges.get(b)) {
                            if (sharesNode(first, second) || !crosses(first, second)) {
                                continue;
                            }
                            int treeIndex = line(first).getLength() <= line(second).getLength() ? a : b;
                            ForestEdge victim = treeIndex == a ? first : second;
                            ForestEdge counterpart = victim == first ? second : first;
                            ForestEdge rebuilt = rebuildCrossing(victim, allEdges, counterpart,
                                    allNodes, pass, obstacleIndex, Set.of());
                            if (rebuilt != null) {
                                List<ForestEdge> edges = treeEdges.get(treeIndex);
                                edges.set(edges.indexOf(victim), rebuilt);
                                allEdges.remove(victim);
                                allEdges.add(rebuilt);
                                changed = true;
                                break;
                            }
                            if (reported.add(first.getId() + "|" + second.getId())) {
                                warnings.add("FOREST_CROSSING_UNRESOLVED: участки " + first.getId()
                                        + " и " + second.getId());
                            }
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        List<ForestTree> result = new ArrayList<>(trees.size());
        for (int i = 0; i < trees.size(); i++) {
            result.add(ForestTree.builder().tieInNodeId(trees.get(i).getTieInNodeId())
                    .nodes(trees.get(i).getNodes()).edges(treeEdges.get(i)).build());
        }
        return result;
    }

    /**
     * E26-02/FR-26: диагностика превышения степени камеры с учётом существующих
     * примыканий (проходная линия = 2). Существующая камера без смены Ду занимает
     * два примыкания.
     */
    private void warnChamberDegree(List<ForestEdge> edges, Map<String, ForestNode> nodes,
                                   ExistingNetworkGraph graph, List<String> warnings) {
        int max = appProperties.getForestMaxChamberDegree();
        if (max <= 0 || graph == null) {
            return;
        }
        Map<String, Integer> degree = new HashMap<>();
        for (ForestEdge edge : edges) {
            degree.merge(edge.getFromNodeId(), 1, Integer::sum);
            degree.merge(edge.getToNodeId(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : degree.entrySet()) {
            ForestNode node = nodes.get(entry.getKey());
            if (node == null || !node.isExisting()) {
                continue;
            }
            int total = entry.getValue() + graph.chamberAttachments(entry.getKey());
            if (total > max) {
                warnings.add("FOREST_CHAMBER_DEGREE_EXCEEDED: узел " + entry.getKey()
                        + ", примыканий " + total + " > " + max);
            }
        }
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

    /**
     * ADR-0043: ремонт поворотов во всех узлах дерева. Для каждой пары
     * инцидентных рёбер угол на узле должен быть ≤ {@code forest-max-turn-deg};
     * иначе в одно из рёбер рядом с узлом вставляется вершина-via по соседям
     * сетки, разбивающая поворот на два допустимых. Если починить не удалось —
     * предупреждение {@code FOREST_TURN_UNRESOLVED}.
     */
    private List<ForestEdge> repairNodeTurns(List<ForestEdge> edges, Map<String, ForestNode> nodes,
                                             ObstacleMask pass, ObstacleIndex obstacleIndex,
                                             Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                                     ownObstacles,
                                             List<String> warnings) {
        List<MutableEdge> work = new ArrayList<>();
        for (ForestEdge edge : edges) {
            work.add(new MutableEdge(edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                    new ArrayList<>(edge.getCoordinates())));
        }
        int passes = Math.max(4, localPasses() * 2);
        for (int p = 0; p < passes; p++) {
            Map<String, List<Integer>> incident = new HashMap<>();
            for (int i = 0; i < work.size(); i++) {
                MutableEdge edge = work.get(i);
                incident.computeIfAbsent(edge.from, key -> new ArrayList<>()).add(i);
                incident.computeIfAbsent(edge.to, key -> new ArrayList<>()).add(i);
            }
            boolean repaired = false;
            for (Map.Entry<String, List<Integer>> entry : incident.entrySet()) {
                String node = entry.getKey();
                List<Integer> ids = entry.getValue();
                ForestNode forestNode = nodes.get(node);
                if (forestNode == null || ids.size() < 2) {
                    continue;
                }
                Coordinate vertex = forestNode.getCoordinate();
                for (int a = 0; a < ids.size() && !repaired; a++) {
                    for (int b = a + 1; b < ids.size() && !repaired; b++) {
                        MutableEdge first = work.get(ids.get(a));
                        MutableEdge second = work.get(ids.get(b));
                        Coordinate incoming = neighborAt(first, node);
                        Coordinate outgoing = neighborAt(second, node);
                        if (incoming == null || outgoing == null) {
                            continue;
                        }
                        if (turnAllowed(incoming, vertex, outgoing)) {
                            continue;
                        }
                        if (rerouteAtNode(work, ids.get(b), node, incoming, vertex, outgoing, pass,
                                obstacleIndex, ownObstacles)) {
                            repaired = true;
                            break;
                        }
                        if (rerouteAtNode(work, ids.get(a), node, outgoing, vertex, incoming, pass,
                                obstacleIndex, ownObstacles)) {
                            repaired = true;
                        }
                    }
                }
            }
            if (!repaired) {
                break;
            }
        }
        List<ForestEdge> result = new ArrayList<>();
        for (int i = 0; i < work.size(); i++) {
            MutableEdge edge = work.get(i);
            result.add(ForestEdge.builder().id(edge.id).fromNodeId(edge.from)
                    .toNodeId(edge.to).coordinates(edge.coords)
                    .flowTph(edges.get(i).getFlowTph()).diameterMm(edges.get(i).getDiameterMm())
                    .build());
        }
        for (Map.Entry<String, List<Integer>> entry : incident(work).entrySet()) {
            List<Integer> ids = entry.getValue();
            if (ids.size() < 2) {
                continue;
            }
            ForestNode forestNode = nodes.get(entry.getKey());
            if (forestNode == null) {
                continue;
            }
            Coordinate vertex = forestNode.getCoordinate();
            for (int a = 0; a < ids.size(); a++) {
                for (int b = a + 1; b < ids.size(); b++) {
                    Coordinate incoming = neighborAt(work.get(ids.get(a)), entry.getKey());
                    Coordinate outgoing = neighborAt(work.get(ids.get(b)), entry.getKey());
                    if (incoming != null && outgoing != null
                            && !turnAllowed(incoming, vertex, outgoing)) {
                        warnings.add("FOREST_TURN_UNRESOLVED: узел " + entry.getKey());
                    }
                }
            }
        }
        return result;
    }

    private Map<String, List<Integer>> incident(List<MutableEdge> edges) {
        Map<String, List<Integer>> incident = new HashMap<>();
        for (int i = 0; i < edges.size(); i++) {
            MutableEdge edge = edges.get(i);
            incident.computeIfAbsent(edge.from, key -> new ArrayList<>()).add(i);
            incident.computeIfAbsent(edge.to, key -> new ArrayList<>()).add(i);
        }
        return incident;
    }

    /**
     * E23-04: устранить недопустимые повороты во внутренних вершинах рёбер
     * (после relink/refine): сначала удаляем вершину-шип, затем вставляем
     * промежуточную вершину по сетке, разбивая поворот на два допустимых.
     */
    private List<ForestEdge> repairInteriorTurns(List<ForestEdge> edges, ObstacleMask pass,
                                                 ObstacleIndex obstacleIndex,
                                                 Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                                         ownObstacles,
                                                 Set<String> terminalNodes, List<String> warnings) {
        List<MutableEdge> work = new ArrayList<>();
        for (ForestEdge edge : edges) {
            work.add(new MutableEdge(edge.getId(), edge.getFromNodeId(), edge.getToNodeId(),
                    new ArrayList<>(edge.getCoordinates())));
        }
        int passes = Math.max(4, localPasses() * 2);
        for (int p = 0; p < passes; p++) {
            boolean changed = false;
            for (MutableEdge edge : work) {
                boolean terminal = terminalNodes != null
                        && (terminalNodes.contains(edge.to) || terminalNodes.contains(edge.from));
                if (repairEdgeTurns(edge, pass, obstacleIndex, ownObstacles, terminal)) {
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        List<ForestEdge> result = new ArrayList<>();
        for (int i = 0; i < work.size(); i++) {
            MutableEdge edge = work.get(i);
            result.add(ForestEdge.builder().id(edge.id).fromNodeId(edge.from)
                    .toNodeId(edge.to).coordinates(edge.coords)
                    .flowTph(edges.get(i).getFlowTph()).diameterMm(edges.get(i).getDiameterMm())
                    .build());
        }
        return result;
    }

    private boolean repairEdgeTurns(MutableEdge edge, ObstacleMask pass,
                                    ObstacleIndex obstacleIndex,
                                    Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                            ownObstacles,
                                    boolean terminal) {
        List<Coordinate> coords = edge.coords;
        if (coords.size() < 3) {
            return false;
        }
        // E50: свой ОКС не игнорируется на стволе — только хвост выхода
        // (`target→point`) вправе пересекать свой ОКС; последние две вершины
        // здесь защищены (terminal), поэтому изоляция не нужна.
        Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored = Set.of();
        if (removeMicroVertices(edge, pass, obstacleIndex, ignored, terminal)) {
            return true;
        }
        // i идёт до предпоследней (исключая point). Для терминального ребра
        // последняя внутренняя вершина (target) не удаляется, но может получать
        // вставленную перед ней вершину (E43/E44).
        int lastInterior = terminal ? coords.size() - 2 : -1;
        for (int i = 1; i < coords.size() - 1; i++) {
            if (turnAllowed(coords.get(i - 1), coords.get(i), coords.get(i + 1))) {
                continue;
            }
            if (i != lastInterior && canDropVertex(coords, i, obstacleIndex, ignored)) {
                coords.remove(i);
                return true;
            }
            Coordinate via = gridViaInterior(coords, i, pass, obstacleIndex, ignored);
            if (via != null) {
                coords.add(i, via);
                return true;
            }
            // E23-04 (продолжение): перепроложить окно вокруг вершины по сетке.
            List<Coordinate> window = rerouteInteriorWindow(coords, i, pass, obstacleIndex, ignored);
            if (window != null) {
                coords.clear();
                coords.addAll(window);
                return true;
            }
        }
        return false;
    }

    /**
     * Перепроложить окно {@code [i-2, i+2]} через {@code gridPath}: сохраняет
     * направления на входе/выходе, устраняя недопустимый поворот в стеснении
     * (когда удаление вершины и вставка соседа невозможны).
     */
    private List<Coordinate> rerouteInteriorWindow(List<Coordinate> coords, int i, ObstacleMask pass,
                                                   ObstacleIndex obstacleIndex,
                                                   Set<org.locationtech.jts.geom.prep.PreparedGeometry>
                                                           ignored) {
        if (i < 2 || i + 3 > coords.size()) {
            return null;
        }
        Coordinate start = coords.get(i - 2);
        Coordinate goal = coords.get(i + 2);
        Coordinate incoming = i - 3 >= 0
                ? new Coordinate(start.x - coords.get(i - 3).x, start.y - coords.get(i - 3).y)
                : null;
        Coordinate nextAfter = i + 3 < coords.size() ? coords.get(i + 3) : null;
        List<Coordinate> path = gridPath(pass, obstacleIndex, start, incoming, goal, nextAfter,
                ignored);
        if (path == null || path.size() < 2) {
            return null;
        }
        List<Coordinate> rebuilt = new ArrayList<>(coords.subList(0, i - 2));
        rebuilt.addAll(path);
        if (i + 3 < coords.size()) {
            rebuilt.addAll(coords.subList(i + 3, coords.size()));
        }
        return turnsWithinLimit(rebuilt, rebuilt.size() - 2) ? rebuilt : null;
    }

    /**
     * Удаляет внутренние вершины, образующие микро-отрезки (артефакты grid-
     * захода), если объединяющий отрезок не пересекает запрет. Устраняет
     * «шипы» и почти развороты (FR-34).
     */
    private boolean removeMicroVertices(MutableEdge edge, ObstacleMask pass,
                                        ObstacleIndex obstacleIndex,
                                        Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored,
                                        boolean terminal) {
        double microLen = Math.max(0.1, pass.cellM() * 0.5);
        List<Coordinate> coords = edge.coords;
        int limit = terminal ? coords.size() - 2 : coords.size() - 1;
        for (int i = 1; i < limit; i++) {
            double toPrevious = coords.get(i).distance(coords.get(i - 1));
            double toNext = coords.get(i).distance(coords.get(i + 1));
            if (Math.min(toPrevious, toNext) >= microLen) {
                continue;
            }
            // Удаляем микро-вершину, если объединённый отрезок не заблокирован;
            // углы на соседях чинит последующий проход ремонта поворотов.
            if (canMergeVertex(coords, i, obstacleIndex, ignored)) {
                coords.remove(i);
                return true;
            }
        }
        return false;
    }

    private boolean canMergeVertex(List<Coordinate> coords, int i, ObstacleIndex obstacleIndex,
                                   Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored) {
        Coordinate previous = coords.get(i - 1);
        Coordinate next = coords.get(i + 1);
        return previous.equals2D(next) || obstacleIndex == null
                || !obstacleIndex.isInteriorBlocked(line(previous, next), ignored);
    }

    private boolean canDropVertex(List<Coordinate> coords, int i, ObstacleIndex obstacleIndex,
                                  Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored) {
        Coordinate previous = coords.get(i - 1);
        Coordinate next = coords.get(i + 1);
        if (!previous.equals2D(next) && obstacleIndex != null
                && obstacleIndex.isInteriorBlocked(line(previous, next), ignored)) {
            return false;
        }
        if (i - 2 >= 0 && !turnAllowed(coords.get(i - 2), previous, next)) {
            return false;
        }
        return i + 2 >= coords.size() || turnAllowed(previous, next, coords.get(i + 2));
    }

    private Coordinate gridViaInterior(List<Coordinate> coords, int i, ObstacleMask pass,
                                       ObstacleIndex obstacleIndex,
                                       Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored) {
        Coordinate before = coords.get(i - 1);
        Coordinate vertex = coords.get(i);
        Coordinate after = coords.get(i + 1);
        Coordinate beforeBefore = i - 2 >= 0 ? coords.get(i - 2) : null;
        Coordinate afterAfter = i + 2 < coords.size() ? coords.get(i + 2) : null;
        Coordinate best = null;
        double bestExtra = Double.POSITIVE_INFINITY;
        for (Coordinate base : new Coordinate[]{before, vertex}) {
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
                if (q.equals2D(before) || q.equals2D(vertex) || q.equals2D(after)) {
                    continue;
                }
                if (!turnAllowed(beforeBefore, before, q)
                        || !turnAllowed(before, q, vertex)
                        || !turnAllowed(q, vertex, after)
                        || !turnAllowed(vertex, after, afterAfter)) {
                    continue;
                }
                if (obstacleIndex != null && (obstacleIndex.isInteriorBlocked(line(before, q), ignored)
                        || obstacleIndex.isInteriorBlocked(line(q, vertex), ignored))) {
                    continue;
                }
                double extra = q.distance(before) + q.distance(vertex) - before.distance(vertex);
                if (extra < bestExtra) {
                    bestExtra = extra;
                    best = q;
                }
            }
        }
        return best;
    }

    private Coordinate neighborAt(MutableEdge edge, String node) {
        if (edge.from.equals(node) && edge.coords.size() >= 2) {
            return edge.coords.get(1);
        }
        if (edge.to.equals(node) && edge.coords.size() >= 2) {
            return edge.coords.get(edge.coords.size() - 2);
        }
        return null;
    }

    /**
     * ADR-0043: заменить прямой отрезок «узел→сосед» на локальный turn-aware
     * путь по сетке (≤ {@code forest-max-turn-deg} на каждом повороте, обход
     * запретов), чтобы снять недопустимый поворот в узле. Возвращает {@code true},
     * если путь найден и вставлен.
     */
    private boolean rerouteAtNode(List<MutableEdge> edges, int edgeIndex, String node,
                                  Coordinate incoming, Coordinate vertex, Coordinate outgoing,
                                  ObstacleMask pass, ObstacleIndex obstacleIndex,
                                  Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                          ownObstacles) {
        MutableEdge edge = edges.get(edgeIndex);
        Coordinate nextAfter = nextAfter(edge, node);
        Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored = new HashSet<>();
        if (ownObstacles != null) {
            Set<org.locationtech.jts.geom.prep.PreparedGeometry> first = ownObstacles.get(edge.from);
            Set<org.locationtech.jts.geom.prep.PreparedGeometry> second = ownObstacles.get(edge.to);
            if (first != null) {
                ignored.addAll(first);
            }
            if (second != null) {
                ignored.addAll(second);
            }
        }
        List<Coordinate> path = gridPath(pass, obstacleIndex, vertex, incoming, outgoing, nextAfter,
                ignored);
        if (path == null || path.size() < 2) {
            return false;
        }
        List<Coordinate> coords = edge.coords;
        List<Coordinate> rebuilt = new ArrayList<>();
        if (edge.from.equals(node)) {
            rebuilt.addAll(path);
            rebuilt.addAll(coords.subList(2, coords.size()));
        } else if (edge.to.equals(node)) {
            rebuilt.addAll(coords.subList(0, coords.size() - 2));
            List<Coordinate> reversed = new ArrayList<>(path);
            Collections.reverse(reversed);
            reversed.remove(0);
            rebuilt.addAll(reversed);
        } else {
            return false;
        }
        coords.clear();
        coords.addAll(rebuilt);
        return true;
    }

    /**
     * ADR-0043: перетрассировать подход к точке выхода (сегмент
     * {@code prev→target}), чтобы стык {@code target→point} стал ≤90°.
     * Возвращает новую геометрию ребра или {@code null}.
     */
    private List<Coordinate> repairExitApproach(List<Coordinate> coords, Coordinate startPrevious,
                                                ObstacleMask pass, ObstacleIndex obstacleIndex,
                                                Map<String, Set<org.locationtech.jts.geom.prep.PreparedGeometry>>
                                                        ownObstacles,
                                                String terminalId) {
        int n = coords.size();
        Coordinate point = coords.get(n - 1);
        Coordinate target = coords.get(n - 2);
        // E50: перепрокладывается ствол до `target`; свой ОКС не игнорируется
        // (хвост `target→point` добавляется отдельно и каноничен).
        Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored = Set.of();
        // E23-04: если локальный ремонт у target невозможен, перепрокладываем
        // подход от более дальней вершины (до 5 назад), чтобы найти допустимый
        // заход на target (углы ≤90° на стыке target→point).
        int earliest = Math.max(1, n - 10);
        for (int s = n - 3; s >= earliest; s--) {
            Coordinate start = coords.get(s);
            Coordinate beforeStart = s - 1 >= 0 ? coords.get(s - 1) : startPrevious;
            Coordinate incoming = beforeStart == null ? null
                    : new Coordinate(start.x - beforeStart.x, start.y - beforeStart.y);
            List<Coordinate> path = gridPath(pass, obstacleIndex, start, incoming, target, point,
                    ignored);
            if (path != null && path.size() >= 2) {
                List<Coordinate> rebuilt = new ArrayList<>(coords.subList(0, s));
                rebuilt.addAll(path);
                rebuilt.add(point);
                if (turnsWithinLimit(rebuilt, rebuilt.size() - 3)) {
                    return rebuilt;
                }
            }
            // Непрерывный «доводчик»: точка на продолжении вывода за target, при
            // которой стык q→target→point прямой, а подход prev→q допустим.
            Coordinate via = continuousExitVia(coords, s, target, point, obstacleIndex, ignored);
            if (via != null) {
                List<Coordinate> rebuilt = new ArrayList<>(coords.subList(0, s));
                rebuilt.add(via);
                rebuilt.add(target);
                rebuilt.add(point);
                if (turnsWithinLimit(rebuilt, rebuilt.size() - 3)) {
                    return rebuilt;
                }
            }
        }
        return null;
    }

    private Coordinate continuousExitVia(List<Coordinate> coords, int s, Coordinate target,
                                         Coordinate point, ObstacleIndex obstacleIndex,
                                         Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored) {
        double dx = target.x - point.x;
        double dy = target.y - point.y;
        double norm = Math.hypot(dx, dy);
        if (norm < EPS) {
            return null;
        }
        // rebuilt = coords[0..s-1] + via + target + point, предшественник — coords[s-1].
        int prevIndex = s - 1;
        if (prevIndex < 0) {
            return null;
        }
        Coordinate prev = coords.get(prevIndex);
        Coordinate before = prevIndex - 1 >= 0 ? coords.get(prevIndex - 1) : null;
        for (double distance : new double[]{1.0, 2.0, 3.0, 5.0}) {
            Coordinate via = new Coordinate(target.x + dx / norm * distance,
                    target.y + dy / norm * distance);
            if (obstacleIndex != null && (obstacleIndex.isInteriorBlocked(line(prev, via), ignored)
                    || obstacleIndex.isInteriorBlocked(line(via, target), ignored))) {
                continue;
            }
            if (!turnAllowed(before, prev, via) || !turnAllowed(prev, via, target)) {
                continue;
            }
            return via;
        }
        return null;
    }

    /** Допустимо ли завершение в цели: не тривиальный старт и стык ≤90°. */
    private boolean acceptableGoal(int dir, int parentCell, ObstacleMask pass, Coordinate goal,
                                   Coordinate nextAfter) {
        if (dir == 8 || parentCell < 0) {
            return false;
        }
        if (nextAfter == null) {
            return true;
        }
        int col = parentCell % pass.width();
        int row = parentCell / pass.width();
        return turnDegrees(goal.x - pass.centerX(col, row), goal.y - pass.centerY(col, row),
                nextAfter.x - goal.x, nextAfter.y - goal.y) <= maxTurnDeg() + ANGLE_EPS;
    }

    private Coordinate nextAfter(MutableEdge edge, String node) {
        if (edge.from.equals(node)) {
            return edge.coords.size() > 2 ? edge.coords.get(2) : null;
        }
        if (edge.to.equals(node)) {
            return edge.coords.size() > 2 ? edge.coords.get(edge.coords.size() - 3) : null;
        }
        return null;
    }

    /**
     * Turn-aware поиск пути по сетке от {@code start} к {@code goal}: состояние
     * «клетка + входящее направление», поворот ≤ {@code forest-max-turn-deg},
     * запретные клетки/сегменты обходятся.
     */
    private List<Coordinate> gridPath(ObstacleMask pass, ObstacleIndex obstacleIndex,
                                      Coordinate start, Coordinate incoming, Coordinate goal,
                                      Coordinate nextAfter,
                                      Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored) {
        return gridPath(pass, obstacleIndex, start, incoming, goal, nextAfter, ignored, 8000, true);
    }

    private List<Coordinate> gridPath(ObstacleMask pass, ObstacleIndex obstacleIndex,
                                      Coordinate start, Coordinate incoming, Coordinate goal,
                                      Coordinate nextAfter,
                                      Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored,
                                      int maxExpansions) {
        return gridPath(pass, obstacleIndex, start, incoming, goal, nextAfter, ignored, maxExpansions,
                true);
    }

    /**
     * @param trustMask разрешено ли пропускать точную JTS-проверку сегмента по
     *                  маске: {@code false}, если индекс содержит добавленные
     *                  препятствия ({@code withAdditional}), которых нет в маске.
     */
    private List<Coordinate> gridPath(ObstacleMask pass, ObstacleIndex obstacleIndex,
                                      Coordinate start, Coordinate incoming, Coordinate goal,
                                      Coordinate nextAfter,
                                      Set<org.locationtech.jts.geom.prep.PreparedGeometry> ignored,
                                      int maxExpansions, boolean trustMask) {
        int width = pass.width();
        int height = pass.height();
        int startCell = pass.cellAt(start.x, start.y);
        int goalCell = pass.cellAt(goal.x, goal.y);
        GridPathWorkspace ws = GRID_PATH.get();
        ws.begin(maxExpansions);
        long startKey = (long) startCell * 9 + 8;
        ws.put(startKey, 0.0, -1);
        ws.heapPush(startKey, 0.0);
        long goalKey = -1;
        int expansions = 0;
        while (ws.heapSize > 0 && expansions < maxExpansions) {
            ws.heapPop();
            long key = ws.popKey;
            double keyDist = ws.popDist;
            if (keyDist > ws.getDist(key) + ANGLE_EPS) {
                continue;
            }
            expansions++;
            int cell = (int) (key / 9);
            int dir = (int) (key % 9);
            if (cell == goalCell) {
                int parentKey = ws.parent(key);
                if (acceptableGoal(dir, parentKey < 0 ? -1 : parentKey / 9, pass, goal, nextAfter)) {
                    goalKey = key;
                    break;
                }
            }
            int col = cell % width;
            int row = cell / width;
            double c0x = pass.centerX(col, row);
            double c0y = pass.centerY(col, row);
            int parentKey = ws.parent(key);
            int prevCell = dir == 8 || parentKey < 0 ? -1 : parentKey / 9;
            int[][] neighbors = pass.neighbors(col, row);
            for (int idx = 0; idx < neighbors.length; idx++) {
                int[] step = neighbors[idx];
                int nc = col + step[0];
                int nr = row + step[1];
                if (nc < 0 || nr < 0 || nc >= width || nr >= height) {
                    continue;
                }
                double c1x = pass.centerX(nc, nr);
                double c1y = pass.centerY(nc, nr);
                if (dir == 8) {
                    if (incoming != null
                            && turnDegrees(incoming.x, incoming.y, c1x - c0x, c1y - c0y)
                                    > maxTurnDeg() + ANGLE_EPS) {
                        continue;
                    }
                } else {
                    int pc = prevCell % width;
                    int pr = prevCell / width;
                    if (prevCell >= 0 && turnDegrees(c0x - pass.centerX(pc, pr),
                            c0y - pass.centerY(pc, pr), c1x - c0x, c1y - c0y)
                            > maxTurnDeg() + ANGLE_EPS) {
                        continue;
                    }
                }
                if (obstacleIndex != null && (!trustMask || pass.anyBlockedAlong(c0x, c0y, c1x, c1y))
                        && obstacleIndex.isInteriorBlocked(
                                line(new Coordinate(c0x, c0y), new Coordinate(c1x, c1y)), ignored)) {
                    continue;
                }
                int nextCell = nr * width + nc;
                long nextKey = (long) nextCell * 9 + idx;
                double dx = c1x - c0x;
                double dy = c1y - c0y;
                double nd = keyDist + Math.sqrt(dx * dx + dy * dy);
                if (nd < ws.getDist(nextKey) - ANGLE_EPS) {
                    ws.put(nextKey, nd, (int) key);
                    ws.heapPush(nextKey, nd);
                }
            }
        }
        if (goalKey < 0) {
            return null;
        }
        List<Integer> cells = new ArrayList<>();
        long current = goalKey;
        while (current != startKey) {
            cells.add((int) (current / 9));
            int parent = ws.parent(current);
            if (parent < 0) {
                return null;
            }
            current = parent;
        }
        cells.add(startCell);
        Collections.reverse(cells);
        List<Coordinate> path = new ArrayList<>();
        for (int cell : cells) {
            path.add(center(pass, cell));
        }
        path.set(0, start);
        if (path.size() >= 2 && incoming != null
                && turnDegrees(incoming.x, incoming.y,
                        path.get(1).x - start.x, path.get(1).y - start.y)
                        > maxTurnDeg() + ANGLE_EPS) {
            return null;
        }
        Coordinate last = path.get(path.size() - 1);
        if (!last.equals2D(goal)) {
            if (path.size() >= 2 && !turnAllowed(path.get(path.size() - 2), last, goal)) {
                return null;
            }
            if (obstacleIndex != null && obstacleIndex.isInteriorBlocked(line(last, goal), ignored)) {
                return null;
            }
            path.add(goal);
        }
        if (nextAfter != null) {
            Coordinate beforeGoal = path.get(path.size() - 2);
            if (!turnAllowed(beforeGoal, goal, nextAfter)) {
                return null;
            }
        }
        return path;
    }

    /**
     * Переиспользуемое рабочее пространство turn-aware поиска: примитивные
     * хеш-таблица состояний {@code cell*9+dir} и бинарная heap. Без боксинга и
     * объектов в горячем пути; очищаются только занятые слоты.
     */
    private static final class GridPathWorkspace {
        private long[] keys = new long[1024];
        private double[] dist = new double[1024];
        private int[] parent = new int[1024];
        private int[] touched = new int[1024];
        private int touchedCount;
        private long[] heapKeys = new long[256];
        private double[] heapDists = new double[256];
        private int heapSize;
        private long popKey;
        private double popDist;

        private void begin(int maxExpansions) {
            int expected = Math.max(64, maxExpansions * 6 + 64);
            int need = 1;
            while (need < expected) {
                need <<= 1;
            }
            need <<= 1;
            if (need > keys.length) {
                keys = new long[need];
                dist = new double[need];
                parent = new int[need];
                touched = new int[need];
                Arrays.fill(keys, -1L);
                touchedCount = 0;
            } else {
                for (int i = 0; i < touchedCount; i++) {
                    keys[touched[i]] = -1L;
                }
                touchedCount = 0;
            }
            heapSize = 0;
        }

        private int slot(long key) {
            int i = mix(key) & (keys.length - 1);
            while (keys[i] != -1L && keys[i] != key) {
                i = (i + 1) & (keys.length - 1);
            }
            return i;
        }

        private double getDist(long key) {
            int i = slot(key);
            return keys[i] == -1L ? Double.POSITIVE_INFINITY : dist[i];
        }

        private int parent(long key) {
            int i = slot(key);
            return keys[i] == -1L ? -1 : parent[i];
        }

        private void put(long key, double value, int par) {
            int i = slot(key);
            if (keys[i] == -1L) {
                keys[i] = key;
                touched[touchedCount++] = i;
            }
            dist[i] = value;
            parent[i] = par;
        }

        private void heapPush(long key, double value) {
            if (heapSize == heapKeys.length) {
                heapKeys = Arrays.copyOf(heapKeys, heapKeys.length * 2);
                heapDists = Arrays.copyOf(heapDists, heapDists.length * 2);
            }
            int i = heapSize++;
            heapKeys[i] = key;
            heapDists[i] = value;
            while (i > 0) {
                int p = (i - 1) >> 1;
                if (heapDists[p] <= heapDists[i]) {
                    break;
                }
                swapHeap(p, i);
                i = p;
            }
        }

        private void heapPop() {
            popKey = heapKeys[0];
            popDist = heapDists[0];
            int n = --heapSize;
            if (n > 0) {
                long xKey = heapKeys[n];
                double xDist = heapDists[n];
                int i = 0;
                while (true) {
                    int child = (i << 1) + 1;
                    if (child >= n) {
                        break;
                    }
                    long cKey = heapKeys[child];
                    double cDist = heapDists[child];
                    int right = child + 1;
                    if (right < n && cDist > heapDists[right]) {
                        child = right;
                        cKey = heapKeys[right];
                        cDist = heapDists[right];
                    }
                    if (xDist <= cDist) {
                        break;
                    }
                    heapKeys[i] = cKey;
                    heapDists[i] = cDist;
                    i = child;
                }
                heapKeys[i] = xKey;
                heapDists[i] = xDist;
            }
        }

        private void swapHeap(int first, int second) {
            long key = heapKeys[first];
            heapKeys[first] = heapKeys[second];
            heapKeys[second] = key;
            double value = heapDists[first];
            heapDists[first] = heapDists[second];
            heapDists[second] = value;
        }

        private static int mix(long z) {
            z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
            z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
            return (int) (z ^ (z >>> 33));
        }
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
                                    SpecialZoneIndex specialZones, Coordinate startPrevious,
                                    Coordinate endNext, boolean terminal) {
        List<Coordinate> unique;
        if (endNext != null && coordinates.size() >= 3) {
            // E43: канонический выход (target, n-2) и точка (n-1) — обязательные
            // вершины; упрощаем только ствол [0..n-3], чтобы не потерять выход.
            Coordinate target = coordinates.get(coordinates.size() - 2);
            Coordinate end = coordinates.get(coordinates.size() - 1);
            unique = simplifier.simplify(
                    new ArrayList<>(coordinates.subList(0, coordinates.size() - 2)));
            unique.add(target);
            unique.add(end);
        } else if (endNext != null) {
            unique = new ArrayList<>(coordinates);
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
                // E41: не срезать углом через спецзону (угол ≥45°).
                if (specialZones != null && !specialZones.angleOk(line(vertex, target))) {
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

    /** Изменяемое ребро для ремонта углов в узлах (ADR-0043). */
    private static final class MutableEdge {
        private final String id;
        private final String from;
        private final String to;
        private final List<Coordinate> coords;

        private MutableEdge(String id, String from, String to, List<Coordinate> coords) {
            this.id = id;
            this.from = from;
            this.to = to;
            this.coords = coords;
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

    private double polylineLength(List<Coordinate> coordinates) {
        double total = 0.0;
        for (int i = 0; i + 1 < coordinates.size(); i++) {
            total += coordinates.get(i).distance(coordinates.get(i + 1));
        }
        return total;
    }

    /** Все сегменты ломаной допустимы по минимальному углу спецпрохода (E41). */
    private boolean polylineSpecialOk(List<Coordinate> coordinates, SpecialZoneIndex specialZones) {
        for (int i = 0; i + 1 < coordinates.size(); i++) {
            if (!specialZones.angleOk(line(coordinates.get(i), coordinates.get(i + 1)))) {
                return false;
            }
        }
        return true;
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
        private final List<ForestTree> relinkedTrees;
        private final List<ForestTree> contractedTrees;
        private final List<ForestTree> refinedTrees;
        private final List<ForestTree> optimizedTrees;
        private final int passNumber;

        private GridBuild(List<ForestTree> trees, double score, Set<String> connected,
                          List<StageFeature> rawFeatures, List<ForestTree> relinkedTrees,
                          List<ForestTree> contractedTrees, List<ForestTree> refinedTrees,
                          List<ForestTree> optimizedTrees, int passNumber) {
            this.trees = trees;
            this.score = score;
            this.connected = connected;
            this.rawFeatures = rawFeatures;
            this.relinkedTrees = relinkedTrees;
            this.contractedTrees = contractedTrees;
            this.refinedTrees = refinedTrees;
            this.optimizedTrees = optimizedTrees;
            this.passNumber = passNumber;
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
