package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.locationtech.jts.index.strtree.STRtree;

/**
 * Реестр спецзон (ADR-0018). По трассе определяет непрерывные специальные
 * участки и их коэффициент (максимум при наложении, ADR-0011), а также проверяет
 * минимальный угол пересечения от оси препятствия (FR-53).
 */
public class SpecialZoneIndex {

    private static final double EPS = 1e-6;
    private static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory();

    private final List<SpecialZone> zones;
    private final STRtree tree = new STRtree();
    /** Подмножество зон с ограничением угла — отдельный индекс для горячего пути. */
    private final List<SpecialZone> angleZones = new ArrayList<>();
    private final STRtree angleTree = new STRtree();

    public SpecialZoneIndex(List<SpecialZone> zones) {
        this.zones = zones;
        for (int i = 0; i < zones.size(); i++) {
            SpecialZone zone = zones.get(i);
            tree.insert(zone.getZone().getEnvelopeInternal(), i);
            if (zone.getAngleMinDeg() != null && zone.getAxis() != null) {
                angleTree.insert(zone.getZone().getEnvelopeInternal(), angleZones.size());
                angleZones.add(zone);
            }
        }
        tree.build();
        angleTree.build();
    }

    public int size() {
        return zones.size();
    }

    /** Есть ли зоны с ограничением минимального угла пересечения (E41). */
    public boolean hasAngleZones() {
        return !angleZones.isEmpty();
    }

    /** Спецзоны для визуализации этапа «спецпроходы» (ADR-0036). */
    public List<SpecialZone> zones() {
        return zones;
    }

    /**
     * E25-04: максимальный {@code Kспец} зон, накрывающих трассу (иначе 1.0).
     * Дешёвая предпроверка по индексу: если ни одна зона не попадает в габарит
     * трассы, участок не специальный.
     */
    public double maxKSpecial(LineString route, List<String> warnings) {
        if (zones.isEmpty() || route == null || route.getNumPoints() < 2) {
            return 1.0;
        }
        if (tree.query(route.getEnvelopeInternal()).isEmpty()) {
            return 1.0;
        }
        double k = 1.0;
        for (SpecialSpan span : spans(route, warnings)) {
            k = Math.max(k, span.getKSpecial());
        }
        return k;
    }

    /** Координатная перегрузка для вызовов без готовой геометрии. */
    public double maxKSpecial(List<Coordinate> coordinates, List<String> warnings) {
        if (zones.isEmpty() || coordinates == null || coordinates.size() < 2) {
            return 1.0;
        }
        LineString route = GEOMETRY_FACTORY.createLineString(
                coordinates.toArray(new Coordinate[0]));
        return maxKSpecial(route, warnings);
    }

    /**
     * Быстрая верхняя оценка {@code Kспец} для relink (R5a): без {@code spans()}
     * и проверок угла, только близкие зоны из индекса. Семантика — «трасса
     * пересекает зону» (консервативно, без разового учёта участков).
     */
    public double maxKSpecialNearby(List<Coordinate> coordinates) {
        if (zones.isEmpty() || coordinates == null || coordinates.size() < 2) {
            return 1.0;
        }
        LineString route = GEOMETRY_FACTORY.createLineString(
                coordinates.toArray(new Coordinate[0]));
        @SuppressWarnings("unchecked")
        List<Integer> nearby = tree.query(route.getEnvelopeInternal());
        double k = 1.0;
        for (Integer index : nearby) {
            SpecialZone zone = zones.get(index);
            Geometry zoneGeometry = zone.getZone();
            if (zoneGeometry == null || zoneGeometry.isEmpty()) {
                continue;
            }
            if (route.intersects(zoneGeometry)) {
                k = Math.max(k, zone.getKSpecial());
            }
        }
        return k;
    }

    /**
     * Непрерывные специальные участки вдоль трассы (в метрах от начала).
     *
     * <p>E32: специальный проход — фактическое <b>пересечение</b> трассы с осью
     * (линейное ограничение) или границей (полигональное) препятствия,
     * продлённое на радиус зоны {@code bufferM} в обе стороны. Движение вдоль
     * препятствия (без пересечения) специальным проходом не считается.</p>
     */
    public List<SpecialSpan> spans(LineString route, List<String> warnings) {
        List<SpecialSpan> raw = new ArrayList<>();
        if (zones.isEmpty()) {
            return raw;
        }
        double length = route.getLength();
        LengthIndexedLine indexed = new LengthIndexedLine(route);
        for (SpecialZone zone : zones) {
            Geometry axis = zone.getAxis();
            if (axis == null || axis.isEmpty()) {
                continue;
            }
            @SuppressWarnings("unchecked")
            boolean nearby = !tree.query(route.getEnvelopeInternal()).isEmpty();
            if (!nearby) {
                continue;
            }
            Geometry intersection = route.intersection(axis);
            if (intersection.isEmpty()) {
                // Параллельное движение вдоль препятствия — не спецпроход.
                continue;
            }
            for (Coordinate crossing : crossingPoints(intersection)) {
                LineString local = localSegment(route, crossing);
                if (local != null) {
                    checkAngle(local, crossing, zone, warnings);
                }
            }
            // E38: спецпроход — участок трассы внутри спецзоны (при фактическом
            // пересечении); при заблокированной зоне это проход через «ворота»,
            // т.е. прямой отрезок.
            double[] inZone = interval(indexed, route.intersection(zone.getZone()), length);
            if (inZone == null) {
                for (Coordinate crossing : crossingPoints(intersection)) {
                    double distance = indexed.project(crossing);
                    inZone = new double[]{Math.max(0.0, distance - zone.getBufferM()),
                            Math.min(length, distance + zone.getBufferM())};
                    break;
                }
            }
            if (inZone != null) {
                raw.add(SpecialSpan.builder()
                        .startDistanceM(inZone[0])
                        .endDistanceM(inZone[1])
                        .kSpecial(zone.getKSpecial())
                        .build());
            }
        }
        return merge(raw);
    }

