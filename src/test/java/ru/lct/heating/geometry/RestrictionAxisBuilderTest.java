package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heating.domain.GeometrySupport;

class RestrictionAxisBuilderTest {

    private final RestrictionAxisBuilder builder = new RestrictionAxisBuilder();

    @Test
    void axis_lineString_returnsSameLine() {
        LineString line = GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(0, 0), new Coordinate(10, 0)});
        Geometry axis = builder.axis(line);
        assertThat(axis.getLength()).isEqualTo(10.0);
    }

    @Test
    void axis_polygon_returnsExteriorRing() {
        Polygon polygon = (Polygon) GeometrySupport.GEOMETRY_FACTORY
                .createPolygon(new Coordinate[]{
                        new Coordinate(0, 0), new Coordinate(10, 0),
                        new Coordinate(10, 10), new Coordinate(0, 10),
                        new Coordinate(0, 0)});
        Geometry axis = builder.axis(polygon);
        assertThat(axis.getLength()).isEqualTo(40.0);
    }

    @Test
    void axis_null_returnsNull() {
        assertThat(builder.axis(null)).isNull();
    }
}
