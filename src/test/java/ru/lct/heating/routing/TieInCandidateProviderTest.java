package ru.lct.heating.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;

/**
 * ADR-0037: кандидаты врезки — камеры, сэмплы сети каждые 1 м, исключение
 * кандидатов в радиусе 1 м от камер.
 */
class TieInCandidateProviderTest {

    private final AppProperties properties = new AppProperties();
    private final TieInCandidateProvider provider = new TieInCandidateProvider(properties);

    @Test
    void samplesNetworkEveryMeterAndExcludesCandidatesNearChambers() {
        NetworkSegment segment = NetworkSegment.builder().id("seg").diameterMm(400)
                .geometry(line(0, 0, 10, 0)).build();
        HeatChamberObject chamber = HeatChamberObject.builder().id("ch").diameterMm(400)
                .geometry(point(0, 0)).build();
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of())
                .networkSegments(List.of(segment))
                .heatChambers(List.of(chamber))
                .connectionPoints(List.of())
                .restrictions(List.of())
                .build();

        List<TieInCandidate> candidates = provider.candidates(dataset);

        assertThat(candidates).anyMatch(candidate ->
                "heat_chamber".equals(candidate.getExistingObjectType()));
        List<TieInCandidate> network = candidates.stream()
                .filter(candidate -> "heat_network".equals(candidate.getExistingObjectType()))
                .collect(java.util.stream.Collectors.toList());
        // 10 м с шагом 1 м: сэмплы 0..10, из них в радиусе 1 м от камеры исключаются.
        assertThat(network).hasSizeBetween(9, 10);
        assertThat(network).allMatch(candidate ->
                candidate.getCoordinate().distance(new Coordinate(0, 0)) > 1.0);
    }

    @Test
    void projectionsAreAddedByCallerAndExcludedNearChambers() {
        NetworkSegment segment = NetworkSegment.builder().id("seg").diameterMm(400)
                .geometry(line(0, 0, 10, 0)).build();
        HeatChamberObject chamber = HeatChamberObject.builder().id("ch").diameterMm(400)
                .geometry(point(5, 0)).build();
        NetworkDataset dataset = NetworkDataset.builder()
                .sources(List.of())
                .networkSegments(List.of(segment))
                .heatChambers(List.of(chamber))
                .connectionPoints(List.of())
                .restrictions(List.of())
                .build();

        List<TieInCandidate> projections = provider.projections(dataset, new Coordinate(5, 3));
        List<TieInCandidate> filtered = provider.excludeNearChambers(dataset, projections);

        assertThat(projections).isNotEmpty();
        assertThat(filtered).isEmpty();
    }

    private LineString line(double x1, double y1, double x2, double y2) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(
                new Coordinate[]{new Coordinate(x1, y1), new Coordinate(x2, y2)});
    }

    private Point point(double x, double y) {
        return GeometrySupport.GEOMETRY_FACTORY.createPoint(new Coordinate(x, y));
    }
}
