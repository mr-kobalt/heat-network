package ru.lct.heating.domain;

import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Envelope;

/**
 * Нормализованный набор входных данных (координаты уже в EPSG:32637).
 */
@Value
@Builder
public class NetworkDataset {

    List<SourceObject> sources;
    List<NetworkSegment> networkSegments;
    List<HeatChamberObject> heatChambers;
    List<OksFutureObject> oksFutures;
    List<OksConnectionPointObject> connectionPoints;
    List<OksExistingObject> oksExisting;
    List<RestrictionObject> restrictions;
    Envelope bounds;

    public Optional<SourceObject> firstSource() {
        return sources.stream().findFirst();
    }
}
