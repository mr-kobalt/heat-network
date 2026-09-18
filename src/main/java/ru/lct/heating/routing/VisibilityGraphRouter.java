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
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.ObstacleIndex;

/**
 * Поиск пути в обход запретных зон: прямая, при необходимости — граф видимости
 * вокруг прямоугольных оболочек препятствий и алгоритм Дейкстры (ADR-0006).
 */
@Component
public class VisibilityGraphRouter {

    private static final double ENVELOPE_EXPAND_M = 30.0;
    private static final double CORNER_OFFSET_M = 2.0;
    private static final int MAX_NODES = 200;

    public List<Coordinate> findPath(Coordinate start, Coordinate end, ObstacleIndex index) {
        List<Coordinate> nodes = new ArrayList<>();
        nodes.add(start);
        nodes.add(end);

        LineString direct = line(start, end);
        if (!index.isBlocked(direct, ignore(index, nodes, 0, 1))) {
            return List.of(start, end);
        }

        Envelope searchArea = direct.getEnvelopeInternal();
        searchArea.expandBy(ENVELOPE_EXPAND_M);
        for (Geometry obstacle : index.obstaclesIn(searchArea)) {
            Envelope envelope = obstacle.getEnvelopeInternal();
            envelope.expandBy(CORNER_OFFSET_M);
            nodes.add(new Coordinate(envelope.getMinX(), envelope.getMinY()));
            nodes.add(new Coordinate(envelope.getMinX(), envelope.getMaxY()));
            nodes.add(new Coordinate(envelope.getMaxX(), envelope.getMinY()));
            nodes.add(new Coordinate(envelope.getMaxX(), envelope.getMaxY()));
            if (nodes.size() >= MAX_NODES) {
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
                    double candidate = distance[current] + a.distance(b);
                    if (candidate < distance[next]) {
                        distance[next] = candidate;
                        previous[next] = current;
                    }
                }
            }
        }

        if (Double.isInfinite(distance[1])) {
            return null;
        }
        return reconstruct(nodes, previous);
    }

    private Set<PreparedGeometry> ignore(ObstacleIndex index, List<Coordinate> nodes, int first, int second) {
        Set<PreparedGeometry> result = Collections.newSetFromMap(new IdentityHashMap<>());
        result.addAll(index.obstaclesContaining(point(nodes.get(first))));
        result.addAll(index.obstaclesContaining(point(nodes.get(second))));
        return result;
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
