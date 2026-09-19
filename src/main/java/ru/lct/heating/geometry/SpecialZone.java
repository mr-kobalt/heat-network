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
    Geometry axis;
    Geometry zone;
}
