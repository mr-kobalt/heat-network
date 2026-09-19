package ru.lct.heating.routing;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

/**
 * Ребро (участок) плана новой сети: геометрия в EPSG:32637, расход поддерева
 * и подобранный Ду (ADR-0019).
 */
@Value
@Builder
public class ForestEdge {
    String id;
    String fromNodeId;
    String toNodeId;
    List<Coordinate> coordinates;
    double flowTph;
    int diameterMm;

    public double lengthM() {
        double total = 0.0;
        for (int i = 1; i < coordinates.size(); i++) {
            total += coordinates.get(i - 1).distance(coordinates.get(i));
        }
        return total;
    }
}
