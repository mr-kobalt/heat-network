package ru.lct.heating.output;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import ru.lct.heating.routing.GridReport;

/**
 * Результат варианта: только типы вывода ТП v2 §7.1.
 */
@Value
@Builder(toBuilder = true)
public class VariantResult {
    String variantId;
    List<OutputSegment> segments;
    List<OutputChamber> chambers;
    List<OutputTechnicalNode> technicalNodes;
    VariantSummary summary;
    /** Диагностика поиска (в GeoJSON не пишется). */
    GridReport gridReport;
}
