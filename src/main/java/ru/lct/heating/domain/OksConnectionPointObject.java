package ru.lct.heating.domain;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/**
 * Точка подключения перспективного ОКС. По протоколу встречи именно она является
 * целью маршрута; {@code flowTph} может задаваться здесь.
 */
@Value
@Builder
public class OksConnectionPointObject {
    String id;
    String oksId;
    Double flowTph;
    Point geometry;
}
