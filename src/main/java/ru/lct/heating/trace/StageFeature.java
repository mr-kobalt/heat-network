package ru.lct.heating.trace;

import java.util.Map;
import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Geometry;

/**
 * Объект промежуточного этапа алгоритма для визуализации (ADR-0036). Геометрия —
 * в EPSG:32637; преобразование в WGS84 выполняет писатель этапов.
 */
@Value
@Builder
public class StageFeature {
    /** Геометрия в EPSG:32637; {@code null} — объект без геометрии. */
    Geometry geometry;
    /** Тип объекта этапа (сеть, камера, выход, ребро и т. п.). */
    String objectType;
    /** Дополнительные атрибуты (примитивы), попадают в GeoJSON properties. */
    Map<String, Object> properties;
}
