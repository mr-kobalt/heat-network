package ru.lct.heating.geometry;

import java.util.List;

/** Квадратная сетка (прежнее поведение, ADR-0033/0034). */
public final class SquareGridShape implements GridShape {

    public static final SquareGridShape INSTANCE = new SquareGridShape();
    private static final int[][] NEIGHBORS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private SquareGridShape() {
    }

    @Override
    public String id() {
        return "square";
    }

    @Override
    public int columns(double widthM, double cellM) {
        return (int) Math.floor(widthM / cellM) + 2;
    }

    @Override
    public int rows(double heightM, double cellM) {
        return (int) Math.floor(heightM / cellM) + 2;
    }

    @Override
    public double centerX(int col, int row, double originX, double cellM) {
        return originX + (col + 0.5) * cellM;
    }

    @Override
    public double centerY(int col, int row, double originY, double cellM) {
        return originY + (row + 0.5) * cellM;
    }

    @Override
    public int[] cell(double x, double y, double originX, double originY, double cellM,
                      int columns, int rows) {
        int col = clamp((int) Math.floor((x - originX) / cellM), columns);
        int row = clamp((int) Math.floor((y - originY) / cellM), rows);
        return new int[]{col, row};
    }

    @Override
    public List<int[]> neighbors(int col, int row) {
        return List.of(NEIGHBORS);
    }

    @Override
    public boolean diagonal(int dcol, int drow) {
        return dcol != 0 && drow != 0;
    }

    @Override
    public double stepLength(double cellM, int dcol, int drow) {
        return diagonal(dcol, drow) ? cellM * Math.sqrt(2.0) : cellM;
    }

    @Override
    public double rowSpacing(double cellM) {
        return cellM;
    }

    @Override
    public double conservativeDilation(double cellM) {
        return cellM * Math.sqrt(2.0) / 2.0;
    }

    private int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
