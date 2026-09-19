package ru.lct.heating.cost;

import org.springframework.stereotype.Component;
import ru.lct.heating.hydraulics.DiameterCatalog;

/**
 * Единая модель стоимости (ТП 8, 9). Коэффициенты — в {@link CostProperties}.
 */
@Component
public class CostModel {

    private final DiameterCatalog diameters;
    private final CostProperties properties;

    public CostModel(DiameterCatalog diameters, CostProperties properties) {
        this.diameters = diameters;
        this.properties = properties;
    }

    public long segmentCost(double lengthM, int dn, double depthCoefficient, double specialCoefficient) {
        return segmentCost(lengthM, dn, depthCoefficient, specialCoefficient, 1.0);
    }

    /**
     * Стоимость участка с учётом нестандартных углов отвода (FR-57).
     */
    public long segmentCost(double lengthM, int dn, double depthCoefficient,
                            double specialCoefficient, double bendCoefficient) {
        double cost = lengthM * diameters.newCostPerM(dn)
                * depthCoefficient * specialCoefficient * bendCoefficient;
        return Math.round(cost);
    }

    public long chamberCost(int maxDn) {
        if (maxDn <= properties.getChamberSmallMaxDn()) {
            return properties.getChamberCostSmall();
        }
        if (maxDn <= properties.getChamberMediumMaxDn()) {
            return properties.getChamberCostMedium();
        }
        if (maxDn <= properties.getChamberLargeMaxDn()) {
            return properties.getChamberCostLarge();
        }
        return properties.getChamberCostExtraLarge();
    }

    public long unconnectedPenalty(double flowTph) {
        return properties.getUnconnectedBaseCost()
                + Math.round(properties.getUnconnectedCostPerTph() * flowTph);
    }

    public double score(long calculatedCost, double lengthM) {
        return properties.getScoreCostWeight() * (calculatedCost / properties.getScoreCostBase())
                + properties.getScoreLengthWeight() * (lengthM / properties.getScoreLengthBase());
    }

    /** Стоимость врезки в существующую камеру (за участок, FR-73). */
    public long existingChamberTieInCost() {
        return properties.getExistingChamberTieInCost();
    }
}
