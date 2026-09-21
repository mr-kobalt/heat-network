package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.simplify.TopologyPreservingSimplifier;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.ObstacleIndex;

/**
 * Поиск пути в обход запретных зон (ТП 2.1, ADR-0006/0025): трасса — прямые
 * участки, поворот относительно продолжения предыдущего — не более 90°, без
 * необоснованных изломов, зигзагов и ступеней.
 *
 * <p>Граф видимости строится по прореженным вершинам препятствий (не по углам
 * bbox), узлы чуть раздвинуты от запретной зоны, проверка видимости ведётся по
 * локальному списку препятствий области поиска (без STRtree на каждый сегмент).
 * После поиска путь сглаживается (string pulling).</p>
 */
@Component
public class VisibilityGraphRouter {

    private static final double MAX_TURN_DEG = 90.0;
    private static final double TURN_THRESHOLD_DEG = 15.0;
    private static final double SHAPE_SIMPLIFY_TOLERANCE_M = 2.0;
    private static final double NODE_INFLATE_M = 0.2;
    private static final double ANGLE_EPS = 1e-6;

    /** Уровни адаптивного поиска: расширение области и лимит узлов. */
    private static final double[][] SEARCH_LEVELS = {
            {25.0, 120.0},
            {150.0, 200.0},
            {500.0, 300.0}
    };

    private final double turnPenaltyM;
    private final Map<Geometry, Coordinate[]> shapeVertices = new WeakHashMap<>();

    public VisibilityGraphRouter(AppProperties appProperties) {
        this.turnPenaltyM = appProperties.getTurnPenaltyM();
    }

    public List<Coordinate> findPath(Coordinate start, Coordinate end, ObstacleIndex index) {
        return findPath(start, end, index, null, null);
    }

    /**
     * @param startPrevious точка перед началом (для контроля поворота на стыке
     *                      финального вывода и маршрута), может быть null
     * @param endNext       точка после конца (для контроля поворота у конечного
     *                      вывода), может быть null
     */
    public List<Coordinate> findPath(Coordinate start, Coordinate end, ObstacleIndex index,
                                     Coordinate startPrevious, Coordinate endNext) {
        LineString direct = line(start, end);
        if (!index.isBlocked(direct)
                && endpointsValid(List.of(start, end), startPrevious, endNext)) {
            return List.of(start, end);
        }
        for (double[] level : SEARCH_LEVELS) {
            List<Coordinate> path = findPath(start, end, index, level[0], (int) level[1],
                    startPrevious, endNext);
            if (path == null) {
                continue;
            }
            List<Coordinate> smoothed = smooth(path, index);
            if (!exceedsTurnLimit(smoothed)
                    && endpointsValid(smoothed, startPrevious, endNext)) {
                return smoothed;
            }
            if (!exceedsTurnLimit(path) && endpointsValid(path, startPrevious, endNext)) {
                return path;
            }
        }
        return null;
    }

