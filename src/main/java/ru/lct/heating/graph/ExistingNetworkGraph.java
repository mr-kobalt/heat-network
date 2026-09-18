package ru.lct.heating.graph;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.Value;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.SourceObject;

/**
 * Топология существующей сети по {@code upstream_object_id} (FR-10, FR-11).
 */
@Value
@Builder
public class ExistingNetworkGraph {

    Map<String, NetworkSegment> segments;
    Map<String, HeatChamberObject> chambers;
    List<SourceObject> sources;
    Map<String, Double> distanceToSourceM;
    Map<String, List<String>> chainToSourceIds;
    List<String> warnings;

    public Optional<NetworkSegment> segment(String id) {
        return Optional.ofNullable(segments.get(id));
    }

    public Optional<HeatChamberObject> chamber(String id) {
        return Optional.ofNullable(chambers.get(id));
    }

    public Optional<Double> distanceToSource(String id) {
        return Optional.ofNullable(distanceToSourceM.get(id));
    }

    public List<String> chainToSourceIds(String id) {
        return chainToSourceIds.getOrDefault(id, java.util.Collections.emptyList());
    }

    public List<SourceObject> sources() {
        return Collections.unmodifiableList(sources);
    }
}
