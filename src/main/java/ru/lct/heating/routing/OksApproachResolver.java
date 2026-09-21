package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.config.OksApproachPolicy;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.EnvelopeCatalog;

/**
 * Финальный прямой вывод от точки подключения в `oks`-полигоне (ТП 2.2,
 * ADR-0023/0024). Поддерживается только один прямой сегмент от точки до точки
 * стыковки за внешней границей буфера отступа. Отступ к собственному полигону
 * на этом сегменте не действует, остальные ограничения продолжают действовать.
 *
 * <p>Точка стыковки выбирается как ближайший допустимый перпендикуляр к
 * внешней грани: кандидаты — рёбра полигона, проекция точки на которые лежит
 * строго внутри ребра; среди допустимых (стыковка вне буферов всех ограничений,
 * отрезок не задевает чужих буферов) выбирается ближайшее ребро. Если ни один
 * перпендикуляр недопустим (колодцы, П-образные здания) — кратчайший прямой
 * выход из кармана. Если и он невозможен — точка помечается заблокированной.</p>
 */
@Component
public class OksApproachResolver {

    private static final Logger log = LoggerFactory.getLogger(OksApproachResolver.class);

    private static final double EPS = 1e-6;
    private static final double VERTEX_TOLERANCE_M = 1e-3;
    private static final int MAX_CANDIDATES = 6;
    private static final int FALLBACK_DIRECTIONS = 72;
    private static final double FALLBACK_STEP_M = 0.25;
    private static final double FALLBACK_MAX_M = 2000.0;

    private final RestrictionRuleResolver rules;
    private final EnvelopeCatalog envelopes;
    private final DiameterCatalog diameters;
    private final AppProperties appProperties;

    public OksApproachResolver(RestrictionRuleResolver rules, EnvelopeCatalog envelopes,
                               DiameterCatalog diameters, AppProperties appProperties) {
        this.rules = rules;
        this.envelopes = envelopes;
        this.diameters = diameters;
        this.appProperties = appProperties;
    }

