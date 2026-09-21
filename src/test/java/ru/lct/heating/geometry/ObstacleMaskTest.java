package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heating.domain.GeometrySupport;

class ObstacleMaskTest {

    private final ObstacleMaskBuilder builder = new ObstacleMaskBuilder();

    @Test
    void freeSegment_returnsFalse() {
        ObstacleMask mask = mask(square(0, 0, 100, 100), 1.0, 1L << 30);
        assertThat(mask.anyBlockedAlong(new Coordinate(-40, 50), new Coordinate(-10, 50)))
                .isFalse();
    }

    @Test
    void segmentThroughObstacle_returnsTrue() {
        ObstacleMask mask = mask(square(0, 0, 100, 100), 1.0, 1L << 30);
        assertThat(mask.anyBlockedAlong(new Coordinate(10, 50), new Coordinate(90, 50)))
                .isTrue();
    }

    @Test
    void segmentAlongBoundary_isConservative() {
        ObstacleMask mask = mask(square(0, 0, 100, 100), 1.0, 1L << 30);
        assertThat(mask.anyBlockedAlong(new Coordinate(-10, 0), new Coordinate(110, 0)))
                .isTrue();
    }

    @Test
    void tinyBudget_coarsensCell() {
        ObstacleMask mask = mask(square(0, 0, 100, 100), 0.4, 16);
        assertThat(mask.cellM()).isGreaterThan(0.4);
        assertThat(mask.anyBlockedAlong(new Coordinate(10, 50), new Coordinate(90, 50)))
                .isTrue();
    }

    @Test
    void emptyIndex_returnsNull() {
        ObstacleIndex index = new ObstacleIndex(List.of());
        assertThat(builder.build(index, bounds(), 1.0, 4, 1L << 30, new ArrayList<>())).isNull();
    }

    private ObstacleMask mask(Geometry obstacle, double cell, long maxBytes) {
        List<PreparedGeometry> prepared = List.of(PreparedGeometryFactory.prepare(obstacle));
        return builder.build(new ObstacleIndex(prepared), bounds(), cell, 4, maxBytes,
                new ArrayList<>());
    }

    private Envelope bounds() {
        return new Envelope(-50, 150, -50, 150);
    }

    private Geometry square(double minX, double minY, double maxX, double maxY) {
        return GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(minX, minY), new Coordinate(maxX, minY),
                new Coordinate(maxX, maxY), new Coordinate(minX, maxY),
                new Coordinate(minX, minY)});
    }
}
