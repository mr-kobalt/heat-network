package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;

/**
 * Постобработка геометрии трассы (FR-28): удаление повторных и коллинеарных
 * вершин, чтобы не было необоснованных изломов, зигзагов и ступеней.
 */
@Component
public class LineStringSimplifier {

    private static final double COLLINEAR_TOLERANCE = 1e-3;

    public List<Coordinate> simplify(List<Coordinate> coordinates) {
        List<Coordinate> unique = removeDuplicates(coordinates);
        if (unique.size() < 3) {
            return unique;
        }
        boolean changed = true;
        while (changed && unique.size() > 2) {
            changed = false;
            for (int i = 1; i < unique.size() - 1; i++) {
                if (isCollinear(unique.get(i - 1), unique.get(i), unique.get(i + 1))) {
                    unique.remove(i);
                    changed = true;
                    break;
                }
            }
        }
        return unique;
    }

    private List<Coordinate> removeDuplicates(List<Coordinate> coordinates) {
        List<Coordinate> unique = new ArrayList<>();
        for (Coordinate coordinate : coordinates) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals2D(coordinate)) {
                unique.add(coordinate);
            }
        }
        return unique;
    }

    private boolean isCollinear(Coordinate a, Coordinate b, Coordinate c) {
        double cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x);
        double dot = (b.x - a.x) * (c.x - b.x) + (b.y - a.y) * (c.y - b.y);
        double scale = Math.hypot(b.x - a.x, b.y - a.y) * Math.hypot(c.x - b.x, c.y - b.y);
        if (scale < 1e-12) {
            return true;
        }
        return Math.abs(cross) / scale < COLLINEAR_TOLERANCE && dot > 0;
    }
}
