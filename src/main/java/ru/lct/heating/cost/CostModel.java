package ru.lct.heating.cost;

import org.springframework.stereotype.Component;
import ru.lct.heating.hydraulics.DiameterCatalog;

/**
 * Единая модель стоимости (ТП 8, 9).
 */
@Component
public class CostModel {

    public static final long TIE_IN_COST = 5_000_000L;
    public static final double SCORE_COST_BASE = 25_000_000.0;
    public static final double SCORE_LENGTH_BASE = 100.0;
    public static final double SCORE_COST_WEIGHT = 0.7;
    public static final double SCORE_LENGTH_WEIGHT = 0.3;

    private final DiameterCatalog diameters;

    public CostModel(DiameterCatalog diameters) {
        this.diameters = diameters;
    }

    public long segmentCost(double lengthM, int dn, double depthCoefficient, double specialCoefficient) {
        double cost = lengthM * diameters.newCostPerM(dn) * depthCoefficient * specialCoefficient;
        return Math.round(cost);
    }

    public long reconstructionCost(double lengthM, int dn) {
        return Math.round(lengthM * diameters.reconstructionCostPerM(dn));
    }

    public long chamberCost(int maxDn) {
        if (maxDn <= 200) {
            return 3_000_000L;
        }
        if (maxDn <= 500) {
            return 5_000_000L;
        }
        if (maxDn <= 1000) {
            return 8_000_000L;
        }
        return 12_000_000L;
    }

    public long unconnectedPenalty(double flowTph) {
        return 100_000_000L + Math.round(500_000.0 * flowTph);
    }

    public double score(long calculatedCost, double lengthM) {
        return SCORE_COST_WEIGHT * (calculatedCost / SCORE_COST_BASE)
                + SCORE_LENGTH_WEIGHT * (lengthM / SCORE_LENGTH_BASE);
    }
}
