package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Индекс запретных зон (буферы минимальных расстояний) для проверки
 * пересечения трассой (FR-50, FR-51). Правила — ADR-0009.
 *
 * <p>Допускается «выход» трассы из запретной зоны, внутри которой находится
 * сама точка подключения: содержащие её препятствия можно игнорировать на
 * инцидентных сегментах (иначе маршрут из точки, стоящей у стены, невозможен).</p>
 */
public class ObstacleIndex {

    /** Эрозия для проверки захода внутрь запрета (касание границы разрешено). */
    private static final double EROSION_M = 1e-3;

    private final List<PreparedGeometry> prohibited;
    private final STRtree tree = new STRtree();
    private final STRtree erodedTree = new STRtree();
    private final Map<PreparedGeometry, PreparedGeometry> erodedToOriginal = new IdentityHashMap<>();

    public ObstacleIndex(List<PreparedGeometry> prohibited) {
        this.prohibited = prohibited;
        for (PreparedGeometry prepared : prohibited) {
            tree.insert(prepared.getGeometry().getEnvelopeInternal(), prepared);
            Geometry erodedGeometry = prepared.getGeometry().buffer(-EROSION_M);
            if (erodedGeometry.isEmpty()) {
                continue;
            }
            PreparedGeometry eroded = PreparedGeometryFactory.prepare(erodedGeometry);
            erodedToOriginal.put(eroded, prepared);
            erodedTree.insert(erodedGeometry.getEnvelopeInternal(), eroded);
        }
        tree.build();
        erodedTree.build();
    }

    public boolean isBlocked(LineString segment) {
        return isBlocked(segment, Collections.emptySet());
    }

    public boolean isBlocked(LineString segment, Set<PreparedGeometry> ignored) {
        Envelope envelope = segment.getEnvelopeInternal();
        @SuppressWarnings("unchecked")
        List<PreparedGeometry> candidates = tree.query(envelope);
        for (PreparedGeometry candidate : candidates) {
            if (ignored.contains(candidate)) {
                continue;
            }
            if (candidate.intersects(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Проверка захода отрезка во внутреннюю часть запретной зоны: касание
     * границы (ровно минимальное расстояние) допустимо (ADR-0025/0033). Реализуется
     * через пересечение с эрозией препятствия — быстрый prepared-intersects без
     * DE-9IM {@code relate}.
     */
    public boolean isInteriorBlocked(LineString segment) {
        return isInteriorBlocked(segment, Collections.emptySet());
    }

    public boolean isInteriorBlocked(LineString segment, Set<PreparedGeometry> ignored) {
        Envelope envelope = segment.getEnvelopeInternal();
        @SuppressWarnings("unchecked")
        List<PreparedGeometry> candidates = erodedTree.query(envelope);
        for (PreparedGeometry candidate : candidates) {
            PreparedGeometry original = erodedToOriginal.get(candidate);
            if (original != null && ignored.contains(original)) {
                continue;
            }
            if (candidate.intersects(segment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Препятствия, содержащие точку (identity-set для последующего игнорирования).
     */
    public Set<PreparedGeometry> obstaclesContaining(Point point) {
        Set<PreparedGeometry> result = Collections.newSetFromMap(new IdentityHashMap<>());
        @SuppressWarnings("unchecked")
        List<PreparedGeometry> candidates = tree.query(point.getEnvelopeInternal());
        for (PreparedGeometry candidate : candidates) {
            if (candidate.contains(point)) {
                result.add(candidate);
            }
        }
        return result;
    }

    public boolean isPointBlocked(Point point) {
        return !obstaclesContaining(point).isEmpty();
    }

    public List<Geometry> obstaclesIn(Envelope envelope) {
        @SuppressWarnings("unchecked")
        List<PreparedGeometry> candidates = tree.query(envelope);
        List<Geometry> result = new ArrayList<>();
        for (PreparedGeometry candidate : candidates) {
            result.add(candidate.getGeometry());
        }
        return result;
    }

    public int size() {
        return prohibited.size();
    }

    /** Запретные геометрии (буферы минимальных расстояний) для визуализации. */
    public List<Geometry> geometries() {
        List<Geometry> result = new ArrayList<>(prohibited.size());
        for (PreparedGeometry prepared : prohibited) {
            result.add(prepared.getGeometry());
        }
        return result;
    }

    /**
     * Новый индекс с дополнительными геометриями-препятствиями (например,
     * уже проложенными участками при разрешении пересечений, FR-29).
     */
    public ObstacleIndex withAdditional(List<Geometry> extras) {
        List<PreparedGeometry> all = new ArrayList<>(prohibited);
        for (Geometry extra : extras) {
            if (extra != null && !extra.isEmpty()) {
                all.add(PreparedGeometryFactory.prepare(extra));
            }
        }
        return new ObstacleIndex(all);
    }
}
