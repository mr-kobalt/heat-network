package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;

/**
 * Кандидаты точки врезки (ТП 2.4, ADR-0019/0026): существующие камеры и
 * **непрерывные проекции** на участки существующей сети. Дискретизация шагом
 * (сэмплы) не используется как основной источник — точка врезки может быть
 * любой точкой `heat_network`.
 */
@Component
public class TieInCandidateProvider {

    private static final double SAMPLE_STEP_M = 30.0;

    /**
     * Кандидаты точки врезки: существующие камеры и сэмплы вдоль участков
     * существующей сети (FR-21, FR-22). Непрерывные проекции — см.
     * {@link #projections(NetworkDataset, Coordinate)}.
     */
    public List<TieInCandidate> candidates(NetworkDataset dataset) {
        Map<String, TieInCandidate> unique = new LinkedHashMap<>();
        for (TieInCandidate chamber : chambers(dataset)) {
            put(unique, chamber);
        }
        if (dataset.getNetworkSegments() != null) {
            for (NetworkSegment segment : dataset.getNetworkSegments()) {
                LengthIndexedLine indexed = new LengthIndexedLine(segment.getGeometry());
                double length = segment.getGeometry().getLength();
                for (double position = 0.0; position <= length; position += SAMPLE_STEP_M) {
                    put(unique, candidate(segment, indexed.extractPoint(position)));
                }
                if (length > 0) {
                    put(unique, candidate(segment, indexed.extractPoint(length)));
                }
            }
        }
        return new ArrayList<>(unique.values());
    }

    private void put(Map<String, TieInCandidate> unique, TieInCandidate candidate) {
        String key = candidate.getExistingObjectId() + "@"
                + Math.round(candidate.getCoordinate().x * 100) + ":"
                + Math.round(candidate.getCoordinate().y * 100);
        unique.putIfAbsent(key, candidate);
    }

    /** Существующие камеры как кандидаты врезки. */
    public List<TieInCandidate> chambers(NetworkDataset dataset) {
        List<TieInCandidate> result = new ArrayList<>();
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            if (chamber.getGeometry() == null) {
                continue;
            }
            result.add(TieInCandidate.builder()
                    .existingObjectId(chamber.getId())
                    .existingObjectType("heat_chamber")
                    .existingDiameterMm(chamber.getDiameterMm())
                    .coordinate(chamber.getGeometry().getCoordinate())
                    .build());
        }
        return result;
    }

    /**
     * Проекции анкера на каждый участок существующей сети (по одной на участок).
     * Концы участков получаются как частный случай проекции.
     */
    public List<TieInCandidate> projections(NetworkDataset dataset, Coordinate anchor) {
        List<TieInCandidate> result = new ArrayList<>();
        if (anchor == null || dataset.getNetworkSegments() == null) {
            return result;
        }
        for (NetworkSegment segment : dataset.getNetworkSegments()) {
            if (segment.getGeometry() == null) {
                continue;
            }
            Coordinate projection = nearestPoint(segment.getGeometry(), anchor);
            if (projection != null) {
                result.add(candidate(segment, projection));
            }
        }
        return result;
    }

    /** Минимальное расстояние от точки до существующей сети (по проекциям). */
    public double distanceToNetwork(NetworkDataset dataset, Coordinate coordinate) {
        double best = Double.POSITIVE_INFINITY;
        if (dataset.getNetworkSegments() == null) {
            return best;
        }
        for (NetworkSegment segment : dataset.getNetworkSegments()) {
            if (segment.getGeometry() == null) {
                continue;
            }
            best = Math.min(best, segment.getGeometry().distance(
                    GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate)));
        }
        return best;
    }

    private Coordinate nearestPoint(LineString line, Coordinate coordinate) {
        Coordinate[] nearest = DistanceOp.nearestPoints(line,
                GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate));
        if (nearest.length == 0) {
            return line.getCoordinate();
        }
        double distance = coordinate.distance(nearest[0]);
        if (distance > 1e-9) {
            // Сдвиг внутрь на пренебрежимо малую величину не требуется; проекция
            // лежит на линии, что допустимо (граница запретной зоны сети SPECIAL).
            return nearest[0];
        }
        return nearest[0];
    }

    private TieInCandidate candidate(NetworkSegment segment, Coordinate coordinate) {
        return TieInCandidate.builder()
                .existingObjectId(segment.getId())
                .existingObjectType("heat_network")
                .existingDiameterMm(segment.getDiameterMm())
                .coordinate(coordinate)
                .build();
    }

    /** Уникализация кандидатов (по объекту и координате). */
    public List<TieInCandidate> distinct(List<TieInCandidate> candidates) {
        Map<String, TieInCandidate> unique = new LinkedHashMap<>();
        for (TieInCandidate candidate : candidates) {
            String key = candidate.getExistingObjectId() + "@"
                    + Math.round(candidate.getCoordinate().x * 100) + ":"
                    + Math.round(candidate.getCoordinate().y * 100);
            unique.putIfAbsent(key, candidate);
        }
        return new ArrayList<>(unique.values());
    }
}
