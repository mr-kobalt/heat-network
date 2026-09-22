package ru.lct.heating.geometry;

import java.util.List;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Location;
import org.springframework.stereotype.Component;

/**
 * Построение растровой маски запретов (ADR-0033/0034).
 *
 * <p>Консервативный режим: ячейка блокируется, если её центр попадает в
 * препятствие, расширенное на полудиагональ ячейки — «свободная» ячейка не
 * пересекает запретную зону. Режим проходимости для поиска по сетке: центр в
 * самом препятствии (без расширения), чтобы не перекрывать узкие коридоры;
 * корректность геометрии обеспечивает последующее точное уточнение.</p>
 */
@Component
public class ObstacleMaskBuilder {

    private static final double EPS = 1e-9;

    public ObstacleMask build(ObstacleIndex index, Envelope bounds, double requestedCellM,
                              int coarseFactor, long maxBytes, List<String> warnings) {
        if (index == null || index.size() == 0) {
            return null;
        }
        return rasterize(index, bounds, requestedCellM, Math.max(1, coarseFactor), maxBytes, true,
                "MASK_COARSENED", warnings);
    }

    /**
     * Одноуровневая маска проходимости для поиска по сетке (ADR-0037, вариант B):
     * без расширения на полудиагональ — клетка блокируется только если её центр
     * внутри самого буфера препятствия. Так точка выхода, лежащая на границе
     * буфера ОКС, попадает в свободную клетку. Гарантию «свободная клетка ⇒
     * отрезок не пересекает запрет» снимаем; корректность геометрии обеспечивает
     * точное уточнение `refine` ({@code ObstacleIndex.isInteriorBlocked}).
     */
    public ObstacleMask buildPassability(ObstacleIndex index, Envelope bounds, double requestedCellM,
                                         long maxBytes, List<String> warnings) {
        if (bounds == null || bounds.isNull()) {
            return null;
        }
        ObstacleIndex effective = index != null ? index : new ObstacleIndex(java.util.List.of());
        return rasterize(effective, bounds, requestedCellM, 1, maxBytes, false, "GRID_COARSENED",
                warnings);
    }

    private ObstacleMask rasterize(ObstacleIndex index, Envelope bounds, double requestedCellM,
                                   int factor, long maxBytes, boolean dilate, String warnCode,
                                   List<String> warnings) {
        if (bounds == null || bounds.isNull()) {
            return null;
        }
        double requested = requestedCellM > 0 ? requestedCellM : 1.0;
        double cell = requested;
        long budget = maxBytes > 0 ? maxBytes : Long.MAX_VALUE;
        long start = System.nanoTime();

        boolean single = factor == 1;
        int width;
        int height;
        int coarseWidth;
        int coarseHeight;
        while (true) {
            width = (int) Math.floor(bounds.getWidth() / cell) + 2;
            height = (int) Math.floor(bounds.getHeight() / cell) + 2;
            coarseWidth = single ? width : (width + factor - 1) / factor;
            coarseHeight = single ? height : (height + factor - 1) / factor;
            long fineBytes = ((long) width * height + 7) / 8;
            long coarseBytes = single ? fineBytes : ((long) coarseWidth * coarseHeight + 7) / 8;
            if (fineBytes + coarseBytes <= budget) {
                break;
            }
            if (cell > bounds.getWidth() && cell > bounds.getHeight()) {
                break;
            }
            cell *= 2.0;
        }
        if (cell > requested + EPS) {
            warnings.add(warnCode + ": cell=" + cell + " requested=" + requested);
        }

        long[] fine = new long[(int) (((long) width * height + 63) / 64)];
        long[] coarse = single ? fine : new long[(int) (((long) coarseWidth * coarseHeight + 63) / 64)];
        double originX = bounds.getMinX();
        double originY = bounds.getMinY();
        double dilation = dilate ? cell * Math.sqrt(2.0) / 2.0 : 0.0;
        Coordinate probe = new Coordinate();
        long blocked = 0L;

        for (Geometry obstacle : index.obstaclesIn(bounds)) {
            if (obstacle == null || obstacle.isEmpty()) {
                continue;
            }
            Geometry expanded = dilation > 0.0 ? obstacle.buffer(dilation) : obstacle;
            boolean areal = expanded.getDimension() >= 2;
            IndexedPointInAreaLocator locator = areal ? new IndexedPointInAreaLocator(expanded) : null;
            Envelope envelope = expanded.getEnvelopeInternal();
            int col0 = clamp((int) Math.floor((envelope.getMinX() - originX) / cell), width);
            int col1 = clamp((int) Math.floor((envelope.getMaxX() - originX) / cell), width);
            int row0 = clamp((int) Math.floor((envelope.getMinY() - originY) / cell), height);
            int row1 = clamp((int) Math.floor((envelope.getMaxY() - originY) / cell), height);
            for (int row = row0; row <= row1; row++) {
                probe.y = originY + (row + 0.5) * cell;
                for (int col = col0; col <= col1; col++) {
                    probe.x = originX + (col + 0.5) * cell;
                    boolean inside = areal
                            ? locator.locate(probe) != Location.EXTERIOR
                            : envelope.intersects(probe);
                    if (!inside) {
                        continue;
                    }
                    long cellIndex = (long) row * width + col;
                    if ((fine[(int) (cellIndex >>> 6)] & (1L << (cellIndex & 63))) == 0) {
                        fine[(int) (cellIndex >>> 6)] |= 1L << (cellIndex & 63);
                        blocked++;
                    }
                    if (!single) {
                        long coarseIndex = (long) (row / factor) * coarseWidth + (col / factor);
                        coarse[(int) (coarseIndex >>> 6)] |= 1L << (coarseIndex & 63);
                    }
                }
            }
        }
        long buildMs = (System.nanoTime() - start) / 1_000_000L;
        return new ObstacleMask(originX, originY, cell, width, height, fine, factor, coarseWidth,
                coarseHeight, coarse, blocked, buildMs);
    }

    private int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
