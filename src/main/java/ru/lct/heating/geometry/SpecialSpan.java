package ru.lct.heating.geometry;

import lombok.Builder;
import lombok.Value;

/**
 * Непрерывный участок трассы, попадающий в спецзоны, в расстояниях вдоль
 * трассы. {@code kSpecial} — максимум по наложившимся зонам (ADR-0011).
 */
@Value
@Builder
public class SpecialSpan {
    double startDistanceM;
    double endDistanceM;
    double kSpecial;

    public double lengthM() {
        return endDistanceM - startDistanceM;
    }
}
