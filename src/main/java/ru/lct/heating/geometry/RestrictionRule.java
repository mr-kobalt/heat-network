package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * Правило обработки пространственного ограничения (ТП 5.1, протокол).
 * Конфигурируемое — ADR-0009.
 */
@Data
public class RestrictionRule {

    private RestrictionMode mode = RestrictionMode.PROHIBITED;
    private double minDistanceM = 1.0;
    private Double angleMinDeg;
    private Double kSpecial;
    private Double specialZoneBufferM;
    /**
     * ADR-0073 (ТП v2 §4, таблица 4): глубина до верха условного габарита
     * коммуникации, м. {@code null} — вертикальный учёт не задан.
     */
    private Double verticalTopDepthM;
    /** ADR-0073: высота условного габарита коммуникации, м. */
    private Double verticalHeightM;
    private List<DistanceBand> distanceBands = new ArrayList<>();

    /**
     * Минимальное горизонтальное расстояние для заданного Ду с учётом полос.
     */
    public double minDistanceForDn(int dn) {
        return distanceBands.stream()
                .filter(band -> dn <= band.getMaxDn())
                .mapToDouble(DistanceBand::getDistanceM)
                .min()
                .orElse(minDistanceM);
    }

    public boolean isSpecial() {
        return mode == RestrictionMode.SPECIAL;
    }

    /**
     * Радиус буфера спецзоны: явное значение или минимальное расстояние правила.
     */
    public double zoneBufferM() {
        return specialZoneBufferM != null ? specialZoneBufferM : minDistanceM;
    }
}
