package ru.lct.heating.cost;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Конфигурируемые коэффициенты стоимости и ранжирования (ТП 8, 9; ADR-0009).
 */
@Data
@ConfigurationProperties(prefix = "heating.cost")
public class CostProperties {

    /** FR-73: стоимость одной врезки в существующую камеру (за участок). */
    private long existingChamberTieInCost = 5_000_000L;

    private int chamberSmallMaxDn = 200;
    private int chamberMediumMaxDn = 500;
    private int chamberLargeMaxDn = 1000;
    private long chamberCostSmall = 3_000_000L;
    private long chamberCostMedium = 5_000_000L;
    private long chamberCostLarge = 8_000_000L;
    private long chamberCostExtraLarge = 12_000_000L;

    private long unconnectedBaseCost = 100_000_000L;
    private double unconnectedCostPerTph = 500_000.0;

    private double scoreCostBase = 25_000_000.0;
    private double scoreLengthBase = 100.0;
    private double scoreCostWeight = 0.7;
    private double scoreLengthWeight = 0.3;
}
