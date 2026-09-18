package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.RestrictionObject;

/**
 * Построение индекса запретных зон по входным ограничениям.
 * Спецпроходы (SPECIAL) пересечение допускают и в запретный индекс не входят.
 */
@Component
public class ObstacleIndexBuilder {

    private final RestrictionRuleResolver rules;

    public ObstacleIndexBuilder(RestrictionRuleResolver rules) {
        this.rules = rules;
    }

    public ObstacleIndex build(NetworkDataset dataset, int designDiameterMm, List<String> warnings) {
        List<PreparedGeometry> prohibited = new ArrayList<>();
        for (RestrictionObject restriction : dataset.getRestrictions()) {
            RestrictionRule rule = rules.resolve(restriction.getRestrictionType());
            if (!rules.isKnownType(restriction.getRestrictionType())) {
                warnings.add("UNKNOWN_RESTRICTION_TYPE: " + restriction.getRestrictionType()
                        + " (применено запасное правило)");
            }
            if (rule.isSpecial()) {
                continue;
            }
            double distance = rule.minDistanceForDn(designDiameterMm);
            if (distance > 0) {
                prohibited.add(PreparedGeometryFactory.prepare(restriction.getGeometry().buffer(distance)));
            } else {
                prohibited.add(PreparedGeometryFactory.prepare(restriction.getGeometry()));
            }
        }
        return new ObstacleIndex(prohibited);
    }
}