    private List<Coordinate> findPath(Coordinate start, Coordinate end, ObstacleIndex index,
                                      double expandM, int maxNodes,
                                      Coordinate startPrevious, Coordinate endNext) {
        LineString direct = line(start, end);
        Envelope searchArea = direct.getEnvelopeInternal();
        searchArea.expandBy(expandM);
        List<Geometry> obstacles = index.obstaclesIn(searchArea);
        obstacles.removeIf(obstacle -> obstacle.distance(direct) > expandM);
        List<PreparedGeometry> local = new ArrayList<>(obstacles.size());
        for (Geometry obstacle : obstacles) {
            local.add(PreparedGeometryFactory.prepare(obstacle));
        }

        List<Coordinate> nodes = graphNodes(start, end, direct, obstacles, expandM, maxNodes);
        int n = nodes.size();
        double maxEdgeM = Math.max(expandM * 2.0, 150.0);

        double[] distance = new double[n];
        int[] previous = new int[n];
        boolean[] visited = new boolean[n];
        for (int i = 0; i < n; i++) {
            distance[i] = Double.POSITIVE_INFINITY;
            previous[i] = -1;
        }
        distance[0] = 0.0;

        for (int iteration = 0; iteration < n; iteration++) {
            int current = -1;
            double best = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (!visited[i] && distance[i] < best) {
                    best = distance[i];
                    current = i;
                }
            }
            if (current == -1) {
                break;
            }
            if (current == 1) {
                return reconstruct(nodes, previous);
            }
            visited[current] = true;
            for (int next = 0; next < n; next++) {
                if (visited[next] || next == current) {
                    continue;
                }
                Coordinate a = nodes.get(current);
                Coordinate b = nodes.get(next);
                double edge = a.distance(b);
                if (edge > maxEdgeM || blocked(line(a, b), local)) {
                    continue;
                }
                double turn;
                if (previous[current] == -1) {
                    turn = startPrevious == null ? 0.0
                            : turnDegrees(startPrevious, a, b);
                } else {
                    turn = turnDegrees(nodes.get(previous[current]), a, b);
                }
                if (turn > MAX_TURN_DEG + ANGLE_EPS) {
                    continue;
                }
                if (next == 1 && endNext != null
                        && turnDegrees(a, b, endNext) > MAX_TURN_DEG + ANGLE_EPS) {
                    continue;
                }
                double candidate = distance[current] + edge + turnPenalty(turn);
                if (candidate < distance[next]) {
                    distance[next] = candidate;
                    previous[next] = current;
                }
            }
        }
        return null;
    }

    /**
     * Узлы графа: старт/финиш и прореженные вершины ближайших к коридору
     * препятствий (приоритет — ближайшим, чтобы не терять проходы).
     */
    private List<Coordinate> graphNodes(Coordinate start, Coordinate end, LineString direct,
                                        List<Geometry> obstacles, double expandM, int maxNodes) {
        List<Coordinate> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(end);
        obstacles.sort(Comparator.comparingDouble(obstacle -> obstacle.distance(direct)));
        Set<Long> seen = new HashSet<>();
        seen.add(nodeKey(start));
        seen.add(nodeKey(end));
        for (Geometry obstacle : obstacles) {
            for (Coordinate vertex : vertices(obstacle)) {
                if (!seen.add(nodeKey(vertex))) {
                    continue;
                }
                nodes.add(vertex);
                if (nodes.size() >= maxNodes) {
                    return nodes;
                }
            }
        }
        return nodes;
    }

    /** Прореженные вершины препятствия, раздвинутого на малый зазор. */
    private Coordinate[] vertices(Geometry obstacle) {
        Coordinate[] cached = shapeVertices.get(obstacle);
        if (cached != null) {
            return cached;
        }
        Geometry inflated = obstacle.buffer(NODE_INFLATE_M);
        Geometry simplified = TopologyPreservingSimplifier.simplify(
                inflated, SHAPE_SIMPLIFY_TOLERANCE_M);
        Coordinate[] coordinates = simplified.getCoordinates();
        Coordinate[] result = coordinates.length >= 3 ? coordinates : inflated.getCoordinates();
        shapeVertices.put(obstacle, result);
        return result;
    }

    private long nodeKey(Coordinate coordinate) {
        long x = Math.round(coordinate.x * 100.0);
        long y = Math.round(coordinate.y * 100.0);
        return x * 1_000_000_007L + y;
    }

    /**
     * Сглаживание пути (string pulling): каждый следующий сегмент тянется до
     * самой дальней видимой вершины. Убирает ступени, зигзаги и развороты.
     */
    private List<Coordinate> smooth(List<Coordinate> path, ObstacleIndex index) {
        if (path.size() <= 2) {
            return path;
        }
        List<Coordinate> result = new ArrayList<>();
        result.add(path.get(0));
        int current = 0;
        while (current < path.size() - 1) {
            int next = current + 1;
            for (int candidate = path.size() - 1; candidate > current + 1; candidate--) {
                if (!index.isBlocked(line(path.get(current), path.get(candidate)))) {
                    next = candidate;
                    break;
                }
            }
            result.add(path.get(next));
            current = next;
        }
        return result;
    }

    private boolean blocked(LineString segment, List<PreparedGeometry> obstacles) {
        for (PreparedGeometry obstacle : obstacles) {
            if (!obstacle.intersects(segment)) {
                continue;
            }
            // Касание границы запретной зоны допустимо; запрет — пересечение
            // внутренней части (как и для минимального расстояния).
            if (obstacle.getGeometry().relate(segment, "T********")) {
                return true;
            }
        }
        return false;
    }

    private boolean endpointsValid(List<Coordinate> path, Coordinate startPrevious,
                                   Coordinate endNext) {
        if (path.size() < 2) {
            return true;
        }
        if (startPrevious != null
                && turnDegrees(startPrevious, path.get(0), path.get(1))
                        > MAX_TURN_DEG + ANGLE_EPS) {
            return false;
        }
        return endNext == null
                || turnDegrees(path.get(path.size() - 2), path.get(path.size() - 1), endNext)
                        <= MAX_TURN_DEG + ANGLE_EPS;
    }

    private boolean exceedsTurnLimit(List<Coordinate> path) {
        for (int i = 1; i < path.size() - 1; i++) {
            if (turnDegrees(path.get(i - 1), path.get(i), path.get(i + 1))
                    > MAX_TURN_DEG + ANGLE_EPS) {
                return true;
            }
        }
        return false;
    }

    private double turnPenalty(double turn) {
        if (turnPenaltyM <= 0.0 || turn <= TURN_THRESHOLD_DEG) {
            return 0.0;
        }
        return turnPenaltyM * (turn / MAX_TURN_DEG);
    }

    private double turnDegrees(Coordinate before, Coordinate vertex, Coordinate after) {
        double inX = vertex.x - before.x;
        double inY = vertex.y - before.y;
        double outX = after.x - vertex.x;
        double outY = after.y - vertex.y;
        double dot = inX * outX + inY * outY;
        double cross = inX * outY - inY * outX;
        return Math.toDegrees(Math.atan2(Math.abs(cross), dot));
    }

    private List<Coordinate> reconstruct(List<Coordinate> nodes, int[] previous) {
        List<Coordinate> path = new ArrayList<>();
        int current = 1;
        while (current != -1) {
            path.add(0, nodes.get(current));
            current = previous[current];
        }
        return path;
    }

    private LineString line(Coordinate a, Coordinate b) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(new Coordinate[]{a, b});
    }
}
