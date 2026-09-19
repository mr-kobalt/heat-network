package ru.lct.heating.domain;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

/**
 * Точка подключения перспективного ОКС (ТП v2 §1.1): цель маршрута со своим
 * {@code flow_tph}. {@code numericId} фиксирует исходный тип ID (число/строка)
 * для сохранения типа в выводе (FR-84).
 */
@Value
@Builder
public class OksConnectionPointObject {
    String id;
    Double flowTph;
    boolean numericId;
    Point geometry;
}
