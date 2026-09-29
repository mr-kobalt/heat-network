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
    /** Эрозия при проверке соседних компонент своего ОКС (ADR-0040). */
    private static final double SIBLING_EROSION_M = 1e-3;
    /** Радиус поиска соседних запретных буферов для «узкого промежутка», м. */
    private static final double NARROW_GAP_SEARCH_M = 20.0;
    private static final int MAX_CANDIDATES = 6;
    private static final int FALLBACK_DIRECTIONS = 72;
    private static final double FALLBACK_STEP_M = 0.25;
    private static final double FALLBACK_MAX_M = 2000.0;

    private final RestrictionRuleResolver rules;
    private final EnvelopeCatalog envelopes;
    private final DiameterCatalog diameters;
    private final AppProperties appProperties;

    /** E8-15d1: кэш индексов последнего набора (точек, oks, запретов). */
    private NetworkDataset cachedDataset;
    private Context cachedContext;

    public OksApproachResolver(RestrictionRuleResolver rules, EnvelopeCatalog envelopes,
                               DiameterCatalog diameters, AppProperties appProperties) {
        this.rules = rules;
        this.envelopes = envelopes;
        this.diameters = diameters;
        this.appProperties = appProperties;
    }

    /**
     * E8-15d1: индексы набора — точки по id, `oks`-полигоны (STRtree), список
     * неспециальных ограничений и кэш {@code prohibited} по Ду. Иначе на каждую
     * точку перебираются все ограничения/точки (O(точки × ограничения)).
     */
    private static final class Context {
        private final Map<String, OksConnectionPointObject> pointsById = new HashMap<>();
        private final List<RestrictionObject> oksRestrictions = new ArrayList<>();
        private final Map<RestrictionObject, Integer> oksOrder =
                new java.util.IdentityHashMap<>();
        private final org.locationtech.jts.index.strtree.STRtree oksIndex =
                new org.locationtech.jts.index.strtree.STRtree();
        private final List<RestrictionObject> prohibitedRestrictions = new ArrayList<>();
        private final Map<Integer, List<Prohibited>> prohibitedByDn = new HashMap<>();
        /** E8-15d2b: полные списки выходов-кандидатов на точку (из resolveExits). */
        private final Map<String, List<ConnectionExit>> candidatesCache = new HashMap<>();
    }

    private synchronized Context context(NetworkDataset dataset) {
        if (dataset == cachedDataset && cachedContext != null) {
            return cachedContext;
        }
        Context context = new Context();
        if (dataset.getConnectionPoints() != null) {
            for (OksConnectionPointObject point : dataset.getConnectionPoints()) {
                context.pointsById.put(point.getId(), point);
            }
        }
        if (dataset.getRestrictions() != null) {
            for (RestrictionObject restriction : dataset.getRestrictions()) {
                if (restriction.getGeometry() == null) {
                    continue;
                }
                RestrictionRule rule = rules.resolve(restriction.getRestrictionType());
                if ("oks".equals(restriction.getRestrictionType())) {
                    context.oksOrder.put(restriction, context.oksRestrictions.size());
                    context.oksRestrictions.add(restriction);
                    context.oksIndex.insert(restriction.getGeometry().getEnvelopeInternal(),
                            restriction);
                }
                if (!rule.isSpecial()) {
                    context.prohibitedRestrictions.add(restriction);
                }
            }
        }
        context.oksIndex.build();
        cachedDataset = dataset;
        cachedContext = context;
        return context;
    }

    /**
     * Канонические точки выхода из `oks`-полигонов для нового алгоритма
     * (ADR-0032): одна точка на подключение. Ду выбирается минимальным под
     * расход точки (FR-43), точка выхода — перпендикуляр к ближайшему ребру
     * выпуклой оболочки полигона, продолженный на расстояние
     * `minDistance(oks, Ду) + halfPairWidth(Ду) + диагональ клетки` (ADR-0037).
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
        Context context = context(dataset);
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            Double flow = connectionPoint.getFlowTph();
            if (flow == null || flow <= 0.0 || connectionPoint.getGeometry() == null) {
                skipped++;
                continue;
            }
            // E8-15d2b: полный список кандидатов кэшируется, чтобы
            // `candidatesFor` не пересчитывал его в планировщике.
            List<ConnectionExit> candidates = exitCandidates(connectionPoint, dataset, flow);
            context.candidatesCache.put(connectionPoint.getId(), candidates);
            ConnectionExit exit = candidates.get(0);
            if (exit.isBlocked()) {
                blocked++;
            }
            result.put(connectionPoint.getId(), exit);
        }
        log.info("OKS exits: points={} resolved={} blocked={} skipped={} time={} ms",
                total, result.size(), blocked, skipped, (System.nanoTime() - start) / 1_000_000L);
        return result;
    }

    /**
     * Все валидные выходы точки, упорядоченные по возрастанию `p→target`
     * (ADR-0037). Первый — основной; остальные используются планировщиком как
     * альтернативы, если клетка основного выхода недостижима от сети.
     */
    public List<ConnectionExit> candidatesFor(NetworkDataset dataset, String pointId) {
        if (dataset.getConnectionPoints() == null || pointId == null) {
            return List.of();
        }
        Context context = context(dataset);
        List<ConnectionExit> cached = context.candidatesCache.get(pointId);
        if (cached != null) {
            return cached;
        }
        OksConnectionPointObject point = context.pointsById.get(pointId);
        if (point == null) {
            return List.of();
        }
        Double flow = point.getFlowTph();
        if (flow == null || flow <= 0.0 || point.getGeometry() == null) {
            return List.of();
        }
        return exitCandidates(point, dataset, flow);
    }

    private List<ConnectionExit> exitCandidates(OksConnectionPointObject connectionPoint,
                                                NetworkDataset dataset, double flow) {
        Point point = connectionPoint.getGeometry();
        Coordinate p = point.getCoordinate();
        Integer dn = designDiameter(flow);
        if (dn == null) {
            return List.of(exit(connectionPoint, p, List.of(), true, 0));
        }
        RestrictionObject own = owningRestriction(dataset, point);
        if (own == null) {
            return List.of(exit(connectionPoint, p, List.of(), false, dn));
        }
        Polygon component = containingPolygon(own.getGeometry(), point);
        if (component == null) {
            return List.of(exit(connectionPoint, p, List.of(), true, dn));
        }
        double halfWidth = envelopes.halfPairWidthM(dn);
        double buffer = rules.resolve("oks").minDistanceForDn(dn) + halfWidth;
        List<Prohibited> prohibited = prohibited(dataset, dn, halfWidth);
        List<Candidate> candidates = exitCandidates(p, component, own, buffer, prohibited);
        if (candidates.isEmpty()) {
            return List.of(exit(connectionPoint, p, List.of(), true, dn));
        }
        List<ConnectionExit> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            result.add(exit(connectionPoint, candidate.outer, List.of(candidate.outer, p), false, dn));
        }
        return result;
    }

    private ConnectionExit exit(OksConnectionPointObject connectionPoint, Coordinate target,
                                List<Coordinate> tail, boolean blocked, int dn) {
        return ConnectionExit.builder()
                .connectionPointId(connectionPoint.getId())
                .target(target)
                .tail(tail)
                .blocked(blocked)
                .designDiameterMm(dn)
                .build();
    }

    /** Компонента-полигон, содержащая точку (внешнее кольцо — граница выхода). */
    private Polygon containingPolygon(Geometry restriction, Point point) {
        for (int i = 0; i < restriction.getNumGeometries(); i++) {
            Geometry component = restriction.getGeometryN(i);
            if (component instanceof Polygon && component.covers(point)) {
                return (Polygon) component;
            }
        }
        return null;
    }

    /**
     * Внешний контур буфера ОКС (внешние кольца полигонов, без внутренних
     * «дырок»): цель не должна попадать во внутренний двор/карман, замкнутый
     * буфером кластера ОКС.
     */
    private Geometry exteriorBoundary(Geometry buffered) {
        List<LineString> rings = new ArrayList<>();
        collectExteriorRings(buffered, rings);
        if (rings.isEmpty()) {
            return buffered.getFactory().createGeometryCollection();
        }
        return buffered.getFactory().createMultiLineString(rings.toArray(new LineString[0]));
    }

    private void collectExteriorRings(Geometry geometry, List<LineString> rings) {
        for (int i = 0; i < geometry.getNumGeometries(); i++) {
            Geometry component = geometry.getGeometryN(i);
            if (component instanceof Polygon) {
                rings.add(((Polygon) component).getExteriorRing());
            } else if (component.getNumGeometries() > 1) {
                collectExteriorRings(component, rings);
            }
        }
    }

    /** Все валидные пересечения луча с буфером ОКС, по возрастанию `p→target`. */
    private List<Candidate> exitCandidates(Coordinate p, Polygon component, RestrictionObject own,
                                           double buffer, List<Prohibited> prohibited) {
        Geometry bufferBoundary = exteriorBoundary(own.getGeometry().buffer(buffer));
        Envelope ownEnvelope = own.getGeometry().getEnvelopeInternal();
        double rayLength = Math.hypot(ownEnvelope.getWidth(), ownEnvelope.getHeight())
                + 2.0 * buffer + 1.0 + NARROW_GAP_SEARCH_M;
        double maxPairWidth = envelopes.maxPairWidthM();
        boolean filter = appProperties.isOksExitFilter();
        double maxTail = appProperties.getOksExitMaxTailM();
        Geometry siblings = filter ? siblingInterior(own.getGeometry(), component) : null;
        LineString ring = component.getExteriorRing();
        Coordinate[] ringCoordinates = ring.getCoordinates();
        List<Candidate> candidates = new ArrayList<>();
        for (int i = 0; i < ringCoordinates.length - 1; i++) {
            Coordinate a = ringCoordinates[i];
            Coordinate b = ringCoordinates[i + 1];
            if (a.distance(b) < EPS) {
                continue;
            }
            Coordinate q = nearestPointOnSegment(p, a, b);
            double length = p.distance(q);
            double ux;
            double uy;
            if (length < EPS) {
                // Точка лежит на грани: направление выхода — внешняя нормаль ребра.
                double ex = b.x - a.x;
                double ey = b.y - a.y;
                double edge = Math.hypot(ex, ey);
                if (edge < EPS) {
                    continue;
                }
                double nx = -ey / edge;
                double ny = ex / edge;
                Coordinate probe = new Coordinate(p.x + nx * 0.1, p.y + ny * 0.1);
                if (component.covers(point(probe))) {
                    nx = -nx;
                    ny = -ny;
                }
                ux = nx;
                uy = ny;
            } else {
                if (q.distance(a) < VERTEX_TOLERANCE_M || q.distance(b) < VERTEX_TOLERANCE_M) {
                    continue;
                }
                ux = (q.x - p.x) / length;
                uy = (q.y - p.y) / length;
            }
            LineString ray = line(p, new Coordinate(p.x + ux * rayLength, p.y + uy * rayLength));
            Coordinate target = nearestCrossing(ray.intersection(bufferBoundary), p, length);
            if (target == null) {
                continue;
            }
            // Узкий промежуток между близкими зданиями: если сразу за выходом
            // луч снова упирается в запретный буфер ближе ширины пары, ставим
            // выход в середине промежутка — так клетка попадает в проходимый коридор.
            Coordinate next = nextBufferCrossing(ray, p, target, prohibited);
            if (next != null && target.distance(next) < maxPairWidth) {
                target = new Coordinate((target.x + next.x) / 2.0, (target.y + next.y) / 2.0);
            } else {
                // E36: широкий зазор или препятствий впереди нет — сдвигаем выход
                // вдоль луча на extra, давая маршруту место (если хвост допустим).
                double extra = appProperties.getOksExitExtraBufferM();
                if (extra > 0) {
                    Coordinate pushed = pushAlongRay(p, target, extra);
                    if (tailAllowed(p, pushed, own, prohibited)) {
                        target = pushed;
                    }
                }
            }
            if (!tailAllowed(p, target, own, prohibited)) {
                continue;
            }
            // ADR-0040: отбраковка недопустимых выходов — «через всё здание»
            // (повторный вход в свой корпус), через соседние корпуса своего ОКС
            // и хвосты длиннее предела.
            if (filter) {
                // Длина хвоста без обязательного отступа = путь внутри корпуса.
                if (maxTail > 0 && p.distance(target) - buffer > maxTail + EPS) {
                    continue;
                }
                LineString tail = line(p, target);
                if (siblings != null && siblings.intersects(tail)) {
                    continue;
                }
                if (!singleExit(tail, component)) {
                    continue;
                }
            }
            candidates.add(new Candidate(target, p.distance(target)));
        }
        candidates.sort(Comparator.comparingDouble(candidate -> candidate.length));
        return candidates;
    }

    /**
     * ADR-0040: внутренние части прочих компонент своего ОКС (эрозия, чтобы
     * касание границы не считалось). {@code null}, если компонента одна.
     */
    private Geometry siblingInterior(Geometry restriction, Polygon component) {
        List<Geometry> others = new ArrayList<>();
        for (int i = 0; i < restriction.getNumGeometries(); i++) {
            Geometry candidate = restriction.getGeometryN(i);
            if (candidate == component) {
                continue;
            }
            others.add(candidate);
        }
        if (others.isEmpty()) {
            return null;
        }
        Geometry union = restriction.getFactory().buildGeometry(others);
        Geometry eroded = union.buffer(-SIBLING_EROSION_M);
        return eroded.isEmpty() ? null : eroded;
    }

    /** ADR-0040: хвост пересекает свой корпус ровно один раз (без возврата). */
    private boolean singleExit(LineString tail, Polygon component) {
        return tail.intersection(component).getNumGeometries() <= 1;
    }

    /**
     * Ближайшее пересечение луча с запретным буфером (включая свой ОКС) строго
     * дальше {@code target}. Буферы ищутся только у ограничений рядом с целью
     * (envelope-фильтр), чтобы не буферизовать весь набор.
     */
    private Coordinate nextBufferCrossing(LineString ray, Coordinate p, Coordinate target,
                                          List<Prohibited> prohibited) {
        Envelope window = new Envelope(target);
        window.expandBy(NARROW_GAP_SEARCH_M);
        double fromDistance = p.distance(target);
        Coordinate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Prohibited obstacle : prohibited) {
            Envelope envelope = obstacle.restriction.getGeometry().getEnvelopeInternal();
            if (!window.intersects(envelope)) {
                continue;
            }
            Geometry boundary = obstacle.restriction.getGeometry()
                    .buffer(obstacle.distance).getBoundary();
            for (Coordinate coordinate : ray.intersection(boundary).getCoordinates()) {
                double distance = p.distance(coordinate);
                if (distance <= fromDistance + EPS) {
                    continue;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = coordinate;
                }
            }
        }
        return best;
    }

    private Coordinate nearestPointOnSegment(Coordinate p, Coordinate a, Coordinate b) {
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double lengthSquared = dx * dx + dy * dy;
        if (lengthSquared < EPS) {
            return a;
        }
        double t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / lengthSquared;
        t = Math.max(0.0, Math.min(1.0, t));
        return new Coordinate(a.x + t * dx, a.y + t * dy);
    }

    /** Ближайшее к точке пересечение (строго за основанием перпендикуляра). */
    private Coordinate nearestCrossing(Geometry intersection, Coordinate p, double minDistance) {
        Coordinate best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Coordinate coordinate : intersection.getCoordinates()) {
            double distance = p.distance(coordinate);
            if (distance < minDistance - EPS) {
                continue;
            }
            if (distance < bestDistance) {
                bestDistance = distance;
                best = coordinate;
            }
        }
        return best;
    }

    /** Хвост не должен нарушать буферы прочих ограничений (кроме своего ОКС). */
    /** E36: сдвиг точки вдоль луча p→target ещё на {@code extra} метров. */
    private Coordinate pushAlongRay(Coordinate p, Coordinate target, double extra) {
        double dx = target.x - p.x;
        double dy = target.y - p.y;
        double norm = Math.hypot(dx, dy);
        if (norm < EPS) {
            return target;
        }
        return new Coordinate(target.x + dx / norm * extra, target.y + dy / norm * extra);
    }

    private boolean tailAllowed(Coordinate p, Coordinate target, RestrictionObject own,
                                List<Prohibited> prohibited) {
        LineString tail = line(p, target);
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

    /** Минимальный Ду под расход точки; {@code null}, если расход выше номенклатуры. */
    private Integer designDiameter(double flow) {
        try {
            return diameters.select(flow).getDn();
        } catch (IllegalArgumentException overflow) {
            return null;
        }
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
        Context context = context(dataset);
        if (context.oksRestrictions.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        List<RestrictionObject> candidates = context.oksIndex.query(point.getEnvelopeInternal());
        RestrictionObject best = null;
        int bestOrder = Integer.MAX_VALUE;
        for (RestrictionObject restriction : candidates) {
            // ADR-0035: точка подключения часто лежит на границе ОКС, где
            // contains() ложно; covers() учитывает и границу.
            boolean owns = appProperties.isOksOwningIncludeBoundary()
                    ? restriction.getGeometry().covers(point)
                    : restriction.getGeometry().contains(point);
            if (owns) {
                int order = context.oksOrder.getOrDefault(restriction, Integer.MAX_VALUE);
                if (order < bestOrder) {
                    bestOrder = order;
                    best = restriction;
                }
            }
        }
        return best;
    }

    private List<Prohibited> prohibited(NetworkDataset dataset, int designDiameterMm,
                                        double halfWidth) {
        Context context = context(dataset);
        return context.prohibitedByDn.computeIfAbsent(designDiameterMm, dn -> {
            List<Prohibited> result = new ArrayList<>(context.prohibitedRestrictions.size());
            for (RestrictionObject restriction : context.prohibitedRestrictions) {
                RestrictionRule rule = rules.resolve(restriction.getRestrictionType());
                double distance = rule.minDistanceForDn(dn)
                        + envelopes.halfPairWidthM(dn);
                result.add(new Prohibited(restriction, distance));
            }
            return result;
        });
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
