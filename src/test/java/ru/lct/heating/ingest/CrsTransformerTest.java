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

    /**
     * Round-trip WGS84 → UTM 37N → WGS84 должен быть практически точным
     * (CRS84/датум не дают смещения): иначе вход и результат визуализатора
     * разъезжались бы.
     */
    @Test
    void roundTripIsExactWithinMillimeters() {
        double[][] points = {
                {37.6344054, 55.6994811},
                {37.6360932, 55.6972966},
                {37.6410302, 55.7023537},
                {37.6320100, 55.7000500},
        };
        for (double[] point : points) {
            var wgs84 = GeometrySupport.GEOMETRY_FACTORY
                    .createPoint(new Coordinate(point[0], point[1]));
            var back = transformer.toWgs84(transformer.toUtm(wgs84));
            assertThat(back.getCoordinate().x).isCloseTo(point[0], org.assertj.core.data.Offset.offset(1e-7));
            assertThat(back.getCoordinate().y).isCloseTo(point[1], org.assertj.core.data.Offset.offset(1e-7));
        }
    }
}