    /**
     * E41: допустим ли отрезок по минимальному углу пересечения — все спецзоны
     * с {@code angleMinDeg}, пересекаемые отрезком, должны иметь угол не меньше
     * заданного. Используется как жёсткое ограничение в поиске пути.
     */
    public boolean angleOk(LineString segment) {
        if (angleZones.isEmpty() || segment == null || segment.getNumPoints() < 2) {
            return true;
        }
        @SuppressWarnings("unchecked")
        List<Integer> candidates = angleTree.query(segment.getEnvelopeInternal());
        for (Integer index : candidates) {
            SpecialZone zone = angleZones.get(index);
            Geometry intersection = segment.intersection(zone.getAxis());
            if (intersection.isEmpty()) {
                continue;
            }
            for (Coordinate crossing : crossingPoints(intersection)) {
                double angle = crossingAngle(segment, crossing, zone.getAxis());
                if (angle + EPS < zone.getAngleMinDeg()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Интервал трассы (по расстоянию) внутри геометрии; {@code null}, если пусто. */
    private double[] interval(LengthIndexedLine indexed, Geometry geometry, double length) {
        if (geometry == null || geometry.isEmpty()) {
            return null;
        }
        Coordinate[] coordinates = geometry.getCoordinates();
        if (coordinates.length == 0) {
            return null;
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : coordinates) {
            double projection = indexed.project(coordinate);
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }
        min = Math.max(0.0, min);
        max = Math.min(length, max);
        return max <= min + EPS ? null : new double[]{min, max};
    }

    /** Точечные пересечения (фактические пересечения/касания), без перекрытий. */
    private List<Coordinate> crossingPoints(Geometry intersection) {
        List<Coordinate> points = new ArrayList<>();
        for (int i = 0; i < intersection.getNumGeometries(); i++) {
            Geometry component = intersection.getGeometryN(i);
            if (component.getDimension() == 0) {
                for (Coordinate coordinate : component.getCoordinates()) {
                    points.add(coordinate);
                }
            }
        }
        return points;
    }

    /** Сегмент трассы, содержащий точку пересечения (для расчёта угла). */
    private LineString localSegment(LineString route, Coordinate crossing) {
        Coordinate[] coordinates = route.getCoordinates();
        Point point = route.getFactory().createPoint(crossing);
        for (int i = 0; i < coordinates.length - 1; i++) {
            LineString segment = route.getFactory()
                    .createLineString(new Coordinate[]{coordinates[i], coordinates[i + 1]});
            if (segment.distance(point) < 1e-6) {
                return segment;
            }
        }
        return null;
    }

    private void checkAngle(LineString segment, Coordinate crossing, SpecialZone zone,
                            List<String> warnings) {
        if (zone.getAngleMinDeg() == null || zone.getAxis() == null) {
            return;
        }
        double angle = crossingAngle(segment, crossing, zone.getAxis());
        if (angle + EPS < zone.getAngleMinDeg()) {
            warnings.add("CROSSING_ANGLE_TOO_SHALLOW: " + zone.getRestrictionType()
                    + " угол " + Math.round(angle) + "° < " + Math.round(zone.getAngleMinDeg()) + "°");
        }
    }

    private double crossingAngle(LineString segment, Coordinate crossing, Geometry axis) {
        Coordinate a = segment.getCoordinateN(0);
        Coordinate b = segment.getCoordinateN(1);
        double ux = b.x - a.x;
        double uy = b.y - a.y;
        Coordinate[] axisCoordinates = axis.getCoordinates();
        double bestDistance = Double.POSITIVE_INFINITY;
        double bestAngle = 90.0;
        for (int i = 0; i < axisCoordinates.length - 1; i++) {
            Coordinate p = axisCoordinates[i];
            Coordinate q = axisCoordinates[i + 1];
            double distance = distanceToSegment(crossing, p, q);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestAngle = acuteAngle(ux, uy, q.x - p.x, q.y - p.y);
            }
        }
        return bestAngle;
    }

    private double acuteAngle(double ux, double uy, double vx, double vy) {
        double dot = Math.abs(ux * vx + uy * vy);
        double cross = Math.abs(ux * vy - uy * vx);
        if (dot == 0.0 && cross == 0.0) {
            return 0.0;
        }
        return Math.toDegrees(Math.atan2(cross, dot));
    }

    private double distanceToSegment(Coordinate point, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < EPS) {
            return point.distance(a);
        }
        double t = ((point.x - a.x) * dx + (point.y - a.y) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return point.distance(new Coordinate(a.x + t * dx, a.y + t * dy));
    }

    private List<SpecialSpan> merge(List<SpecialSpan> spans) {
        spans.sort(Comparator.comparingDouble(SpecialSpan::getStartDistanceM));
        List<SpecialSpan> merged = new ArrayList<>();
        for (SpecialSpan span : spans) {
            if (merged.isEmpty()) {
                merged.add(span);
                continue;
            }
            SpecialSpan last = merged.get(merged.size() - 1);
            if (span.getStartDistanceM() <= last.getEndDistanceM() + EPS) {
                merged.set(merged.size() - 1, SpecialSpan.builder()
                        .startDistanceM(last.getStartDistanceM())
                        .endDistanceM(Math.max(last.getEndDistanceM(), span.getEndDistanceM()))
                        .kSpecial(Math.max(last.getKSpecial(), span.getKSpecial()))
                        .build());
            } else {
                merged.add(span);
            }
        }
        return merged;
    }
}
