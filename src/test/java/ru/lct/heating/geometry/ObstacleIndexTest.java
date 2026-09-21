package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heating.domain.GeometrySupport;

class ObstacleIndexTest {

    @Test
    void isInteriorBlocked_crossing_isTrue() {
        ObstacleIndex index = index(square(0, 0, 100, 100));
        assertThat(index.isInteriorBlocked(line(10, 50, 90, 50))).isTrue();
    }

    @Test
    void isInteriorBlocked_touchingBoundary_isFalse() {
        ObstacleIndex index = index(square(0, 0, 100, 100));
        assertThat(index.isInteriorBlocked(line(-10, 0, 110, 0))).isFalse();
    }

    @Test
    void isInteriorBlocked_outside_isFalse() {
        ObstacleIndex index = index(square(0, 0, 100, 100));
        assertThat(index.isInteriorBlocked(line(-40, 50, -10, 50))).isFalse();
    }

    @Test
    void isInteriorBlocked_ignoredObstacle_isFalse() {
        PreparedGeometry prepared = PreparedGeometryFactory.prepare(square(0, 0, 100, 100));
        ObstacleIndex index = new ObstacleIndex(List.of(prepared));

        assertThat(index.isInteriorBlocked(line(10, 50, 90, 50), Set.of(prepared))).isFalse();
    }

    @Test
    void isBlocked_touchingBoundary_isTrue() {
        ObstacleIndex index = index(square(0, 0, 100, 100));
        assertThat(index.isBlocked(line(-10, 0, 110, 0))).isTrue();
    }

    private ObstacleIndex index(Geometry obstacle) {
        return new ObstacleIndex(List.of(PreparedGeometryFactory.prepare(obstacle)));
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }

    private Geometry square(double minX, double minY, double maxX, double maxY) {
        return GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)});
    }
}