    /**
     * Канонические точки выхода из `oks`-полигонов для нового алгоритма
     * (ADR-0032): одна точка на подключение. Ду выбирается минимальным под
     * расход точки (FR-43), точка выхода — перпендикуляр к ближайшему ребру
     * выпуклой оболочки полигона, продолженный на расстояние
     * `minDistance(oks, Ду) + halfPairWidth(Ду)`.
     */
    public Map<String, ConnectionExit> resolveExits(NetworkDataset dataset) {
        long start = System.nanoTime();
        Map<String, ConnectionExit> result = new LinkedHashMap<>();
        if (dataset.getConnectionPoints() == null) {
            return result;
        }
        int total = dataset.getConnectionPoints().size();
        int skipped = 0;
        int blocked = 0;
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            Double flow = connectionPoint.getFlowTph();
            if (flow == null || flow <= 0.0 || connectionPoint.getGeometry() == null) {
                skipped++;
                continue;
            }
            ConnectionExit exit = exitFor(connectionPoint, dataset, flow);
            if (exit.isBlocked()) {
                blocked++;
            }
            result.put(connectionPoint.getId(), exit);
        }
        log.info("OKS exits: points={} resolved={} blocked={} skipped={} time={} ms",
                total, result.size(), blocked, skipped, (System.nanoTime() - start) / 1_000_000L);
        return result;
    }

    private ConnectionExit exitFor(OksConnectionPointObject connectionPoint,
                                   NetworkDataset dataset, double flow) {
        Point point = connectionPoint.getGeometry();
        Coordinate p = point.getCoordinate();
        Integer dn = designDiameter(flow);
        if (dn == null) {
            return ConnectionExit.builder()
                    .connectionPointId(connectionPoint.getId())
                    .target(p).tail(List.of()).blocked(true).designDiameterMm(0)
                    .build();
        }
        RestrictionObject own = owningRestriction(dataset, point);
        if (own == null) {
            return ConnectionExit.builder()
                    .connectionPointId(connectionPoint.getId())
                    .target(p).tail(List.of()).blocked(false).designDiameterMm(dn)
                    .build();
        }
        double halfWidth = envelopes.halfPairWidthM(dn);
        double offset = rules.resolve("oks").minDistanceForDn(dn) + halfWidth;
        List<Prohibited> prohibited = prohibited(dataset, dn, halfWidth);
        Candidate candidate = hullPerpendicular(p, own.getGeometry().convexHull(), offset);
        if (candidate == null || !feasible(candidate, p, own, prohibited, offset)) {
            return ConnectionExit.builder()
                    .connectionPointId(connectionPoint.getId())
                    .target(p).tail(List.of()).blocked(true).designDiameterMm(dn)
                    .build();
        }
        return ConnectionExit.builder()
                .connectionPointId(connectionPoint.getId())
                .target(candidate.outer)
                .tail(List.of(candidate.outer, p))
                .blocked(false)
                .designDiameterMm(dn)
                .build();
    }

    /** Минимальный Ду под расход точки; {@code null}, если расход выше номенклатуры. */
    private Integer designDiameter(double flow) {
        try {
            return diameters.select(flow).getDn();
        } catch (IllegalArgumentException overflow) {
            return null;
        }
    }

    /** Ближайший перпендикуляр к контуру выпуклой оболочки, вынесенный на offset. */
    private Candidate hullPerpendicular(Coordinate p, Geometry hull, double offset) {
        Candidate best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        for (LineString segment : boundarySegments(hull)) {
            Coordinate[] nearest = DistanceOp.nearestPoints(segment, point(p));
            if (nearest.length == 0) {
                continue;
            }
            Candidate candidate = candidateFrom(p, nearest[0], offset, 0.0);
            if (candidate != null && candidate.length < bestLength - EPS) {
                bestLength = candidate.length;
                best = candidate;
            }
        }
        return best;
    }

    public Map<String, Approach> resolve(NetworkDataset dataset, int designDiameterMm) {
        Map<String, List<Approach>> candidates = resolveCandidates(dataset, designDiameterMm);
        Map<String, Approach> result = new HashMap<>();
        for (Map.Entry<String, List<Approach>> entry : candidates.entrySet()) {
            List<Approach> list = entry.getValue();
            result.put(entry.getKey(), list.isEmpty() ? null : list.get(0));
        }
        return result;
    }

    /**
     * Упорядоченные по длине хвоста варианты вывода на точку: кратчайший
     * допустимый перпендикуляр первым; при отсутствии перпендикуляров —
     * кратчайший прямой выход из кармана; иначе единственный `blocked`.
     * Более длинный вариант используется как вынужденный откат, если
     * кратчайшая точка стыковки недостижима для сети.
     */
    public Map<String, List<Approach>> resolveCandidates(NetworkDataset dataset,
                                                         int designDiameterMm) {
        Map<String, List<Approach>> result = new HashMap<>();
        if (dataset.getConnectionPoints() == null) {
            return result;
        }
        double halfWidth = envelopes.halfPairWidthM(designDiameterMm);
        double offset = rules.resolve("oks").minDistanceForDn(designDiameterMm) + halfWidth;
        double clearance = Math.max(0.0, appProperties.getOksExitClearanceM());
        OksApproachPolicy policy = appProperties.getOksApproachPolicy() == null
                ? OksApproachPolicy.PERPENDICULAR_NEAREST : appProperties.getOksApproachPolicy();
        List<Prohibited> prohibited = prohibited(dataset, designDiameterMm, halfWidth);

        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            result.put(connectionPoint.getId(),
                    approachCandidates(connectionPoint, dataset, prohibited, offset, clearance, policy));
        }
        return result;
    }

    private List<Approach> approachCandidates(OksConnectionPointObject connectionPoint,
                                              NetworkDataset dataset, List<Prohibited> prohibited,
                                              double offset, double clearance,
                                              OksApproachPolicy policy) {
        Point point = connectionPoint.getGeometry();
        if (point == null) {
            return List.of(Approach.direct(new Coordinate(0, 0)));
        }
        Coordinate p = point.getCoordinate();
        RestrictionObject own = owningRestriction(dataset, point);
        if (own == null) {
            return List.of(Approach.direct(p));
        }
        List<Approach> result = new ArrayList<>();
        if (policy == OksApproachPolicy.NEAREST_BOUNDARY) {
            Candidate literal = nearestBoundaryCandidate(p, own.getGeometry(), offset, clearance);
            if (literal != null && feasible(literal, p, own, prohibited, offset)) {
                result.add(Approach.of(literal.outer, p));
            }
        }
        for (Candidate candidate : perpendicularCandidates(p, own, prohibited, offset, clearance)) {
            if (result.isEmpty() || !sameTarget(result.get(result.size() - 1), candidate)) {
                result.add(Approach.of(candidate.outer, p));
            }
        }
        if (result.isEmpty()) {
            Candidate fallback = fallbackCandidate(p, own, prohibited, offset, clearance);
            if (fallback != null) {
                result.add(Approach.of(fallback.outer, p));
            }
        }
        if (result.isEmpty()) {
            result.add(Approach.blocked(p));
        }
        return result;
    }

    private boolean sameTarget(Approach approach, Candidate candidate) {
        return approach.getTarget().distance(candidate.outer) < EPS;
    }

    private RestrictionObject owningRestriction(NetworkDataset dataset, Point point) {
        if (dataset.getRestrictions() == null) {
            return null;
        }
        for (RestrictionObject restriction : dataset.getRestrictions()) {
            if ("oks".equals(restriction.getRestrictionType())
                    && restriction.getGeometry() != null
                    && restriction.getGeometry().contains(point)) {
                return restriction;
            }
        }
        return null;
    }

    private List<Prohibited> prohibited(NetworkDataset dataset, int designDiameterMm,
                                        double halfWidth) {
        List<Prohibited> result = new ArrayList<>();
        if (dataset.getRestrictions() == null) {
            return result;
        }
        for (RestrictionObject restriction : dataset.getRestrictions()) {
            RestrictionRule rule = rules.resolve(restriction.getRestrictionType());
            if (rule.isSpecial() || restriction.getGeometry() == null) {
                continue;
            }
            double distance = rule.minDistanceForDn(designDiameterMm) + halfWidth;
            result.add(new Prohibited(restriction, distance));
        }
        return result;
    }

    private Candidate nearestBoundaryCandidate(Coordinate p, Geometry own,
                                               double offset, double clearance) {
        Geometry boundary = own.getBoundary();
        if (boundary == null || boundary.isEmpty()) {
            return null;
        }
        Coordinate[] nearest = DistanceOp.nearestPoints(boundary, point(p));
        if (nearest.length == 0) {
            return null;
        }
        return candidateFrom(p, nearest[0], offset, clearance);
    }

    /** Допустимые перпендикуляры, упорядоченные по длине хвоста (кратчайший первым). */
    private List<Candidate> perpendicularCandidates(Coordinate p, RestrictionObject own,
                                                    List<Prohibited> prohibited, double offset,
                                                    double clearance) {
        List<Candidate> candidates = new ArrayList<>();
        for (LineString segment : boundarySegments(own.getGeometry())) {
            Coordinate[] nearest = DistanceOp.nearestPoints(segment, point(p));
            if (nearest.length == 0) {
                continue;
            }
            Coordinate a = segment.getCoordinateN(0);
            Coordinate b = segment.getCoordinateN(segment.getNumPoints() - 1);
            Coordinate q = nearest[0];
            if (q.distance(a) < VERTEX_TOLERANCE_M || q.distance(b) < VERTEX_TOLERANCE_M
                    || a.distance(b) < VERTEX_TOLERANCE_M) {
                continue;
            }
            Candidate candidate = candidateFrom(p, q, offset, clearance);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.length));
        List<Candidate> feasible = new ArrayList<>();
        for (Candidate candidate : candidates) {
            if (feasible(candidate, p, own, prohibited, offset)) {
                feasible.add(candidate);
                if (feasible.size() >= MAX_CANDIDATES) {
                    break;
                }
            }
        }
        return feasible;
    }

    /**
     * Кратчайший прямой выход из кармана без требования перпендикулярности:
     * перебор направлений, для каждого — первая точка вне буфера своей ОКС.
     */
    private Candidate fallbackCandidate(Coordinate p, RestrictionObject own,
                                        List<Prohibited> prohibited, double offset,
                                        double clearance) {
        Geometry ownGeometry = own.getGeometry();
        Candidate best = null;
        double bestLength = Double.POSITIVE_INFINITY;
        for (int i = 0; i < FALLBACK_DIRECTIONS; i++) {
            double angle = 2.0 * Math.PI * i / FALLBACK_DIRECTIONS;
            double ux = Math.cos(angle);
            double uy = Math.sin(angle);
            double exit = -1.0;
            for (double t = 0.0; t <= FALLBACK_MAX_M; t += FALLBACK_STEP_M) {
                Coordinate q = new Coordinate(p.x + ux * t, p.y + uy * t);
                if (DistanceOp.distance(point(q), ownGeometry) >= offset - EPS) {
                    exit = t;
                    break;
                }
            }
            if (exit < 0.0) {
                continue;
            }
            Coordinate outer = new Coordinate(p.x + ux * (exit + clearance),
                    p.y + uy * (exit + clearance));
            Candidate candidate = new Candidate(outer, exit);
            if (!feasible(candidate, p, own, prohibited, offset)) {
                continue;
            }
            if (exit < bestLength - EPS) {
                bestLength = exit;
                best = candidate;
            }
        }
        return best;
    }

    private Candidate candidateFrom(Coordinate p, Coordinate q, double offset, double clearance) {
        double length = p.distance(q);
        if (length < EPS) {
            return null;
        }
        double ux = (q.x - p.x) / length;
        double uy = (q.y - p.y) / length;
        Coordinate outer = new Coordinate(q.x + ux * (offset + clearance),
                q.y + uy * (offset + clearance));
        return new Candidate(outer, length);
    }

    private boolean feasible(Candidate candidate, Coordinate p, RestrictionObject own,
                             List<Prohibited> prohibited, double offset) {
        if (DistanceOp.distance(point(candidate.outer), own.getGeometry()) < offset - EPS) {
            return false;
        }
        LineString tail = line(p, candidate.outer);
        Envelope tailEnvelope = tail.getEnvelopeInternal();
        for (Prohibited obstacle : prohibited) {
            if (obstacle.restriction == own) {
                continue;
            }
            Envelope envelope = obstacle.restriction.getGeometry().getEnvelopeInternal();
            if (tailEnvelope.distance(envelope) > obstacle.distance) {
                continue;
            }
            if (DistanceOp.distance(tail, obstacle.restriction.getGeometry())
                    < obstacle.distance - EPS) {
                return false;
            }
        }
        return true;
    }

    private List<LineString> boundarySegments(Geometry geometry) {
        List<LineString> segments = new ArrayList<>();
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry component = geometry.getGeometryN(i);
            if (component instanceof Polygon) {
                Polygon polygon = (Polygon) component;
                addRing(polygon.getExteriorRing(), segments);
                for (int hole = 0; hole < polygon.getNumInteriorRing(); hole++) {
                    addRing(polygon.getInteriorRingN(hole), segments);
                }
            } else if (component.getNumGeometries() > 1) {
                segments.addAll(boundarySegments(component));
            }
        }
        return segments;
    }

    private void addRing(LineString ring, List<LineString> segments) {
        Coordinate[] coordinates = ring.getCoordinates();
        for (int i = 0; i < coordinates.length - 1; i++) {
            if (coordinates[i].distance(coordinates[i + 1]) > EPS) {
                segments.add(line(coordinates[i], coordinates[i + 1]));
            }
        }
    }

    private Point point(Coordinate coordinate) {
        return GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate);
    }

    private LineString line(Coordinate a, Coordinate b) {
        return GeometrySupport.GEOMETRY_FACTORY.createLineString(new Coordinate[]{a, b});
    }

    /** Точка стыковки с сетью маршрутов и хвост до самой точки подключения. */
    public static final class Approach {
        private final Coordinate target;
        private final List<Coordinate> tail;
        private final boolean blocked;

        private Approach(Coordinate target, List<Coordinate> tail, boolean blocked) {
            this.target = target;
            this.tail = tail;
            this.blocked = blocked;
        }

        static Approach of(Coordinate outer, Coordinate point) {
            return new Approach(outer, List.of(outer, point), false);
        }

        static Approach direct(Coordinate coordinate) {
            return new Approach(coordinate, List.of(), false);
        }

        static Approach blocked(Coordinate coordinate) {
            return new Approach(coordinate, List.of(), true);
        }

        public Coordinate getTarget() {
            return target;
        }

        /** Хвост [target, point]; для точки вне `oks` или блокировки — пустой. */
        public List<Coordinate> getTail() {
            return tail;
        }

        public boolean isBlocked() {
            return blocked;
        }
    }

    private static final class Prohibited {
        private final RestrictionObject restriction;
        private final double distance;

        private Prohibited(RestrictionObject restriction, double distance) {
            this.restriction = restriction;
            this.distance = distance;
        }
    }

    private static final class Candidate {
        private final Coordinate outer;
        private final double length;

        private Candidate(Coordinate outer, double length) {
            this.outer = outer;
            this.length = length;
        }
    }
}
