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
    /**
     * Записаны ли файлы этапов (ADR-0036/0057). Ложь, если трассировка
     * запрашивалась, но была отключена режимом партиционирования (E8-15d2c2).
     */
    boolean traced;
}
