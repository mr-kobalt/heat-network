package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.geometry.ObstacleIndex;

class VisibilityGraphRouterTest {

    private final VisibilityGraphRouter router = new VisibilityGraphRouter(new AppProperties());

    @Test
    void straightPathWhenClear() {
        ObstacleIndex index = new ObstacleIndex(List.of());
        List<Coordinate> path = router.findPath(new Coordinate(0, 0), new Coordinate(10, 0), index);
        assertThat(path).containsExactly(new Coordinate(0, 0), new Coordinate(10, 0));
    }

    @Test
    void detoursAroundBlockedObstacle() {
        Polygon obstacle = GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(4, -2), new Coordinate(6, -2),
                new Coordinate(6, 2), new Coordinate(4, 2), new Coordinate(4, -2)});
        ObstacleIndex index = new ObstacleIndex(
                List.of(PreparedGeometryFactory.prepare(obstacle)));

        List<Coordinate> path = router.findPath(new Coordinate(0, 0), new Coordinate(10, 0), index);

        assertThat(path).isNotNull();
        double length = 0;
        for (int i = 1; i < path.size(); i++) {
            length += path.get(i - 1).distance(path.get(i));
        }
        assertThat(length).isGreaterThan(10.0);
        assertThat(index.isBlocked(GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(0, 0), new Coordinate(10, 0)}))).isTrue();
    }

    @Test
    void startInsideObstacle_isUnreachable() {
        // Точка подключения не должна маршрутизироваться изнутри запретной зоны:
        // вывод к ней формирует OksApproachResolver, а не «дыра» в препятствии.
        Polygon ring = GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(-1, -1), new Coordinate(1, -1),
                new Coordinate(1, 1), new Coordinate(-1, 1), new Coordinate(-1, -1)});
        ObstacleIndex index = new ObstacleIndex(
                List.of(PreparedGeometryFactory.prepare(ring)));
        List<Coordinate> path = router.findPath(new Coordinate(0, 0), new Coordinate(10, 0), index);
        assertThat(path).isNull();
    }


}
