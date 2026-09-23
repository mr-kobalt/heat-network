package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heating.domain.GeometrySupport;

class SpecialGateCarverTest {

    private final SpecialGateCarver carver = new SpecialGateCarver(new RestrictionAxisBuilder());

    @Test
    void carve_keepsBandBetweenGates_butOpensGates() {
        LineString road = line(0, 0, 100, 0);
        Geometry carved = carver.carve(road, 2.0, 5.0, 1.0);

        // Полоса вне «ворот» непроходима.
        assertThat(carved.contains(point(2.5, 1.5))).isTrue();
        assertThat(carved.contains(point(52.5, -1.0))).isTrue();
        // «Ворота» перпендикулярны оси и прорезаны насквозь.
        assertThat(carved.contains(point(5.0, 1.5))).isFalse();
        assertThat(carved.contains(point(5.0, -1.5))).isFalse();
        assertThat(carved.contains(point(50.0, 0.0))).isFalse();
    }

    @Test
    void carve_emptyGeometry_returnsEmpty() {
        Geometry empty = GeometrySupport.GEOMETRY_FACTORY.createLineString();
        assertThat(carver.carve(empty, 2.0, 5.0, 1.0).isEmpty()).isTrue();
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }

    private Point point(double x, double y) {
        return GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y));
    }
}
