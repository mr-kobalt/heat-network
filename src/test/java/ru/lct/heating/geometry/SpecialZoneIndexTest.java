package ru.lct.heating.geometry;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.domain.GeometrySupport;

class SpecialZoneIndexTest {

    private final RestrictionAxisBuilder axisBuilder = new RestrictionAxisBuilder();

    @Test
    void spans_verticalCrossingHorizontalZone_oneSpan() {
        SpecialZoneIndex index = index(zone(horizontalLine(), 5.0, 1.6, null));
        List<String> warnings = new ArrayList<>();
        List<SpecialSpan> spans = index.spans(line(50, -20, 50, 20), warnings);
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getStartDistanceM()).isCloseTo(15.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(spans.get(0).getEndDistanceM()).isCloseTo(25.0, org.assertj.core.data.Offset.offset(0.01));
        assertThat(spans.get(0).getKSpecial()).isEqualTo(1.6);
        assertThat(warnings).isEmpty();
    }

    @Test
    void spans_overlappingZones_mergedWithMaxCoefficient() {
        SpecialZoneIndex index = index(
                zone(horizontalLine(), 5.0, 1.6, null),
                zone(horizontalLine(), 5.0, 1.75, null));
        List<SpecialSpan> spans = index.spans(line(50, -20, 50, 20), new ArrayList<>());
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getKSpecial()).isEqualTo(1.75);
    }

    @Test
    void spans_noIntersection_empty() {
        SpecialZoneIndex index = index(zone(horizontalLine(), 5.0, 1.6, null));
        assertThat(index.spans(line(50, 40, 50, 60), new ArrayList<>())).isEmpty();
    }

    @Test
    void spans_perpendicularCrossing_noAngleWarning() {
        SpecialZoneIndex index = index(zone(horizontalLine(), 5.0, 1.6, 45.0));
        List<String> warnings = new ArrayList<>();
        index.spans(line(50, -20, 50, 20), warnings);
        assertThat(warnings).isEmpty();
    }

    @Test
    void spans_shallowCrossing_angleWarning() {
        SpecialZoneIndex index = index(zone(horizontalLine(), 5.0, 1.6, 45.0));
        List<String> warnings = new ArrayList<>();
        double dx = 80 * Math.cos(Math.toRadians(30));
        double dy = 80 * Math.sin(Math.toRadians(30));
        index.spans(line(-20, -20, -20 + dx, -20 + dy), warnings);
        assertThat(warnings).hasSize(1);
        assertThat(warnings.get(0)).contains("CROSSING_ANGLE_TOO_SHALLOW");
    }

    @Test
    void spans_parallelInsideZone_noSpan() {
        // E32: движение вдоль препятствия (без пересечения оси) не спецпроход.
        SpecialZoneIndex index = index(zone(horizontalLine(), 5.0, 1.6, null));
        assertThat(index.spans(line(0, 2, 100, 2), new ArrayList<>())).isEmpty();
    }

    private SpecialZone zone(Geometry restriction, double buffer, double kSpecial, Double angleMin) {
        return SpecialZone.builder()
                .restrictionType("road")
                .kSpecial(kSpecial)
                .angleMinDeg(angleMin)
                .bufferM(buffer)
                .axis(axisBuilder.axis(restriction))
                .zone(restriction.buffer(buffer))
                .build();
    }

    private SpecialZoneIndex index(SpecialZone... zones) {
        return new SpecialZoneIndex(List.of(zones));
    }

    private LineString horizontalLine() {
        return line(0, 0, 100, 0);
    }

    private LineString line(double... coordinates) {
        Coordinate[] points = new Coordinate[coordinates.length / 2];
        for (int i = 0; i < points.length; i++) {
            points[i] = new Coordinate(coordinates[i * 2], coordinates[i * 2 + 1]);
        }
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(points);
    }
}
