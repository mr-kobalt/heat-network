package ru.lct.heating.output;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class VariantSummary {
    String variantId;
    int rank;
    long constructionCost;
    long chamberConstructionCost;
    long tieInCost;
    long reconstructionCost;
    long chamberReconstructionCost;
    long unconnectedPenalty;
    long calculatedCost;
    double newNetworkLengthM;
    double reconstructionLengthM;
    double lengthM;
    double score;
    List<String> unconnectedOksIds;
}
