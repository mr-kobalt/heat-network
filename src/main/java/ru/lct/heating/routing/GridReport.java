package ru.lct.heating.routing;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Диагностика поиска по сетке (ADR-0033/0034): пишется в {@code grid.json}
 * рядом со сводкой, в GeoJSON не попадает.
 */
@Value
@Builder
public class GridReport {
    double cellM;
    int width;
    int height;
    long blockedCells;
    String storage;
    int sources;
    int terminals;
    int trees;
    int unconnected;
    long timeMs;
    List<Pass> passes;

    @Value
    @Builder
    public static class Pass {
        int index;
        double score;
        int trees;
        long timeMs;
    }
}
