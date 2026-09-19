package ru.lct.heating.hydraulics;

/**
 * Округление расстояний вверх при незначительном отклонении (FR-58):
 * например, 4,999 → 5 м при допуске 0,01 м.
 */
public final class Rounding {

    private Rounding() {
    }

    public static double roundUp(double value, double toleranceM) {
        if (toleranceM <= 0.0 || !Double.isFinite(value)) {
            return value;
        }
        double ceil = Math.ceil(value - 1e-9);
        return ceil - value <= toleranceM ? ceil : value;
    }
}
