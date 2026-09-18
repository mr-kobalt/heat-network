package ru.lct.heating.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Point;
import ru.lct.heating.domain.GeometrySupport;

class CrsTransformerTest {

    private final CrsTransformer transformer = new CrsTransformer();

    @Test
    void transformsMoscowPointToUtmAndBack() {
        Point wgs84 = GeometrySupport.GEOMETRY_FACTORY
                .createPoint(new Coordinate(37.6344, 55.6995));
        var utm = transformer.toUtm(wgs84);
        assertThat(utm.getCoordinate().x).isGreaterThan(400_000);
        assertThat(utm.getCoordinate().x).isLessThan(500_000);
        assertThat(utm.getCoordinate().y).isGreaterThan(6_000_000);

        var back = transformer.toWgs84(utm);
        assertThat(back.getCoordinate().x).isCloseTo(37.6344, org.assertj.core.data.Offset.offset(1e-5));
        assertThat(back.getCoordinate().y).isCloseTo(55.6995, org.assertj.core.data.Offset.offset(1e-5));
    }
}
