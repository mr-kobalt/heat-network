package ru.lct.heating.calculation;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import ru.lct.heating.output.VariantSummary;

@Value
@Builder
public class CalculationOutcome {
    VariantSummary summary;
    List<String> warnings;
}
