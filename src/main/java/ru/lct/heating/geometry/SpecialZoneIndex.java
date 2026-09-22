package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
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

    private final List<SpecialZone> zones;
    private final STRtree tree = new STRtree();

    public SpecialZoneIndex(List<SpecialZone> zones) {
        this.zones = zones;
        for (int i = 0; i < zones.size(); i++) {
            tree.insert(zones.get(i).getZone().getEnvelopeInternal(), i);
        }
        tree.build();
    }

    public int size() {
        return zones.size();
    }

    /** Спецзоны для визуализации этапа «спецпроходы» (ADR-0036). */
    public List<SpecialZone> zones() {
        return zones;
    }

    /**
     * Непрерывные специальные участки вдоль трассы (в метрах от начала).
     */
    public List<SpecialSpan> spans(LineString route, List<String> warnings) {
        List<SpecialSpan> raw = new ArrayList<>();
        Coordinate[] coordinates = route.getCoordinates();
        double offset = 0.0;
        for (int i = 0; i < coordinates.length - 1; i++) {
            LineString segment = route.getFactory()
                    .createLineString(new Coordinate[]{coordinates[i], coordinates[i + 1]});
            double segmentLength = segment.getLength();
            if (segmentLength > EPS) {
                collectSegmentSpans(segment, offset, raw, warnings);
            }
            offset += segmentLength;
        }
        return merge(raw);
    }

    private void collectSegmentSpans(LineString segment, double offset,
                                     List<SpecialSpan> raw, List<String> warnings) {
        @SuppressWarnings("unchecked")
        List<Integer> candidates = tree.query(segment.getEnvelopeInternal());
        LengthIndexedLine indexed = new LengthIndexedLine(segment);
        double segmentLength = segment.getLength();
        for (Integer candidate : candidates) {
            SpecialZone zone = zones.get(candidate);
            Geometry intersection = zone.getZone().intersection(segment);
            if (intersection.isEmpty()) {
                continue;
            }
            double[] interval = interval(indexed, intersection, segmentLength);
            if (interval == null) {
                continue;
            }
            raw.add(SpecialSpan.builder()
                    .startDistanceM(offset + interval[0])
                    .endDistanceM(offset + interval[1])
                    .kSpecial(zone.getKSpecial())
                    .build());
            checkAngle(segment, intersection, zone, warnings);
        }
    }

    private double[] interval(LengthIndexedLine indexed, Geometry intersection, double segmentLength) {
        Coordinate[] coordinates = intersection.getCoordinates();
        if (coordinates.length == 0) {
            return new double[]{0.0, segmentLength};
        }
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (Coordinate coordinate : coordinates) {
            double projection = indexed.project(coordinate);
            min = Math.min(min, projection);
            max = Math.max(max, projection);
        }
        min = Math.max(0.0, min);
        max = Math.min(segmentLength, max);
        return max <= min + EPS ? null : new double[]{min, max};
    }

    private void checkAngle(LineString segment, Geometry intersection, SpecialZone zone,
                            List<String> warnings) {
        if (zone.getAngleMinDeg() == null || zone.getAxis() == null
                || intersection.getCoordinates().length == 0) {
            return;
        }
        Coordinate crossing = intersection.getCoordinates()[0];
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
