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
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;

/**
 * Кандидаты точки врезки (ТП 2.4, ADR-0026/0037): существующие камеры,
 * **сэмплы** вдоль участков сети с шагом {@code tie-in-sample-step-m} (по
 * умолчанию 1 м) и непрерывные проекции точек выхода на участки. Кандидат в
 * радиусе {@code tie-in-chamber-exclusion-m} от существующей камеры исключается,
 * чтобы не плодить точки рядом с уже готовым узлом.
 */
@Component
public class TieInCandidateProvider {

    private final AppProperties appProperties;

    public TieInCandidateProvider(AppProperties appProperties) {
        this.appProperties = appProperties;
    }

    public List<TieInCandidate> candidates(NetworkDataset dataset) {
        List<HeatChamberObject> chambers = dataset.getHeatChambers() == null
                ? List.of() : dataset.getHeatChambers();
        List<Coordinate> chamberCoordinates = new ArrayList<>();
        Map<String, TieInCandidate> unique = new LinkedHashMap<>();
        for (HeatChamberObject chamber : chambers) {
            if (chamber.getGeometry() == null) {
                continue;
            }
            chamberCoordinates.add(chamber.getGeometry().getCoordinate());
            put(unique, chamberCandidate(chamber));
        }
        double step = sampleStepM();
        if (dataset.getNetworkSegments() != null) {
            for (NetworkSegment segment : dataset.getNetworkSegments()) {
                LengthIndexedLine indexed = new LengthIndexedLine(segment.getGeometry());
                double length = segment.getGeometry().getLength();
                for (double position = 0.0; position <= length; position += step) {
                    put(unique, candidate(segment, indexed.extractPoint(position)));
                }
                if (length > 0) {
                    put(unique, candidate(segment, indexed.extractPoint(length)));
                }
            }
        }
        return excludeNearChambers(new ArrayList<>(unique.values()), chamberCoordinates);
    }

    /** Существующие камеры как кандидаты врезки. */
    public List<TieInCandidate> chambers(NetworkDataset dataset) {
        List<TieInCandidate> result = new ArrayList<>();
        if (dataset.getHeatChambers() == null) {
            return result;
        }
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            if (chamber.getGeometry() == null) {
                continue;
            }
            result.add(chamberCandidate(chamber));
        }
        return result;
    }

    /**
     * Проекции анкера (точки выхода) на каждый участок существующей сети — по
     * одной на участок; концы участков получаются как частный случай.
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

    /** Исключает кандидатов в радиусе {@code tie-in-chamber-exclusion-m} от камер. */
    public List<TieInCandidate> excludeNearChambers(NetworkDataset dataset,
                                                    List<TieInCandidate> candidates) {
        List<Coordinate> chamberCoordinates = new ArrayList<>();
        if (dataset.getHeatChambers() != null) {
            for (HeatChamberObject chamber : dataset.getHeatChambers()) {
                if (chamber.getGeometry() != null) {
                    chamberCoordinates.add(chamber.getGeometry().getCoordinate());
                }
            }
        }
        return excludeNearChambers(candidates, chamberCoordinates);
    }

    private List<TieInCandidate> excludeNearChambers(List<TieInCandidate> candidates,
                                                     List<Coordinate> chamberCoordinates) {
        double exclusion = chamberExclusionM();
        if (exclusion <= 0.0 || chamberCoordinates.isEmpty()) {
            return candidates;
        }
        List<TieInCandidate> filtered = new ArrayList<>(candidates.size());
        for (TieInCandidate candidate : candidates) {
            if ("heat_chamber".equals(candidate.getExistingObjectType())
                    || !nearChamber(candidate.getCoordinate(), chamberCoordinates, exclusion)) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private boolean nearChamber(Coordinate coordinate, List<Coordinate> chambers, double radius) {
        for (Coordinate chamber : chambers) {
            if (coordinate.distance(chamber) <= radius) {
                return true;
            }
        }
        return false;
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

    private void put(Map<String, TieInCandidate> unique, TieInCandidate candidate) {
        unique.putIfAbsent(key(candidate), candidate);
    }

    private String key(TieInCandidate candidate) {
        return candidate.getExistingObjectId() + "@"
                + Math.round(candidate.getCoordinate().x * 100) + ":"
                + Math.round(candidate.getCoordinate().y * 100);
    }

    private double sampleStepM() {
        return appProperties.getTieInSampleStepM() > 0
                ? appProperties.getTieInSampleStepM() : 1.0;
    }

    private double chamberExclusionM() {
        return appProperties.getTieInChamberExclusionM();
    }

    private TieInCandidate chamberCandidate(HeatChamberObject chamber) {
        return TieInCandidate.builder()
                .existingObjectId(chamber.getId())
                .existingObjectType("heat_chamber")
                .existingDiameterMm(chamber.getDiameterMm())
                .coordinate(chamber.getGeometry().getCoordinate())
                .build();
    }

    private Coordinate nearestPoint(LineString line, Coordinate coordinate) {
        Coordinate[] nearest = DistanceOp.nearestPoints(line,
                GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate));
        if (nearest.length == 0) {
            return line.getCoordinate();
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
            unique.putIfAbsent(key(candidate), candidate);
        }
        return new ArrayList<>(unique.values());
    }
}
