package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.ObstacleIndex;

/**
 * Поиск пути в обход запретных зон: прямая, при необходимости — граф видимости
 * вокруг препятствий и алгоритм Дейкстры (ADR-0006). Штраф за поворот (FR-28)
 * минимизирует число изломов.
 *
 * <p>При неудаче быстрого прохода поиск повторяется с расширением области и
 * добавлением вершин полигонов (адаптивный обход узких проходов).</p>
 */
@Component
public class VisibilityGraphRouter {

    private static final double CORNER_OFFSET_M = 2.0;
    private static final double TURN_THRESHOLD_DEG = 20.0;
    private static final int VERTEX_BUDGET = 24;

    /** Уровни адаптивного поиска: расширение области, лимит узлов, вершины. */
    private static final double[][] SEARCH_LEVELS = {
            {30.0, 200.0, 0.0},
            {150.0, 500.0, 0.0},
            {500.0, 1500.0, 1.0}
    };

    private final double turnPenaltyM;

    public VisibilityGraphRouter(AppProperties appProperties) {
        this.turnPenaltyM = appProperties.getTurnPenaltyM();
    }

    public List<Coordinate> findPath(Coordinate start, Coordinate end, ObstacleIndex index) {
        LineString direct = line(start, end);
        Set<PreparedGeometry> directIgnore = Collections.newSetFromMap(new IdentityHashMap<>());
        directIgnore.addAll(index.obstaclesContaining(point(start)));
        directIgnore.addAll(index.obstaclesContaining(point(end)));
        if (!index.isBlocked(direct, directIgnore)) {
            return List.of(start, end);
        }
        for (double[] level : SEARCH_LEVELS) {
            List<Coordinate> path = findPath(start, end, index,
                    level[0], (int) level[1], level[2] > 0.5);
            if (path != null) {
                return path;
            }
        }
        return null;
    }

    private List<Coordinate> findPath(Coordinate start, Coordinate end, ObstacleIndex index,
                                      double expandM, int maxNodes, boolean addVertices) {
        List<Coordinate> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(end);

        LineString direct = line(start, end);
        Envelope searchArea = direct.getEnvelopeInternal();
        searchArea.expandBy(expandM);
        for (Geometry obstacle : index.obstaclesIn(searchArea)) {
            Envelope envelope = obstacle.getEnvelopeInternal();
            envelope.expandBy(CORNER_OFFSET_M);
            nodes.add(new Coordinate(envelope.getMinX(), envelope.getMinY()));
            nodes.add(new Coordinate(envelope.getMinX(), envelope.getMaxY()));
            nodes.add(new Coordinate(envelope.getMaxX(), envelope.getMinY()));
            nodes.add(new Coordinate(envelope.getMaxX(), envelope.getMaxY()));
            if (addVertices) {
                addVertices(nodes, obstacle, maxNodes);
            }
            if (nodes.size() >= maxNodes) {
                break;
            }
        }

        int n = nodes.size();
        List<Set<PreparedGeometry>> ignored = new ArrayList<>(n);
        for (Coordinate node : nodes) {
            ignored.add(index.obstaclesContaining(point(node)));
        }

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
                if (!index.isBlocked(line(a, b), ignore(ignored.get(current), ignored.get(next)))) {
                    double candidate = distance[current] + a.distance(b)
                            + turnPenalty(previous, nodes, current, next);
                    if (candidate < distance[next]) {
                        distance[next] = candidate;
                        previous[next] = current;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Добавляет вершины полигона-препятствия (прореженные), чтобы обходить его
     * по фактическому контуру в узких проходах.
     */
    private void addVertices(List<Coordinate> nodes, Geometry obstacle, int maxNodes) {
        Coordinate[] coordinates = obstacle.getCoordinates();
        if (coordinates.length == 0) {
            return;
        }
        int step = Math.max(1, coordinates.length / VERTEX_BUDGET);
        for (int i = 0; i < coordinates.length; i += step) {
            nodes.add(coordinates[i]);
            if (nodes.size() >= maxNodes) {
                return;
            }
        }
    }

    private double turnPenalty(int[] previous, List<Coordinate> nodes, int current, int next) {
        if (turnPenaltyM <= 0.0 || previous[current] == -1) {
            return 0.0;
        }
        Coordinate before = nodes.get(previous[current]);
        Coordinate vertex = nodes.get(current);
        Coordinate after = nodes.get(next);
        double inX = vertex.x - before.x;
        double inY = vertex.y - before.y;
        double outX = after.x - vertex.x;
        double outY = after.y - vertex.y;
        double dot = inX * outX + inY * outY;
        double cross = inX * outY - inY * outX;
        double angle = Math.toDegrees(Math.atan2(Math.abs(cross), dot));
        return angle > TURN_THRESHOLD_DEG ? turnPenaltyM : 0.0;
    }

    private Set<PreparedGeometry> ignore(Set<PreparedGeometry> first, Set<PreparedGeometry> second) {
        if (first.isEmpty() && second.isEmpty()) {
            return Collections.emptySet();
        }
        Set<PreparedGeometry> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(first);
        result.addAll(second);
        return result;
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

    private Point point(Coordinate coordinate) {
        return GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate);
    }

    private LineString line(Coordinate a, Coordinate b) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(new Coordinate[]{a, b});
    }
}
