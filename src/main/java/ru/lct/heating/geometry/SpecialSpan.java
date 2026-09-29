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
    /** Тип ограничения (ADR-0073: вертикальный учёт в режиме глубины). */
    String restrictionType;
    /** ADR-0073: глубина до верха габарита коммуникации, м (или null). */
    Double verticalTopDepthM;
    /** ADR-0073: высота габарита коммуникации, м (или null). */
    Double verticalHeightM;

    public double lengthM() {
        return endDistanceM - startDistanceM;
    }
}
