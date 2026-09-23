package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;

/**
 * Разбиение трассы на обычные и специальные части по границам спецзон
 * (FR-52). Один непрерывный спецучасток на весь участок наложения (ADR-0011).
 */
@Component
public class SpecialSpanSplitter {

    private static final double EPS = 1e-6;

    public List<RouteChunk> split(LineString route, List<SpecialSpan> spans) {
        return split(route, spans, null, null, List.of(), null);
    }

    public List<RouteChunk> split(LineString route, List<SpecialSpan> spans, ObstacleIndex index,
                                  List<String> warnings) {
        return split(route, spans, index, warnings, List.of(), null);
    }

    /**
     * @param index    запретный индекс для перепроверки выпрямленного
     *                 спецучастка (E25-06); может быть {@code null}
     * @param warnings приёмник диагностики о невозможности выпрямления
     * @param avoid    рёбра нового леса: хорда не должна их пересекать (E27-06)
     * @param self     текущее ребро (исключается из {@code avoid})
     */
    public List<RouteChunk> split(LineString route, List<SpecialSpan> spans, ObstacleIndex index,
                                  List<String> warnings, List<LineString> avoid, LineString self) {
        TreeSet<Double> boundaries = new TreeSet<>();
        double length = route.getLength();
        boundaries.add(0.0);
        boundaries.add(length);
        for (SpecialSpan span : spans) {
            boundaries.add(Math.max(0.0, Math.min(length, span.getStartDistanceM())));
            boundaries.add(Math.max(0.0, Math.min(length, span.getEndDistanceM())));
        }

        List<Double> ordered = new ArrayList<>(boundaries);
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        List<RouteChunk> chunks = new ArrayList<>();
        for (int i = 0; i < ordered.size() - 1; i++) {
            double start = ordered.get(i);
            double end = ordered.get(i + 1);
            if (end - start <= EPS) {
                continue;
            }
            LineString geometry = (LineString) indexed.extractLine(start, end);
            SpecialSpan containing = spanAt(spans, (start + end) / 2.0);
            if (containing != null) {
                // ТП v2 §4: специальный проход — один прямой участок; внутри
                // разрешённого спецпересечения минимальное расстояние не проверяется.
                geometry = straight(geometry);
            }
            chunks.add(RouteChunk.builder()
                    .geometry(geometry)
                    .special(containing != null)
                    .kSpecial(containing != null ? containing.getKSpecial() : 1.0)
                    .build());
        }
        return chunks;
    }

    /**
     * Специальный проход — один прямой участок (ТП v2 §4).
     */
    private LineString straight(LineString line) {
        if (line.getNumPoints() <= 2) {
            return line;
        }
        return line.getFactory().createLineString(new Coordinate[]{
                line.getCoordinateN(0), line.getCoordinateN(line.getNumPoints() - 1)});
    }

    private SpecialSpan spanAt(List<SpecialSpan> spans, double distance) {
        for (SpecialSpan span : spans) {
            if (distance >= span.getStartDistanceM() - EPS
                    && distance <= span.getEndDistanceM() + EPS) {
                return span;
            }
        }
        return null;
    }
}
