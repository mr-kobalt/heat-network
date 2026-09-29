package ru.lct.heating.graph;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.Builder;
import lombok.Value;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.SourceObject;

/**
 * Топология существующей сети по геометрии (ТП v2): смежность участков и
 * число существующих примыканий к камерам. Upstream-цепочки не используются
 * (FR-10, FR-12).
 */
@Value
@Builder
public class ExistingNetworkGraph {

    Map<String, NetworkSegment> segments;
    Map<String, HeatChamberObject> chambers;
    List<SourceObject> sources;
    /** Число существующих линейных участков, заканчивающихся в камере. */
    Map<String, Integer> chamberAttachments;
    List<String> warnings;

    public Optional<NetworkSegment> segment(String id) {
        return Optional.ofNullable(segments.get(id));
    }

    public int chamberAttachments(String chamberId) {
        return chamberAttachments.getOrDefault(chamberId, 0);
    }

    public List<SourceObject> sources() {
        return List.copyOf(sources);
    }
}
