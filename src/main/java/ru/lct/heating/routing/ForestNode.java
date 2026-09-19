package ru.lct.heating.routing;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

/**
 * Узел плана новой сети (ТП v2). Для {@link NodeType#CHAMBER} флаг
 * {@code existing} показывает, используется ли существующая камера (тогда
 * {@code existingObjectId} — её ID) или создана новая.
 */
@Value
@Builder(toBuilder = true)
public class ForestNode {
    String id;
    NodeType type;
    Coordinate coordinate;
    boolean existing;
    /** Для существующей камеры — её ID во входных данных. */
    String existingObjectId;
}
