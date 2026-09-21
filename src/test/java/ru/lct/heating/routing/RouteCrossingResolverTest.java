package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndex;

class RouteCrossingResolverTest {

    private final RouteCrossingResolver resolver = new RouteCrossingResolver(
            new VisibilityGraphRouter(new AppProperties()), new LineStringSimplifier());

    @Test
    void resolve_crossingEdges_rebuildsOneToAvoidCrossing() {
        ForestEdge horizontal = edge("e1", "A", "B", 0, 0, 100, 0);
        ForestEdge vertical = edge("e2", "C", "D", 50, -10, 50, 10);
        List<ForestEdge> result = resolver.resolve(List.of(horizontal, vertical),
                new ObstacleIndex(List.of()), Map.of(), new ArrayList<>());
        assertThat(intersection(result.get(0), result.get(1)).isEmpty()).isTrue();
    }

    @Test
    void resolve_edgesSharingNode_unchanged() {
        ForestEdge first = edge("e1", "A", "B", 0, 0, 100, 0);
        ForestEdge second = edge("e2", "B", "C", 100, 0, 100, 50);
        List<ForestEdge> result = resolver.resolve(List.of(first, second),
                new ObstacleIndex(List.of()), Map.of(), new ArrayList<>());
        assertThat(result.get(0).getCoordinates()).isEqualTo(first.getCoordinates());
        assertThat(result.get(1).getCoordinates()).isEqualTo(second.getCoordinates());
    }

    @Test
    void resolve_nonCrossingEdges_unchanged() {
        ForestEdge first = edge("e1", "A", "B", 0, 0, 100, 0);
        ForestEdge second = edge("e2", "C", "D", 0, 50, 100, 50);
        List<ForestEdge> result = resolver.resolve(List.of(first, second),
                new ObstacleIndex(List.of()), Map.of(), new ArrayList<>());
        assertThat(result.get(0).getCoordinates()).isEqualTo(first.getCoordinates());
        assertThat(result.get(1).getCoordinates()).isEqualTo(second.getCoordinates());
    }

    private org.locationtech.jts.geom.Geometry intersection(ForestEdge first, ForestEdge second) {
        return line(first.getCoordinates()).intersection(line(second.getCoordinates()));
    }

    private LineString line(List<Coordinate> coordinates) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(coordinates.toArray(new Coordinate[0]));
    }

    private ForestEdge edge(String id, String from, String to,
                            double x1, double y1, double x2, double y2) {
        return ForestEdge.builder()
                .id(id)
                .fromNodeId(from)
                .toNodeId(to)
                .coordinates(List.of(new Coordinate(x1, y1), new Coordinate(x2, y2)))
                .flowTph(10.0)
                .diameterMm(100)
                .build();
    }
}
