package ru.lct.heating.output;

import java.util.List;
import java.util.Set;
import lombok.Builder;
import lombok.Value;

/**
 * Сводка варианта по ТП v2 §7.2. Реконструкция и `tie_in` отсутствуют.
 */
@Value
@Builder(toBuilder = true)
public class VariantSummary {
    String variantId;
    int rank;
    long constructionCost;
    long chamberConstructionCost;
    int existingChamberTieInCount;
    long existingChamberTieInCost;
    long unconnectedPenalty;
    long calculatedCost;
    double newNetworkLengthM;
    double score;
    List<String> unconnectedOksIds;
    /** ID неподключённых точек, заданные во входе числами (сохранение типа, FR-84). */
    Set<String> numericOksIds;
}
