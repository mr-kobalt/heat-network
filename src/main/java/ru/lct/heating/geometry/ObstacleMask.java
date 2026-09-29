package ru.lct.heating.geometry;

import java.util.Collection;
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
    private final GridShape shape;

    ObstacleMask(double originX, double originY, double cell, int width, int height, long[] fine,
                 int coarseFactor, int coarseWidth, int coarseHeight, long[] coarse,
                 long blockedCells, long buildMs) {
        this(originX, originY, cell, width, height, fine, coarseFactor, coarseWidth, coarseHeight,
                coarse, blockedCells, buildMs, SquareGridShape.INSTANCE);
    }

    ObstacleMask(double originX, double originY, double cell, int width, int height, long[] fine,
                 int coarseFactor, int coarseWidth, int coarseHeight, long[] coarse,
                 long blockedCells, long buildMs, GridShape shape) {
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
        this.shape = shape == null ? SquareGridShape.INSTANCE : shape;
    }

    /**
     * Возвращает {@code true}, если маска не гарантирует, что отрезок свободен
     * (нужна точная проверка). {@code false} — отрезок гарантированно свободен.
     */
    public boolean anyBlockedAlong(Coordinate a, Coordinate b) {
        if (a == null || b == null) {
            return true;
        }
        return anyBlockedAlong(a.x, a.y, b.x, b.y);
    }

    /**
     * То же без аллокации {@link Coordinate}: вызывается на каждом ребре поиска
     * пути как дешёвый префильтр перед точной JTS-проверкой.
     */
    public boolean anyBlockedAlong(double ax, double ay, double bx, double by) {
        if (coarseFactor > 1
                && !traverse(ax, ay, bx, by, cell * coarseFactor, coarseWidth, coarseHeight,
                        coarse)) {
            return false;
        }
        return traverse(ax, ay, bx, by, cell, width, height, fine);
    }

    private boolean traverse(Coordinate a, Coordinate b, double gridCell, int gridWidth,
                             int gridHeight, long[] bits) {
        return traverse(a.x, a.y, b.x, b.y, gridCell, gridWidth, gridHeight, bits);
    }

    private boolean traverse(double ax, double ay, double bx, double by, double gridCell,
                             int gridWidth, int gridHeight, long[] bits) {
        int col = clamp((int) Math.floor((ax - originX) / gridCell), gridWidth);
        int row = clamp((int) Math.floor((ay - originY) / gridCell), gridHeight);
        if (bit(bits, (long) row * gridWidth + col)) {
            return true;
        }
        int endCol = clamp((int) Math.floor((bx - originX) / gridCell), gridWidth);
        int endRow = clamp((int) Math.floor((by - originY) / gridCell), gridHeight);
        if (col == endCol && row == endRow) {
            return false;
        }
        double dx = bx - ax;
        double dy = by - ay;
        int stepX = dx >= 0 ? 1 : -1;
        int stepY = dy >= 0 ? 1 : -1;
        double tDeltaX = dx != 0 ? Math.abs(gridCell / dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = dy != 0 ? Math.abs(gridCell / dy) : Double.POSITIVE_INFINITY;
        double tMaxX = dx != 0
                ? (dx > 0 ? originX + (col + 1) * gridCell - ax : ax - (originX + col * gridCell))
                        / Math.abs(dx)
                : Double.POSITIVE_INFINITY;
        double tMaxY = dy != 0
                ? (dy > 0 ? originY + (row + 1) * gridCell - ay : ay - (originY + row * gridCell))
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

    /** Индекс клетки, содержащей точку. */
    public int cellAt(double x, double y) {
        int[] cr = shape.cell(x, y, originX, originY, cell, width, height);
        return cr[1] * width + cr[0];
    }

    public double centerX(int col, int row) {
        return shape.centerX(col, row, originX, cell);
    }

    public double centerY(int col, int row) {
        return shape.centerY(col, row, originY, cell);
    }

    public double cellCenterX(int cellIndex) {
        return centerX(cellIndex % width, cellIndex / width);
    }

    public double cellCenterY(int cellIndex) {
        return centerY(cellIndex % width, cellIndex / width);
    }

    public int[][] neighbors(int col, int row) {
        return shape.neighbors(col, row);
    }

    public boolean diagonalStep(int dcol, int drow) {
        return shape.diagonal(dcol, drow);
    }

    public double stepLength(int dcol, int drow) {
        return shape.stepLength(cell, dcol, drow);
    }

    public double rowSpacing() {
        return shape.rowSpacing(cell);
    }

    public GridShape shape() {
        return shape;
    }

    public String shapeId() {
        return shape.id();
    }

    public long blockedCells() {
        return blockedCells;
    }

    public long buildMs() {
        return buildMs;
    }

    /**
     * Копия маски с принудительно снятой блокировкой у указанных клеток
     * (ADR-0037: клетки выхода ОКС открываются как проходимые для финального
     * вывода). Используется только одноуровневой маской проходимости.
     */
    public ObstacleMask withClearedCells(Collection<Integer> cells) {
        long[] freed = fine.clone();
        long cleared = 0L;
        for (int index : cells) {
            if (index < 0 || index >= (long) width * height) {
                continue;
            }
            int word = index >>> 6;
            long mask = 1L << (index & 63);
            if ((freed[word] & mask) != 0L) {
                freed[word] &= ~mask;
                cleared++;
            }
        }
        long[] freedCoarse = coarse == fine ? freed : coarse;
        return new ObstacleMask(originX, originY, cell, width, height, freed, coarseFactor,
                coarseWidth, coarseHeight, freedCoarse, blockedCells - cleared, buildMs, shape);
    }

}
