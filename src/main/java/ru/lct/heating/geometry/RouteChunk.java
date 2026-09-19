package ru.lct.heating.geometry;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.LineString;

/**
 * Часть трассы с однородным способом прокладки: обычная ({@code base}) или
 * специальный проход ({@code special}) с соответствующим Kспец.
 */
@Value
@Builder
public class RouteChunk {
    LineString geometry;
    boolean special;
    double kSpecial;

    public String layingMethod() {
        return special ? "special" : "base";
    }
}
