package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.operation.distance.DistanceOp;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.EnvelopeCatalog;

/**
 * Построение индекса запретных зон по входным ограничениям (ТП v2, таблица 2).
 * Спецпроходы (SPECIAL) в запретный индекс не входят.
 *
 * <p>Для `oks`-полигона, содержащего целевую точку подключения, вырезается
 * коридор финального прямого участка от ближайшей границы до точки (ТП 2.2,
 * ADR-0023); остальные ограничения действуют.</p>
 */
@Component
public class ObstacleIndexBuilder {

    private static final double CORRIDOR_MARGIN_M = 0.2;

    private final RestrictionRuleResolver rules;
    private final EnvelopeCatalog envelopes;

    public ObstacleIndexBuilder(RestrictionRuleResolver rules, EnvelopeCatalog envelopes) {
        this.rules = rules;
        this.envelopes = envelopes;
    }

    public ObstacleIndex build(NetworkDataset dataset, int designDiameterMm, List<String> warnings) {
        double halfWidth = envelopes.halfPairWidthM(designDiameterMm);
        List<Geometry> prohibited = new ArrayList<>();
        List<Geometry> originals = new ArrayList<>();
        List<Boolean> isOks = new ArrayList<>();

        for (RestrictionObject restriction : dataset.getRestrictions()) {
            RestrictionRule rule = rules.resolve(restriction.getRestrictionType());
            if (!rules.isKnownType(restriction.getRestrictionType())) {
                warnings.add("UNKNOWN_RESTRICTION_TYPE: " + restriction.getRestrictionType()
                        + " (применено запасное правило)");
            }
            if (rule.isSpecial()) {
                continue;
            }
            double distance = rule.minDistanceForDn(designDiameterMm) + halfWidth;
            Geometry geometry = restriction.getGeometry();
            prohibited.add(distance > 0 ? geometry.buffer(distance) : geometry);
            originals.add(geometry);
            isOks.add("oks".equals(restriction.getRestrictionType()));
        }

        if (dataset.getConnectionPoints() != null) {
            for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
                carveFinalCorridors(connectionPoint, prohibited, originals, isOks, halfWidth);
            }
        }

        List<PreparedGeometry> prepared = new ArrayList<>();
        for (Geometry geometry : prohibited) {
            prepared.add(PreparedGeometryFactory.prepare(geometry));
        }
        return new ObstacleIndex(prepared);
    }

    private void carveFinalCorridors(OksConnectionPointObject connectionPoint, List<Geometry> prohibited,
                                     List<Geometry> originals, List<Boolean> isOks, double halfWidth) {
        if (connectionPoint.getGeometry() == null) {
            return;
        }
        Point point = connectionPoint.getGeometry();
        for (int i = 0; i < prohibited.size(); i++) {
            if (!isOks.get(i) || !originals.get(i).contains(point)) {
                continue;
            }
            Coordinate nearest = nearestBoundaryPoint(originals.get(i), point);
            if (nearest == null) {
                continue;
            }
            Geometry corridor = GeometrySupport.GEOMETRY_FACTORY
                    .createLineString(new Coordinate[]{point.getCoordinate(), nearest})
                    .buffer(halfWidth + CORRIDOR_MARGIN_M);
            prohibited.set(i, prohibited.get(i).difference(corridor));
        }
    }

    private Coordinate nearestBoundaryPoint(Geometry original, Point point) {
        Geometry boundary = original.getBoundary();
        if (boundary == null || boundary.isEmpty()) {
            return null;
        }
        Coordinate[] nearest = DistanceOp.nearestPoints(boundary, point);
        return nearest.length > 0 ? nearest[0] : null;
    }
}
