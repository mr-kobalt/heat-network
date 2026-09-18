package ru.lct.heating.geometry;

import lombok.Data;

/**
 * Минимальное расстояние в зависимости от класса условного диаметра
 * (например, существующий ОКС: 5 м &lt;500, 7 м 500–800, 9 м ≥900, ТП 5.1).
 */
@Data
public class DistanceBand {
    private int maxDn = Integer.MAX_VALUE;
    private double distanceM;
}
