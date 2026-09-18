package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;

/**
 * Формирование кандидатов точек врезки: существующие камеры и сэмплы вдоль
 * участков существующей сети (FR-21, FR-22).
 */
@Component
public class TieInCandidateProvider {

    private static final double SAMPLE_STEP_M = 30.0;

    public List<TieInCandidate> candidates(NetworkDataset dataset) {
        Map<String, TieInCandidate> unique = new LinkedHashMap<>();
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            put(unique, TieInCandidate.builder()
                    .existingObjectId(chamber.getId())
                    .existingObjectType("heat_chamber")
                    .existingDiameterMm(chamber.getDiameterMm())
                    .coordinate(chamber.getGeometry().getCoordinate())
                    .build());
        }
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
        return new ArrayList<>(unique.values());
    }

    private TieInCandidate candidate(NetworkSegment segment, Coordinate coordinate) {
        return TieInCandidate.builder()
                .existingObjectId(segment.getId())
                .existingObjectType("heat_network")
                .existingDiameterMm(segment.getDiameterMm())
                .coordinate(coordinate)
                .build();
    }

    private void put(Map<String, TieInCandidate> unique, TieInCandidate candidate) {
        String key = candidate.getExistingObjectId() + "@"
                + Math.round(candidate.getCoordinate().x * 100) + ":" 
                + Math.round(candidate.getCoordinate().y * 100);
        unique.putIfAbsent(key, candidate);
    }
}
