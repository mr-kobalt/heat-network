package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndex;

/**
 * Разрешение пересечений новых участков между собой вне общего узла (FR-29):
 * пересекающийся участок перестраивается в обход остальных уже проложенных
 * участков. Если перестроить не удалось — диагностика.
 */
@Component
public class RouteCrossingResolver {

    private static final double EDGE_BUFFER_M = 1.0;
    private static final double NODE_CLEARANCE_M = 3.0;
    private static final int MAX_PASSES = 12;

    private final VisibilityGraphRouter router;
    private final LineStringSimplifier simplifier;

    public RouteCrossingResolver(VisibilityGraphRouter router, LineStringSimplifier simplifier) {
        this.router = router;
        this.simplifier = simplifier;
    }

    public List<ForestEdge> resolve(List<ForestEdge> edges, ObstacleIndex base,
                                    Map<String, OksApproachResolver.Approach> approaches,
                                    List<String> warnings) {
        List<ForestEdge> current = new ArrayList<>(edges);
        Set<String> reported = new HashSet<>();
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (int i = 0; i < current.size(); i++) {
                for (int j = i + 1; j < current.size(); j++) {
                    ForestEdge first = current.get(i);
                    ForestEdge second = current.get(j);
                    if (sharesNode(first, second) || !crosses(first, second)) {
                        continue;
                    }
                    ForestEdge rebuiltFirst = rebuild(first, second, current, base, approaches);
                    if (rebuiltFirst != null) {
                        current.set(i, rebuiltFirst);
                        changed = true;
                        first = rebuiltFirst;
                    }
                    if (sharesNode(first, second) || !crosses(first, second)) {
                        continue;
                    }
                    ForestEdge rebuiltSecond = rebuild(second, first, current, base, approaches);
                    if (rebuiltSecond != null) {
                        current.set(j, rebuiltSecond);
                        changed = true;
                    } else if (reported.add(first.getId() + "|" + second.getId())) {
                        warnings.add("CROSSING_UNRESOLVED: участки " + first.getId()
                                + " и " + second.getId() + " пересекаются");
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        for (int i = 0; i < current.size(); i++) {
            for (int j = i + 1; j < current.size(); j++) {
                if (sharesNode(current.get(i), current.get(j)) || !crosses(current.get(i), current.get(j))) {
                    continue;
                }
                if (reported.add(current.get(i).getId() + "|" + current.get(j).getId())) {
                    warnings.add("CROSSING_UNRESOLVED: участки " + current.get(i).getId()
                            + " и " + current.get(j).getId() + " пересекаются");
                }
            }
        }
        return current;
    }

    private ForestEdge rebuild(ForestEdge target, ForestEdge avoid, List<ForestEdge> all,
                               ObstacleIndex base,
                               Map<String, OksApproachResolver.Approach> approaches) {
        Coordinate start = target.getCoordinates().get(0);
        Coordinate end = target.getCoordinates().get(target.getCoordinates().size() - 1);
        Coordinate routeStart = exitOf(approaches, target.getFromNodeId(), start);
        Coordinate routeEnd = exitOf(approaches, target.getToNodeId(), end);
        Coordinate startPrevious = pointOf(approaches, target.getFromNodeId());
        Coordinate endNext = pointOf(approaches, target.getToNodeId());
        Geometry startDisk = GeometrySupport.GEOMETRY_FACTORY.createPoint(routeStart)
                .buffer(NODE_CLEARANCE_M);
        Geometry endDisk = GeometrySupport.GEOMETRY_FACTORY.createPoint(routeEnd)
                .buffer(NODE_CLEARANCE_M);

        // Обходим сразу все прочие участки (иначе возможна осцилляция между
        // парами); при неудаче — только пересекаемый участок.
        List<Geometry> extras = new ArrayList<>();
        for (ForestEdge edge : all) {
            addExtra(extras, edge, target, startDisk, endDisk);
        }
        List<Coordinate> path = router.findPath(routeStart, routeEnd,
                base.withAdditional(extras), startPrevious, endNext);
        if (path == null) {
            extras = new ArrayList<>();
            addExtra(extras, avoid, target, startDisk, endDisk);
            path = router.findPath(routeStart, routeEnd,
                    base.withAdditional(extras), startPrevious, endNext);
        }
        if (path == null) {
            return null;
        }
        List<Coordinate> coordinates = new ArrayList<>();
        if (!routeStart.equals2D(start)) {
            coordinates.add(start);
            coordinates.add(routeStart);
        }
        coordinates.addAll(path);
        if (!routeEnd.equals2D(end)) {
            coordinates.add(end);
        }
        return ForestEdge.builder()
                .id(target.getId())
                .fromNodeId(target.getFromNodeId())
                .toNodeId(target.getToNodeId())
                .coordinates(simplifier.simplify(coordinates))
                .flowTph(target.getFlowTph())
                .diameterMm(target.getDiameterMm())
                .build();
    }

    private void addExtra(List<Geometry> extras, ForestEdge edge, ForestEdge target,
                          Geometry startDisk, Geometry endDisk) {
        if (edge.getId().equals(target.getId())) {
            return;
        }
        Geometry buffer = line(edge).buffer(EDGE_BUFFER_M);
        if (buffer.intersects(startDisk)) {
            buffer = buffer.difference(startDisk);
        }
        if (buffer.intersects(endDisk)) {
            buffer = buffer.difference(endDisk);
        }
        if (!buffer.isEmpty()) {
            extras.add(buffer);
        }
    }

    private Coordinate exitOf(Map<String, OksApproachResolver.Approach> approaches,
                              String nodeId, Coordinate fallback) {
        if (approaches == null) {
            return fallback;
        }
        OksApproachResolver.Approach approach = approaches.get(nodeId);
        if (approach == null || approach.isBlocked() || approach.getTail().size() != 2) {
            return fallback;
        }
        return approach.getTarget();
    }

    /** Точка подключения перед выводом (для контроля поворота на стыке). */
    private Coordinate pointOf(Map<String, OksApproachResolver.Approach> approaches, String nodeId) {
        if (approaches == null) {
            return null;
        }
        OksApproachResolver.Approach approach = approaches.get(nodeId);
        return approach == null || approach.getTail().size() != 2
                ? null : approach.getTail().get(1);
    }

    private boolean crosses(ForestEdge first, ForestEdge second) {
        return !line(first).intersection(line(second)).isEmpty();
    }

    private boolean sharesNode(ForestEdge first, ForestEdge second) {
        return first.getFromNodeId().equals(second.getFromNodeId())
                || first.getFromNodeId().equals(second.getToNodeId())
                || first.getToNodeId().equals(second.getFromNodeId())
                || first.getToNodeId().equals(second.getToNodeId());
    }

    private LineString line(ForestEdge edge) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                edge.getCoordinates().toArray(new Coordinate[0]));
    }
}
