package ru.lct.heating.graph;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.SourceObject;

/**
 * Топология существующей сети по геометрии (ТП v2, FR-10, FR-12):
 * участки индексируются, для камер считается число существующих примыканий.
 * Проходная линия, разделённая камерой, даёт два примыкания (разъяснение 12).
 */
@Component
public class NetworkGraphBuilder {

    private static final double ATTACH_TOLERANCE_M = 0.5;

    public ExistingNetworkGraph build(NetworkDataset dataset) {
        Map<String, NetworkSegment> segments = new LinkedHashMap<>();
        Map<String, HeatChamberObject> chambers = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();

        for (NetworkSegment segment : dataset.getNetworkSegments()) {
            if (segments.put(segment.getId(), segment) != null) {
                warnings.add("DUPLICATE_ID: участок " + segment.getId() + " встречается повторно");
            }
        }
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            if (chambers.put(chamber.getId(), chamber) != null) {
                warnings.add("DUPLICATE_ID: камера " + chamber.getId() + " встречается повторно");
            }
        }

        Map<String, Integer> attachments = new LinkedHashMap<>();
        for (String chamberId : chambers.keySet()) {
            attachments.put(chamberId, 0);
        }
        for (NetworkSegment segment : segments.values()) {
            if (segment.getGeometry() == null) {
                continue;
            }
            Coordinate[] coordinates = segment.getGeometry().getCoordinates();
            attach(coordinates[0], chambers, attachments);
            attach(coordinates[coordinates.length - 1], chambers, attachments);
        }

        List<SourceObject> sources = dataset.getSources() == null
                ? List.of() : dataset.getSources();
        if (sources.isEmpty()) {
            warnings.add("NO_SOURCE");
        }

        return ExistingNetworkGraph.builder()
                .segments(segments)
                .chambers(chambers)
                .sources(sources)
                .chamberAttachments(attachments)
                .warnings(warnings)
                .build();
    }

    private void attach(Coordinate endpoint, Map<String, HeatChamberObject> chambers,
                        Map<String, Integer> attachments) {
        Set<String> matched = new HashSet<>();
        for (Map.Entry<String, HeatChamberObject> entry : chambers.entrySet()) {
            HeatChamberObject chamber = entry.getValue();
            if (chamber.getGeometry() == null) {
                continue;
            }
            if (endpoint.distance(chamber.getGeometry().getCoordinate()) <= ATTACH_TOLERANCE_M) {
                matched.add(entry.getKey());
            }
        }
        for (String chamberId : matched) {
            attachments.merge(chamberId, 1, Integer::sum);
        }
    }
}
