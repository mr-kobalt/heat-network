package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import ru.lct.heating.domain.GeometrySupport;

class SpecialSpanSplitterTest {

    private final SpecialSpanSplitter splitter = new SpecialSpanSplitter();

    @Test
    void split_noSpans_singleBaseChunk() {
        LineString route = line(0, 0, 100, 0);
        List<RouteChunk> chunks = splitter.split(route, List.of());
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).isSpecial()).isFalse();
        assertThat(chunks.get(0).layingMethod()).isEqualTo("base");
    }

    @Test
    void split_oneSpan_threeChunks() {
        LineString route = line(0, 0, 100, 0);
        SpecialSpan span = SpecialSpan.builder()
                .startDistanceM(20).endDistanceM(40).kSpecial(1.6).build();
        List<RouteChunk> chunks = splitter.split(route, List.of(span));
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).isSpecial()).isFalse();
        assertThat(chunks.get(1).isSpecial()).isTrue();
        assertThat(chunks.get(1).getKSpecial()).isEqualTo(1.6);
        assertThat(chunks.get(1).layingMethod()).isEqualTo("special");
        assertThat(chunks.get(2).isSpecial()).isFalse();
    }

    @Test
    void split_spanCoversWholeRoute_singleSpecialChunk() {
        LineString route = line(0, 0, 100, 0);
        SpecialSpan span = SpecialSpan.builder()
                .startDistanceM(0).endDistanceM(100).kSpecial(1.75).build();
        List<RouteChunk> chunks = splitter.split(route, List.of(span));
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).isSpecial()).isTrue();
        assertThat(chunks.get(0).getKSpecial()).isEqualTo(1.75);
    }

    @Test
    void split_specialChunk_alwaysStraight() {
        // ТП v2 §4: спецпроход — один прямой участок; внутри пересечения мин.
        // расстояние не проверяется, поэтому выпрямление безусловно.
        LineString route = line(0, 0, 50, 50, 100, 0);
        SpecialSpan span = SpecialSpan.builder()
                .startDistanceM(0).endDistanceM(route.getLength()).kSpecial(1.6).build();
        Polygon obstacle = GeometrySupport.GEOMETRY_FACTORY.createPolygon(new Coordinate[]{
                new Coordinate(40, -10), new Coordinate(60, -10), new Coordinate(60, 10),
                new Coordinate(40, 10), new Coordinate(40, -10)});
        ObstacleIndex index = new ObstacleIndex(
                List.of(PreparedGeometryFactory.prepare(obstacle)));

        assertThat(splitter.split(route, List.of(span)).get(0).getGeometry().getNumPoints())
                .isEqualTo(2);
        assertThat(splitter.split(route, List.of(span), index, new ArrayList<>())
                .get(0).getGeometry().getNumPoints()).isEqualTo(2);
    }

    private LineString line(double... coordinates) {
        Coordinate[] points = new Coordinate[coordinates.length / 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Coordinate(coordinates[i * 2], coordinates[i * 2 + 1]);
        }
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(points);
    }
}
