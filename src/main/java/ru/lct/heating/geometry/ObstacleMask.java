package ru.lct.heating.geometry;

import org.locationtech.jts.geom.Coordinate;

/**
 * Консервативная растровая маска запретных зон (ADR-0033). Клетка помечается
 * заблокированной, если её центр попадает в расширенный на полудиагональ
 * препятствие-буфер, поэтому «клетка свободна» достоверно означает отсутствие
 * препятствия. Используется для быстрого отсева рёбер графа леса без точных
 * JTS-проверок; при срабатывании маски вызывающий код делает точную проверку.
 */
public final class ObstacleMask {

    private final double originX;
    private final double originY;
    private final double cell;
    private final int width;
    private final int height;
    private final long[] fine;
    private final int coarseFactor;
    private final int coarseWidth;
    private final int coarseHeight;
    private final long[] coarse;
    private final long blockedCells;
    private final long buildMs;

    ObstacleMask(double originX, double originY, double cell, int width, int height, long[] fine,
                 int coarseFactor, int coarseWidth, int coarseHeight, long[] coarse,
                 long blockedCells, long buildMs) {
        this.originX = originX;
        this.originY = originY;
        this.cell = cell;
        this.width = width;
        this.height = height;
        this.fine = fine;
        this.coarseFactor = coarseFactor;
        this.coarseWidth = coarseWidth;
        this.coarseHeight = coarseHeight;
        this.coarse = coarse;
        this.blockedCells = blockedCells;
        this.buildMs = buildMs;
    }

    /**
     * Возвращает {@code true}, если маска не гарантирует, что отрезок свободен
     * (нужна точная проверка). {@code false} — отрезок гарантированно свободен.
     */
    public boolean anyBlockedAlong(Coordinate a, Coordinate b) {
        if (a == null || b == null) {
            return true;
        }
        if (coarseFactor > 1
                && !traverse(a, b, cell * coarseFactor, coarseWidth, coarseHeight, coarse)) {
            return false;
        }
        return traverse(a, b, cell, width, height, fine);
    }

    private boolean traverse(Coordinate a, Coordinate b, double gridCell, int gridWidth,
                             int gridHeight, long[] bits) {
        int col = clamp((int) Math.floor((a.x - originX) / gridCell), gridWidth);
        int row = clamp((int) Math.floor((a.y - originY) / gridCell), gridHeight);
        if (bit(bits, (long) row * gridWidth + col)) {
            return true;
        }
        int endCol = clamp((int) Math.floor((b.x - originX) / gridCell), gridWidth);
        int endRow = clamp((int) Math.floor((b.y - originY) / gridCell), gridHeight);
        if (col == endCol && row == endRow) {
            return false;
        }
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        int stepX = dx >= 0 ? 1 : -1;
        int stepY = dy >= 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? Math.abs(gridCell / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? Math.abs(gridCell / dy) : Double.POSITIVE_INFINITY;
        double tMaxX = dx != 0
                ? (dx > 0 ? originX + (col + 1) * gridCell - a.x : a.x - (originX + col * gridCell))
                        / Math.abs(dx)
                : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0
                ? (dy > 0 ? originY + (row + 1) * gridCell - a.y : a.y - (originY + row * gridCell))
                        / Math.abs(dy)
                : Double.POSITIVE_INFINITY;
        long guard = (long) gridWidth + gridHeight + 4;
        while ((col != endCol || row != endRow) && guard-- > 0) {
            if (tMaxX <= tMaxY) {
                tMaxX += tDeltaX;
                col += stepX;
            } else {
                tMaxY += tDeltaY;
                row += stepY;
            }
            if (col < 0 || col >= gridWidth || row < 0 || row >= gridHeight) {
                return true;
            }
            if (bit(bits, (long) row * gridWidth + col)) {
                return true;
            }
        }
        return guard <= 0;
    }

    private int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }

    private boolean bit(long[] bits, long index) {
        return (bits[(int) (index >>> 6)] & (1L << (index & 63))) != 0;
    }

    public double cellM() {
        return cell;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public double originX() {
        return originX;
    }

    public double originY() {
        return originY;
    }

    /** Заблокирована ли клетка (для поиска по сетке). */
    public boolean blockedCell(int col, int row) {
        if (col < 0 || col >= width || row < 0 || row >= height) {
            return true;
        }
        long index = (long) row * width + col;
        return (fine[(int) (index >>> 6)] & (1L << (index & 63))) != 0;
    }

    public int colOf(double x) {
        return clamp((int) Math.floor((x - originX) / cell), width);
    }

    public int rowOf(double y) {
        return clamp((int) Math.floor((y - originY) / cell), height);
    }

    public double centerX(int col) {
        return originX + (col + 0.5) * cell;
    }

    public double centerY(int row) {
        return originY + (row + 0.5) * cell;
    }

    public long blockedCells() {
        return blockedCells;
    }

    public long buildMs() {
        return buildMs;
    }

    public String describe() {
        return "cell=" + cell + " fine=" + width + "x" + height
                + " coarse=" + coarseWidth + "x" + coarseHeight + " factor=" + coarseFactor
                + " blocked=" + blockedCells + " build=" + buildMs + "ms";
    }
}
