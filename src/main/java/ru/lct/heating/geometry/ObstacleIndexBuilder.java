package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.EnvelopeCatalog;

/**
 * Построение индекса запретных зон по входным ограничениям (ТП v2, таблица 2).
 * Спецпроходы (SPECIAL) в запретный индекс не входят.
 *
 * <p>Буфер считается по <b>минимальному известному</b> Ду номенклатуры
 * (ADR-0037): фактический Ду трассы определяется после маршрутизации, поэтому
 * индекс намеренно не завышается. Исключение — `oks`-полигон, внутри которого
 * есть точка подключения: для него берётся Ду этой точки (минимальная труба под
 * её расход). Учёт фактического Ду на этапе `refine` — отдельная задача.</p>
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
    private final DiameterCatalog diameters;

    public ObstacleIndexBuilder(RestrictionRuleResolver rules, EnvelopeCatalog envelopes,
                                DiameterCatalog diameters) {
        this.rules = rules;
        this.envelopes = envelopes;
        this.diameters = diameters;
    }

    public ObstacleIndex build(NetworkDataset dataset, List<String> warnings) {
        int globalDn = minDiameterMm();
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
            int dn = "oks".equals(restriction.getRestrictionType())
                    ? oksDiameterMm(dataset, restriction, globalDn) : globalDn;
            double distance = rule.minDistanceForDn(dn) + envelopes.halfPairWidthM(dn);
            Geometry geometry = restriction.getGeometry();
            prohibited.add(distance > 0 ? geometry.buffer(distance) : geometry);
        }

        List<PreparedGeometry> prepared = new ArrayList<>();
        for (Geometry geometry : prohibited) {
            prepared.add(PreparedGeometryFactory.prepare(geometry));
        }
        return new ObstacleIndex(prepared);
    }

    private int minDiameterMm() {
        return diameters.rows().get(0).getDn();
    }

    /** Ду `oks`-полигона: минимум по накрытым точкам подключения, иначе глобальный. */
    private int oksDiameterMm(NetworkDataset dataset, RestrictionObject oks, int fallback) {
        int best = Integer.MAX_VALUE;
        if (dataset.getConnectionPoints() != null) {
            for (OksConnectionPointObject point : dataset.getConnectionPoints()) {
                if (point.getGeometry() == null || point.getFlowTph() == null
                        || point.getFlowTph() <= 0.0) {
                    continue;
                }
                if (!oks.getGeometry().covers(point.getGeometry())) {
                    continue;
                }
                best = Math.min(best, designDiameterMm(point.getFlowTph()));
            }
        }
        return best == Integer.MAX_VALUE ? fallback : best;
    }

    private int designDiameterMm(double flow) {
        try {
            return diameters.select(flow).getDn();
        } catch (IllegalArgumentException overflow) {
            List<ru.lct.heating.hydraulics.DiameterRow> rows = diameters.rows();
            return rows.get(rows.size() - 1).getDn();
        }
    }
}
