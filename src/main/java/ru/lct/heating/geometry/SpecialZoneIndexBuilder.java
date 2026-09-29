package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.hydraulics.EnvelopeCatalog;

/**
 * Построение реестра спецзон по конфигурируемым правилам SPECIAL (ADR-0009,
 * ADR-0018). Буфер расширяется на половину габарита пары труб (ТП 4.2, FR-56).
 */
@Component
public class SpecialZoneIndexBuilder {

    private final RestrictionRuleResolver rules;
    private final RestrictionAxisBuilder axisBuilder;
    private final EnvelopeCatalog envelopes;

    public SpecialZoneIndexBuilder(RestrictionRuleResolver rules, RestrictionAxisBuilder axisBuilder,
                                   EnvelopeCatalog envelopes) {
        this.rules = rules;
        this.axisBuilder = axisBuilder;
        this.envelopes = envelopes;
    }

    public SpecialZoneIndex build(NetworkDataset dataset, int designDiameterMm, List<String> warnings) {
        List<SpecialZone> zones = new ArrayList<>();
        double halfWidth = envelopes.halfPairWidthM(designDiameterMm);
        for (RestrictionObject restriction : dataset.getRestrictions()) {
            RestrictionRule rule = rules.resolve(restriction.getRestrictionType());
            if (!rule.isSpecial()) {
                continue;
            }
            if (rule.getKSpecial() == null) {
                warnings.add("SPECIAL_WITHOUT_KS: " + restriction.getRestrictionType()
                        + " (спецзона пропущена)");
                continue;
            }
            double buffer = rule.zoneBufferM() + halfWidth;
            zones.add(SpecialZone.builder()
                    .restrictionType(restriction.getRestrictionType())
                    .kSpecial(rule.getKSpecial())
                    .angleMinDeg(rule.getAngleMinDeg())
                    .bufferM(buffer)
                    .verticalTopDepthM(rule.getVerticalTopDepthM())
                    .verticalHeightM(rule.getVerticalHeightM())
                    .axis(axisBuilder.axis(restriction.getGeometry()))
                    .zone(restriction.getGeometry().buffer(buffer))
                    .build());
        }
        return new SpecialZoneIndex(zones);
    }
}
