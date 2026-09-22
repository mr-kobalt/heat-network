package ru.lct.heating.geometry;

import java.util.List;

/**
 * Гексагональная сетка, pointy-top, odd-r смещение (ADR-0041). Шесть соседей
 * по рёбрам на равном расстоянии {@code cellM}; строки смещены на полшага.
 */
public final class HexGridShape implements GridShape {

    /** Покрывает диагональный шаг квадратной сетки; для центра гекса. */
    private static final double SQRT3 = Math.sqrt(3.0);
    /** Вертикальный шаг строк: 3/2 от circumradius = sqrt(3)/2 от шага. */
    private static final double VERTICAL = SQRT3 / 2.0;

    public static final HexGridShape INSTANCE = new HexGridShape();

    private HexGridShape() {
    }

    @Override
    public String id() {
        return "hex";
    }

    @Override
    public int columns(double widthM, double cellM) {
        return (int) Math.floor(widthM / cellM) + 2;
    }

    @Override
    public int rows(double heightM, double cellM) {
        return (int) Math.floor(heightM / (cellM * VERTICAL)) + 2;
    }

    @Override
    public double centerX(int col, int row, double originX, double cellM) {
        return originX + (col + 0.5 + (row & 1) * 0.5) * cellM;
    }

    @Override
    public double centerY(int col, int row, double originY, double cellM) {
        return originY + (row + 0.5) * cellM * VERTICAL;
    }

    @Override
    public int[] cell(double x, double y, double originX, double originY, double cellM,
                      int columns, int rows) {
        int baseRow = (int) Math.round((y - originY) / (cellM * VERTICAL) - 0.5);
        int baseCol = (int) Math.round((x - originX) / cellM - 0.5 - (baseRow & 1) * 0.5);
        int bestCol = clamp(baseCol, columns);
        int bestRow = clamp(baseRow, rows);
        double best = Double.POSITIVE_INFINITY;
        for (int dr = -1; dr <= 1; dr++) {
            int r = baseRow + dr;
            if (r < 0 || r >= rows) {
                continue;
            }
            for (int dc = -1; dc <= 1; dc++) {
                int c = baseCol + dc;
                if (c < 0 || c >= columns) {
                    continue;
                }
                double dx = x - centerX(c, r, originX, cellM);
                double dy = y - centerY(c, r, originY, cellM);
                double distance = dx * dx + dy * dy;
                if (distance < best) {
                    best = distance;
                    bestCol = c;
                    bestRow = r;
                }
            }
        }
        return new int[]{bestCol, bestRow};
    }

    @Override
    public List<int[]> neighbors(int col, int row) {
        if ((row & 1) == 0) {
            return List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{0, -1},
                    new int[]{-1, -1}, new int[]{0, 1}, new int[]{-1, 1});
        }
        return List.of(new int[]{1, 0}, new int[]{-1, 0}, new int[]{1, -1},
                new int[]{0, -1}, new int[]{1, 1}, new int[]{0, 1});
    }

    @Override
    public boolean diagonal(int dcol, int drow) {
        return false;
    }

    @Override
    public double stepLength(double cellM, int dcol, int drow) {
        return cellM;
    }

    @Override
    public double rowSpacing(double cellM) {
        return cellM * VERTICAL;
    }

    @Override
    public double conservativeDilation(double cellM) {
        return cellM / SQRT3;
    }

    private int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
