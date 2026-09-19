package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.List;
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
    private static final int MAX_PASSES = 4;

    private final VisibilityGraphRouter router;
    private final LineStringSimplifier simplifier;

    public RouteCrossingResolver(VisibilityGraphRouter router, LineStringSimplifier simplifier) {
        this.router = router;
        this.simplifier = simplifier;
    }

    public List<ForestEdge> resolve(List<ForestEdge> edges, ObstacleIndex base,
                                    List<String> warnings) {
        List<ForestEdge> current = new ArrayList<>(edges);
        for (int pass = 0; pass < MAX_PASSES; pass++) {
            boolean changed = false;
            for (int i = 0; i < current.size() && !changed; i++) {
                for (int j = i + 1; j < current.size() && !changed; j++) {
                    ForestEdge first = current.get(i);
                    ForestEdge second = current.get(j);
                    if (sharesNode(first, second) || !crosses(first, second)) {
                        continue;
                    }
                    changed = true;
                    ForestEdge rebuiltFirst = rebuild(first, current, base);
                    if (rebuiltFirst != null) {
                        current.set(i, rebuiltFirst);
                    } else {
                        ForestEdge rebuiltSecond = rebuild(second, current, base);
                        if (rebuiltSecond != null) {
                            current.set(j, rebuiltSecond);
                        } else {
                            warnings.add("CROSSING_UNRESOLVED: участки " + first.getId()
                                    + " и " + second.getId() + " пересекаются");
                        }
                    }
                }
            }
            if (!changed) {
                break;
            }
        }
        return current;
    }

    private ForestEdge rebuild(ForestEdge target, List<ForestEdge> all, ObstacleIndex base) {
        Coordinate start = target.getCoordinates().get(0);
        Coordinate end = target.getCoordinates().get(target.getCoordinates().size() - 1);
        List<Geometry> extras = new ArrayList<>();
        Geometry startDisk = GeometrySupport.GEOMETRY_FACTORY.createPoint(start).buffer(NODE_CLEARANCE_M);
        Geometry endDisk = GeometrySupport.GEOMETRY_FACTORY.createPoint(end).buffer(NODE_CLEARANCE_M);
        for (ForestEdge edge : all) {
            if (edge.getId().equals(target.getId())) {
                continue;
            }
            Geometry buffer = line(edge).buffer(EDGE_BUFFER_M)
                    .difference(startDisk).difference(endDisk);
            if (!buffer.isEmpty()) {
                extras.add(buffer);
            }
        }
        ObstacleIndex combined = base.withAdditional(extras);
        List<Coordinate> path = router.findPath(start, end, combined);
        if (path == null) {
            return null;
        }
        return ForestEdge.builder()
                .id(target.getId())
                .fromNodeId(target.getFromNodeId())
                .toNodeId(target.getToNodeId())
                .coordinates(simplifier.simplify(path))
                .flowTph(target.getFlowTph())
                .diameterMm(target.getDiameterMm())
                .build();
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
