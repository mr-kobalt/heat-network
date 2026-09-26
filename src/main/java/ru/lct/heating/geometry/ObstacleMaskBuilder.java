package ru.lct.heating.geometry;

import java.util.List;
import org.locationtech.jts.algorithm.locate.IndexedPointInAreaLocator;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Location;
import org.locationtech.jts.geom.Polygon;
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
        return build(index, bounds, requestedCellM, coarseFactor, maxBytes, warnings,
                SquareGridShape.INSTANCE);
    }

    public ObstacleMask build(ObstacleIndex index, Envelope bounds, double requestedCellM,
                              int coarseFactor, long maxBytes, List<String> warnings,
                              GridShape shape) {
        if (index == null || index.size() == 0) {
            return null;
        }
        return rasterize(index, bounds, requestedCellM, Math.max(1, coarseFactor), maxBytes, true,
                "MASK_COARSENED", warnings, shape);
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
        return buildPassability(index, bounds, requestedCellM, maxBytes, warnings,
                SquareGridShape.INSTANCE);
    }

    public ObstacleMask buildPassability(ObstacleIndex index, Envelope bounds, double requestedCellM,
                                         long maxBytes, List<String> warnings, GridShape shape) {
        if (bounds == null || bounds.isNull()) {
            return null;
        }
        ObstacleIndex effective = index != null ? index : new ObstacleIndex(java.util.List.of());
        return rasterize(effective, bounds, requestedCellM, 1, maxBytes, false, "GRID_COARSENED",
                warnings, shape);
    }

    private ObstacleMask rasterize(ObstacleIndex index, Envelope bounds, double requestedCellM,
                                   int factor, long maxBytes, boolean dilate, String warnCode,
                                   List<String> warnings, GridShape shape) {
        if (bounds == null || bounds.isNull()) {
            return null;
        }
        GridShape grid = shape == null ? SquareGridShape.INSTANCE : shape;
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
            width = grid.columns(bounds.getWidth(), cell);
            height = grid.rows(bounds.getHeight(), cell);
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
        double dilation = dilate ? grid.conservativeDilation(cell) : 0.0;
        Coordinate probe = new Coordinate();
        long blocked = 0L;

        for (Geometry obstacle : index.obstaclesIn(bounds)) {
            if (obstacle == null || obstacle.isEmpty()) {
                continue;
            }
            Geometry expanded = dilation > 0.0 ? obstacle.buffer(dilation) : obstacle;
            boolean areal = expanded.getDimension() >= 2;
            Envelope envelope = expanded.getEnvelopeInternal();
            int col0 = clamp((int) Math.floor((envelope.getMinX() - originX) / cell) - 1, width);
            int col1 = clamp((int) Math.floor((envelope.getMaxX() - originX) / cell) + 1, width);
            double rowSpacing = grid.rowSpacing(cell);
            int row0 = clamp((int) Math.floor((envelope.getMinY() - originY) / rowSpacing) - 1, height);
            int row1 = clamp((int) Math.floor((envelope.getMaxY() - originY) / rowSpacing) + 1, height);
            if (areal) {
                blocked += rasterizeAreal(expanded, fine, coarse, single, factor,
                        coarseWidth, width, originX, originY, cell, grid, col0, col1, row0, row1);
            } else {
                for (int row = row0; row <= row1; row++) {
                    probe.y = grid.centerY(0, row, originY, cell);
                    for (int col = col0; col <= col1; col++) {
                        probe.x = grid.centerX(col, row, originX, cell);
                        if (!envelope.intersects(probe)) {
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
        }
        long buildMs = (System.nanoTime() - start) / 1_000_000L;
        return new ObstacleMask(originX, originY, cell, width, height, fine, factor, coarseWidth,
                coarseHeight, coarse, blocked, buildMs, grid);
    }

    /**
     * Сканлайн-растеризация полигонального препятствия: по каждой строке
     * считаются пересечения колец, затем заполняются спаны. Возвращает
     * {@code [новые blocked, всего клеток внутри]}. Эквивалентно
     * {@code IndexedPointInAreaLocator != EXTERIOR} (граница — «внутри»).
     */
    private long rasterizeAreal(Geometry areal, long[] fine, long[] coarse, boolean single,
                                  int factor, int coarseWidth, int width,
                                  double originX, double originY, double cell, GridShape grid,
                                  int col0, int col1, int row0, int row1) {
        List<Comp> comps = new java.util.ArrayList<>();
        for (int i = 0; i < areal.getNumGeometries(); i++) {
            Geometry component = areal.getGeometryN(i);
            if (!(component instanceof Polygon)) {
                continue;
            }
            Polygon polygon = (Polygon) component;
            List<double[]> edges = new java.util.ArrayList<>();
            collectEdges(polygon.getExteriorRing(), edges);
            for (int h = 0; h < polygon.getNumInteriorRing(); h++) {
                collectEdges(polygon.getInteriorRingN(h), edges);
            }
            if (edges.isEmpty()) {
                continue;
            }
            double ymin = Double.POSITIVE_INFINITY;
            double ymax = Double.NEGATIVE_INFINITY;
            for (double[] edge : edges) {
                ymin = Math.min(ymin, Math.min(edge[1], edge[3]));
                ymax = Math.max(ymax, Math.max(edge[1], edge[3]));
            }
            comps.add(new Comp(edges.toArray(new double[0][]), ymin, ymax));
        }
        if (comps.isEmpty()) {
            return rasterizeArealFallback(areal, fine, coarse, single, factor, coarseWidth,
                    width, originX, originY, cell, grid, col0, col1, row0, row1);
        }
        long newBlocked = 0L;
        List<Double> crossings = new java.util.ArrayList<>();
        for (int row = row0; row <= row1; row++) {
            double y = grid.centerY(0, row, originY, cell);
            crossings.clear();
            for (Comp comp : comps) {
                if (y < comp.ymin || y > comp.ymax) {
                    continue;
                }
                for (double[] edge : comp.edges) {
                    double y1 = edge[1];
                    double y2 = edge[3];
                    if ((y1 > y) != (y2 > y)) {
                        crossings.add(edge[0] + (y - y1) * (edge[2] - edge[0]) / (y2 - y1));
                    }
                }
            }
            if (crossings.isEmpty()) {
                continue;
            }
            java.util.Collections.sort(crossings);
            for (int i = 0; i + 1 < crossings.size(); i += 2) {
                double xa = crossings.get(i);
                double xb = crossings.get(i + 1);
                for (int col = col0; col <= col1; col++) {
                    double cx = grid.centerX(col, row, originX, cell);
                    if (cx < xa || cx > xb) {
                        continue;
                    }
                    long cellIndex = (long) row * width + col;
                    int word = (int) (cellIndex >>> 6);
                    long bit = 1L << (cellIndex & 63);
                    if ((fine[word] & bit) == 0) {
                        fine[word] |= bit;
                        newBlocked++;
                    }
                    if (!single) {
                        long coarseIndex = (long) (row / factor) * coarseWidth + (col / factor);
                        coarse[(int) (coarseIndex >>> 6)] |= 1L << (coarseIndex & 63);
                    }
                }
            }
        }
        return newBlocked;
    }

    private long rasterizeArealFallback(Geometry areal, long[] fine, long[] coarse, boolean single,
                                        int factor, int coarseWidth, int width,
                                        double originX, double originY, double cell,
                                        GridShape grid, int col0, int col1, int row0, int row1) {
        IndexedPointInAreaLocator locator = new IndexedPointInAreaLocator(areal);
        Coordinate probe = new Coordinate();
        long newBlocked = 0L;
        for (int row = row0; row <= row1; row++) {
            probe.y = grid.centerY(0, row, originY, cell);
            for (int col = col0; col <= col1; col++) {
                probe.x = grid.centerX(col, row, originX, cell);
                if (locator.locate(probe) == Location.EXTERIOR) {
                    continue;
                }
                long cellIndex = (long) row * width + col;
                int word = (int) (cellIndex >>> 6);
                long bit = 1L << (cellIndex & 63);
                if ((fine[word] & bit) == 0) {
                    fine[word] |= bit;
                    newBlocked++;
                }
                if (!single) {
                    long coarseIndex = (long) (row / factor) * coarseWidth + (col / factor);
                    coarse[(int) (coarseIndex >>> 6)] |= 1L << (coarseIndex & 63);
                }
            }
        }
        return newBlocked;
    }

    private void collectEdges(LineString ring, List<double[]> edges) {
        Coordinate[] coordinates = ring.getCoordinates();
        for (int i = 0; i + 1 < coordinates.length; i++) {
            edges.add(new double[]{coordinates[i].x, coordinates[i].y,
                    coordinates[i + 1].x, coordinates[i + 1].y});
        }
    }

    private static final class Comp {
        private final double[][] edges;
        private final double ymin;
        private final double ymax;

        private Comp(double[][] edges, double ymin, double ymax) {
            this.edges = edges;
            this.ymin = ymin;
            this.ymax = ymax;
        }
    }

    private int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}
