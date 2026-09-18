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
    void buildsChainToSourceAndDistance() {
        SourceObject source = SourceObject.builder()
                .id("S").geometry(point(0, 0)).build();
        HeatChamberObject chamber = HeatChamberObject.builder()
                .id("C").upstreamObjectId("S").geometry(point(100, 0)).build();
        NetworkSegment segment = NetworkSegment.builder()
                .id("N").diameterMm(300).upstreamObjectId("C")
                .geometry(line(100, 0, 200, 0)).build();
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of(source))
                .heatChambers(List.of(chamber))
                .networkSegments(List.of(segment))
                .restrictions(List.of())
                .connectionPoints(List.of())
                .oksFutures(List.of())
                .oksExisting(List.of())
                .build();

        ExistingNetworkGraph graph = builder.build(dataset);

        assertThat(graph.distanceToSource("N")).hasValue(100.0);
        assertThat(graph.chainToSourceIds("N")).containsExactly("N", "C");
        assertThat(graph.getWarnings()).isEmpty();
    }

    @Test
    void reportsUnknownUpstreamReference() {
        NetworkSegment segment = NetworkSegment.builder()
                .id("N").diameterMm(300).upstreamObjectId("missing")
                .geometry(line(0, 0, 10, 0)).build();
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of())
                .heatChambers(List.of())
                .networkSegments(List.of(segment))
                .restrictions(List.of())
                .connectionPoints(List.of())
                .oksFutures(List.of())
                .oksExisting(List.of())
                .build();

        ExistingNetworkGraph graph = builder.build(dataset);

        assertThat(graph.getWarnings())
                .anyMatch(warning -> warning.startsWith("UPSTREAM_NOT_FOUND"));
    }

    private Point point(double x, double y) {
        return GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y));
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }
}
