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
    /**
     * Диагностика контракта {@code gridPath.incomingDir}: сколько раз в него
     * передан не вектор направления, а координата точки (ошибка вызова).
     * После исправления должно быть {@code 0}.
     */
    long turnSuspiciousIncoming;
    List<Pass> passes;

    @Value
    @Builder
    public static class Pass {
        int index;
        double score;
        int trees;
        long timeMs;
        /** R3: счётчики relink (кандидаты, T-точки, проверки, пересборки, принятые ходы). */
        long relinkCandidateNodes;
        long relinkCandidateEdges;
        long relinkTpoints;
        long relinkValidSegmentCalls;
        long relinkRebuildCalls;
        long relinkMovesAccepted;
        long relinkKSpecialCalls;
        long relinkLengthUpsizedEdges;
        /** R3: тайминги relink, мс. */
        long relinkMs;
        long relinkValidSegmentMs;
        long relinkRebuildMs;
        long relinkIndexBuildMs;
        long relinkKSpecialMs;
        long relinkLengthMs;
    }
}
