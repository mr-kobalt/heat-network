package ru.lct.heating.routing;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

/**
 * Точка выхода трассы из `oks`-полигона (ТП 2.2, ADR-0032): результат общего
 * резолвера, передаваемый в контракт алгоритмов трассировки. {@code target} —
 * точка стыковки за внешней границей буфера ОКС, {@code tail} — финальный
 * прямой отрезок {@code [target, точка подключения]} (пуст, если точка вне ОКС).
 */
@Value
@Builder
public class ConnectionExit {

    String connectionPointId;
    Coordinate target;
    List<Coordinate> tail;
    boolean blocked;
    /** Минимальный Ду, поддерживающий расход точки (мм). */
    int designDiameterMm;

    public boolean hasTail() {
        return tail != null && tail.size() == 2;
    }
}
