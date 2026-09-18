package ru.lct.heating.graph;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.SourceObject;

/**
 * Построение графа существующей сети и проверка цепочек к источнику (FR-10, FR-11, FR-08).
 */
@Component
public class NetworkGraphBuilder {

    public ExistingNetworkGraph build(NetworkDataset dataset) {
        Map<String, NetworkSegment> segments = new LinkedHashMap<>();
        Map<String, HeatChamberObject> chambers = new LinkedHashMap<>();
        Map<String, String> upstream = new HashMap<>();
        Set<String> sourceIds = new HashSet<>();
        for (SourceObject source : dataset.getSources()) {
            sourceIds.add(source.getId());
        }
        for (NetworkSegment segment : dataset.getNetworkSegments()) {
            segments.put(segment.getId(), segment);
            if (segment.getUpstreamObjectId() != null) {
                upstream.put(segment.getId(), segment.getUpstreamObjectId());
            }
        }
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            chambers.put(chamber.getId(), chamber);
            if (chamber.getUpstreamObjectId() != null) {
                upstream.put(chamber.getId(), chamber.getUpstreamObjectId());
            }
        }

        List<String> warnings = new ArrayList<>();
        validateReferences(segments, chambers, upstream, sourceIds, warnings);

        Map<String, Double> distance = new LinkedHashMap<>();
        Map<String, List<String>> chains = new LinkedHashMap<>();
        for (String id : allIds(segments, chambers)) {
            computeChain(id, upstream, segments, chambers, sourceIds, new HashSet<>(), warnings);
        }
        for (String id : allIds(segments, chambers)) {
            distance.put(id, cumulativeLength(id, upstream, segments));
            chains.put(id, chainIds(id, upstream, sourceIds));
        }

        return ExistingNetworkGraph.builder()
                .segments(segments)
                .chambers(chambers)
                .sources(dataset.getSources())
                .distanceToSourceM(distance)
                .chainToSourceIds(chains)
                .warnings(warnings)
                .build();
    }

    private void validateReferences(Map<String, NetworkSegment> segments,
                                    Map<String, HeatChamberObject> chambers,
                                    Map<String, String> upstream,
                                    Set<String> sourceIds,
                                    List<String> warnings) {
        for (Map.Entry<String, String> entry : upstream.entrySet()) {
            String target = entry.getValue();
            if (!segments.containsKey(target) && !chambers.containsKey(target) && !sourceIds.contains(target)) {
                warnings.add("UPSTREAM_NOT_FOUND: объект " + entry.getKey()
                        + " ссылается на неизвестный upstream_object_id=" + target);
            }
        }
    }

    private void computeChain(String id, Map<String, String> upstream,
                              Map<String, NetworkSegment> segments,
                              Map<String, HeatChamberObject> chambers,
                              Set<String> sourceIds, Set<String> visited, List<String> warnings) {
        String current = id;
        while (current != null) {
            if (sourceIds.contains(current)) {
                return;
            }
            if (!visited.add(current)) {
                warnings.add("UPSTREAM_CYCLE: обнаружен цикл в цепочке к источнику у " + id);
                return;
            }
            if (!segments.containsKey(current) && !chambers.containsKey(current)) {
                warnings.add("UPSTREAM_CHAIN_BROKEN: цепочка от " + id + " обрывается");
                return;
            }
            current = upstream.get(current);
        }
    }

    private double cumulativeLength(String id, Map<String, String> upstream,
                                    Map<String, NetworkSegment> segments) {
        double total = 0.0;
        String current = id;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current)) {
            NetworkSegment segment = segments.get(current);
            if (segment != null && segment.getGeometry() != null) {
                total += segment.getGeometry().getLength();
            }
            current = upstream.get(current);
        }
        return total;
    }

    private List<String> chainIds(String id, Map<String, String> upstream, Set<String> sourceIds) {
        List<String> chain = new ArrayList<>();
        String current = id;
        Set<String> visited = new HashSet<>();
        while (current != null && visited.add(current) && !sourceIds.contains(current)) {
            chain.add(current);
            current = upstream.get(current);
        }
        return chain;
    }

    private Set<String> allIds(Map<String, NetworkSegment> segments,
                               Map<String, HeatChamberObject> chambers) {
        Set<String> ids = new HashSet<>();
        ids.addAll(segments.keySet());
        ids.addAll(chambers.keySet());
        return ids;
    }
}
