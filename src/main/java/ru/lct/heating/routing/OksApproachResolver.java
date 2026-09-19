package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.hydraulics.EnvelopeCatalog;

/**
 * Финальный прямой участок к точке подключения в `oks`-полигоне (ТП 2.2,
 * ADR-0023): маршрут подводится к внешней границе буфера отступа, далее
 * добавляется прямой отрезок до самой точки.
 */
@Component
public class OksApproachResolver {

    private final RestrictionRuleResolver rules;
    private final EnvelopeCatalog envelopes;

    public OksApproachResolver(RestrictionRuleResolver rules, EnvelopeCatalog envelopes) {
        this.rules = rules;
        this.envelopes = envelopes;
    }

    public Map<String, Approach> resolve(NetworkDataset dataset, int designDiameterMm) {
        Map<String, Approach> result = new HashMap<>();
        List<Geometry> oksPolygons = new ArrayList<>();
        if (dataset.getRestrictions() != null) {
            for (var restriction : dataset.getRestrictions()) {
                if ("oks".equals(restriction.getRestrictionType())) {
                    oksPolygons.add(restriction.getGeometry());
                }
            }
        }
        RestrictionRule rule = rules.resolve("oks");
        double offset = rule.minDistanceForDn(designDiameterMm)
                + envelopes.halfPairWidthM(designDiameterMm);
        if (dataset.getConnectionPoints() != null) {
            for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
                result.put(connectionPoint.getId(),
                        approach(connectionPoint.getGeometry(), oksPolygons, offset));
            }
        }
        return result;
    }

    private Approach approach(Point point, List<Geometry> oksPolygons, double offset) {
        if (point == null) {
            return Approach.direct(new Coordinate(0, 0));
        }
        Coordinate coordinate = point.getCoordinate();
        for (Geometry polygon : oksPolygons) {
            if (!polygon.contains(point)) {
                continue;
            }
            Geometry boundary = polygon.getBoundary();
            if (boundary == null || boundary.isEmpty()) {
                continue;
            }
            Coordinate[] nearest = DistanceOp.nearestPoints(boundary, point);
            if (nearest.length == 0 || nearest[0].equals2D(coordinate)) {
                continue;
            }
            double dx = nearest[0].x - coordinate.x;
            double dy = nearest[0].y - coordinate.y;
            double length = Math.hypot(dx, dy);
            if (length < 1e-9) {
                continue;
            }
            Coordinate outer = new Coordinate(
                    nearest[0].x + dx / length * offset,
                    nearest[0].y + dy / length * offset);
            List<Coordinate> tail = List.of(outer, coordinate);
            return new Approach(outer, tail);
        }
        return Approach.direct(coordinate);
    }

    /** Точка стыковки с сетью маршрутов и хвост до самой точки подключения. */
    public static final class Approach {
        private final Coordinate target;
        private final List<Coordinate> tail;

        private Approach(Coordinate target, List<Coordinate> tail) {
            this.target = target;
            this.tail = tail;
        }

        static Approach direct(Coordinate coordinate) {
            return new Approach(coordinate, List.of());
        }

        public Coordinate getTarget() {
            return target;
        }

        /** Хвост [target, point]; для точки вне `oks` — пустой. */
        public List<Coordinate> getTail() {
            return tail;
        }
    }
}
