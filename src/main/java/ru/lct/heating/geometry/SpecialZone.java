package ru.lct.heating.geometry;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Geometry;

/**
 * Зона допустимого специального прохода: буфер для определения пересечения,
 * ось препятствия для расчёта угла и коэффициент Kспец (ТП 5.1, ADR-0018).
 */
@Value
@Builder
public class SpecialZone {
    String restrictionType;
    double kSpecial;
    Double angleMinDeg;
    /** Радиус зоны вокруг оси: {@code zoneBufferM + halfPairWidth}, м (E32). */
    double bufferM;
    /** ADR-0073: глубина до верха условного габарита коммуникации, м (или null). */
    Double verticalTopDepthM;
    /** ADR-0073: высота условного габарита коммуникации, м (или null). */
    Double verticalHeightM;
    Geometry axis;
    Geometry zone;
}
