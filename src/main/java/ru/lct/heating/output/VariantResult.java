package ru.lct.heating.output;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VariantResult {
    String variantId;
    List<OutputSegment> segments;
    List<OutputTieIn> tieIns;
    List<OutputChamber> chambers;
    List<OutputTechnicalNode> technicalNodes;
    VariantSummary summary;
}
