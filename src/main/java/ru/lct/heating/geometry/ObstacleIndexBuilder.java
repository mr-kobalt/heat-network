package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.EnvelopeCatalog;

/**
 * Построение индекса запретных зон по входным ограничениям (ТП v2, таблица 2).
 * Спецпроходы (SPECIAL) в запретный индекс не входят.
 *
 * <p>`oks`-полигоны считаются непроходимыми целиком. Единственное исключение —
 * финальный прямой вывод к точке подключения (ТП 2.2, ADR-0023/0024): он
 * добавляется к геометрии отдельно и в запретном индексе не вырезается, поэтому
 * прочие участки трассы обязаны обходить `oks` с отступом.</p>
 */
@Component
public class ObstacleIndexBuilder {

    private final RestrictionRuleResolver rules;
    private final EnvelopeCatalog envelopes;

    public ObstacleIndexBuilder(RestrictionRuleResolver rules, EnvelopeCatalog envelopes) {
        this.rules = rules;
        this.envelopes = envelopes;
    }

    public ObstacleIndex build(NetworkDataset dataset, int designDiameterMm, List<String> warnings) {
        double halfWidth = envelopes.halfPairWidthM(designDiameterMm);
        List<Geometry> prohibited = new ArrayList<>();

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
        }

        List<PreparedGeometry> prepared = new ArrayList<>();
        for (Geometry geometry : prohibited) {
            prepared.add(PreparedGeometryFactory.prepare(geometry));
        }
        return new ObstacleIndex(prepared);
    }
}
