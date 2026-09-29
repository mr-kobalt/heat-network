package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.prep.PreparedGeometry;
import org.locationtech.jts.geom.prep.PreparedGeometryFactory;
import org.locationtech.jts.index.strtree.STRtree;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
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
    private final AppProperties appProperties;
    private final SpecialGateCarver gateCarver;

    public ObstacleIndexBuilder(RestrictionRuleResolver rules, EnvelopeCatalog envelopes,
                                DiameterCatalog diameters) {
        this(rules, envelopes, diameters, new AppProperties(),
                new SpecialGateCarver(new RestrictionAxisBuilder()));
    }

    @Autowired
    public ObstacleIndexBuilder(RestrictionRuleResolver rules, EnvelopeCatalog envelopes,
                                DiameterCatalog diameters, AppProperties appProperties,
                                SpecialGateCarver gateCarver) {
        this.rules = rules;
        this.envelopes = envelopes;
        this.diameters = diameters;
        this.appProperties = appProperties;
        this.gateCarver = gateCarver;
    }

    public ObstacleIndex build(NetworkDataset dataset, List<String> warnings) {
        return buildInternal(dataset, warnings, null, null);
    }

    /**
     * E25-05b: индекс с буферами, посчитанными по заданному фактическому Ду
     * (для всех правил, включая `oks`), вместо минимального Ду номенклатуры.
     */
    public ObstacleIndex buildAtDiameter(NetworkDataset dataset, int diameterMm,
                                         List<String> warnings) {
        return buildInternal(dataset, warnings, diameterMm, null);
    }

    /**
     * E25-07: индекс со строгими спецполосами и заданным шагом «ворот»
     * (для адаптивного уменьшения шага при застревании).
     *
     * @param uniformDn Ду для буферов или {@code null} (минимальный)
     */
    public ObstacleIndex buildWithGateStep(NetworkDataset dataset, Integer uniformDn,
                                           double gateStepM, List<String> warnings) {
        return buildInternal(dataset, warnings, uniformDn, gateStepM);
    }

    private ObstacleIndex buildInternal(NetworkDataset dataset, List<String> warnings,
                                        Integer uniformDn, Double gateStepOverride) {
        int globalDn = uniformDn != null ? uniformDn : minDiameterMm();
        List<Geometry> prohibited = new ArrayList<>();
        // E25-07: строгие спецполосы объединяются и перфорируются единой сетью
        // «ворот», чтобы наложение полос не блокировало пересечение.
        List<Geometry> strictGeometries = new ArrayList<>();
        List<Double> strictWidths = new ArrayList<>();
        // E8-15d1: индекс точек подключения — иначе oksDiameterMm перебирает все
        // точки на каждый `oks`-полигон (O(oks × точки)).
        STRtree pointIndex = new STRtree();
        if (dataset.getConnectionPoints() != null) {
            for (OksConnectionPointObject point : dataset.getConnectionPoints()) {
                if (point.getGeometry() != null && point.getFlowTph() != null
                        && point.getFlowTph() > 0.0) {
                    pointIndex.insert(point.getGeometry().getEnvelopeInternal(), point);
                }
            }
        }
        pointIndex.build();
        Map<Double, Integer> dnByFlow = new HashMap<>();

        for (RestrictionObject restriction : dataset.getRestrictions()) {
            RestrictionRule rule = rules.resolve(restriction.getRestrictionType());
            if (!rules.isKnownType(restriction.getRestrictionType())) {
                warnings.add("UNKNOWN_RESTRICTION_TYPE: " + restriction.getRestrictionType()
                        + " (применено запасное правило)");
            }
            if (rule.isSpecial()) {
                // E25/ADR-0045: строгий обход — полоса непроходима, пересечение
                // только через «ворота». Существующая тепловая сеть — цель врезки,
                // её полоса не блокируется (иначе источники недостижимы).
                if (appProperties.isForestSpecialStrict()
                        && !"heat_network".equals(restriction.getRestrictionType())) {
                    // E38: строгая полоса покрывает всю спецзону (zoneBufferM), а не
                    // только minDistance — тогда пересечение возможно только через
                    // перпендикулярные «ворота», спецучасток прямой (FR-52/53).
                    strictGeometries.add(restriction.getGeometry());
                    strictWidths.add(rule.zoneBufferM() + envelopes.halfPairWidthM(globalDn));
                }
                continue;
            }
            int dn = uniformDn != null ? uniformDn
                    : ("oks".equals(restriction.getRestrictionType())
                            ? oksDiameterMm(restriction, globalDn, pointIndex, dnByFlow) : globalDn);
            double distance = rule.minDistanceForDn(dn) + envelopes.halfPairWidthM(dn);
            Geometry geometry = restriction.getGeometry();
            prohibited.add(distance > 0 ? geometry.buffer(distance) : geometry);
        }

        if (!strictGeometries.isEmpty()) {
            double gateStep = gateStepOverride != null ? gateStepOverride
                    : appProperties.getSpecialGateStepM();
            Geometry carved = gateCarver.carveUnion(strictGeometries, strictWidths, gateStep,
                    appProperties.getSpecialGateThicknessM());
            if (carved != null && !carved.isEmpty()) {
                prohibited.add(carved);
            }
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

    /**
     * Ду `oks`-полигона: минимум по накрытым точкам подключения, иначе глобальный.
     * E8-15d1: точки берутся из STRtree по габаритам полигона.
     */
    private int oksDiameterMm(RestrictionObject oks, int fallback, STRtree pointIndex,
                              Map<Double, Integer> dnByFlow) {
        if (oks.getGeometry() == null) {
            return fallback;
        }
        int best = Integer.MAX_VALUE;
        Envelope envelope = oks.getGeometry().getEnvelopeInternal();
        @SuppressWarnings("unchecked")
        List<OksConnectionPointObject> candidates = pointIndex.query(envelope);
        for (OksConnectionPointObject point : candidates) {
            if (!oks.getGeometry().covers(point.getGeometry())) {
                continue;
            }
            best = Math.min(best, dnByFlow.computeIfAbsent(point.getFlowTph(),
                    this::designDiameterMm));
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
