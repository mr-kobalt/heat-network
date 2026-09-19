package ru.lct.heating.graph;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.SourceObject;

class NetworkGraphBuilderTest {

    private final NetworkGraphBuilder builder = new NetworkGraphBuilder();

    @Test
    void countsChamberAttachmentsByGeometry() {
        // Проходная линия через камеру: два участка заканчиваются в камере.
        HeatChamberObject chamber = HeatChamberObject.builder()
                .id("C").geometry(point(100, 0)).build();
        NetworkSegment left = NetworkSegment.builder()
                .id("L").diameterMm(300).geometry(line(0, 0, 100, 0)).build();
        NetworkSegment right = NetworkSegment.builder()
                .id("R").diameterMm(300).geometry(line(100, 0, 200, 0)).build();
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of(SourceObject.builder().id("S").geometry(point(0, 0)).build()))
                .heatChambers(List.of(chamber))
                .networkSegments(List.of(left, right))
                .restrictions(List.of())
                .connectionPoints(List.of())
                .oksFutures(List.of())
                .oksExisting(List.of())
                .build();

        ExistingNetworkGraph graph = builder.build(dataset);

        assertThat(graph.segment("L")).isPresent();
        assertThat(graph.chamberAttachments("C")).isEqualTo(2);
        assertThat(graph.getWarnings()).isEmpty();
    }

    @Test
    void warnsWhenNoSource() {
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of())
                .heatChambers(List.of())
                .networkSegments(List.of())
                .restrictions(List.of())
                .connectionPoints(List.of())
                .oksFutures(List.of())
                .oksExisting(List.of())
                .build();

        ExistingNetworkGraph graph = builder.build(dataset);

        assertThat(graph.getWarnings()).contains("NO_SOURCE");
    }

    private Point point(double x, double y) {
        return GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y));
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
