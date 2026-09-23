package ru.lct.heating.routing.algorithm;

import java.util.List;
import java.util.Map;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.trace.StageTrace;

/**
 * Подключаемый алгоритм трассировки (ADR-0027). Алгоритм строит план(ы)
 * новой сети (до трёх вариантов); расчёт стоимости, спецпроходов, ранжирование
 * и формирование GeoJSON — общая часть конвейера.
 */
public interface TracingAlgorithm {

    /** Стабильный идентификатор для API и конфигурации. */
    String id();

    /** Краткое описание для пользователя. */
    String description();

    /**
     * Устаревший алгоритм: скрывается из {@code GET /api/v1/algorithms} и
     * отклоняется при явном выборе (E25-09). По умолчанию — нет.
     */
    default boolean deprecated() {
        return false;
    }

    /**
     * План(ы) трассировки. Возвращается до трёх содержательно отличающихся
     * вариантов; порядок не важен — ранжирование выполняется позже по S.
     *
     * @param exits канонические точки выхода из `oks`-полигонов, посчитанные
     *              общим резолвером (ADR-0032); ключ — ID точки подключения
     */
    List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                    ObstacleIndex obstacleIndex, List<String> warnings,
                                    Map<String, ConnectionExit> exits);

    /**
     * План(ы) трассировки с записью промежуточных этапов (ADR-0036). По умолчанию
     * трассировка не поддерживается — вызов без неё; алгоритмы, поддерживающие
     * визуализацию этапов, переопределяют этот метод. {@code specialZones}
     * передаётся для учёта {@code Kспец} в целевой функции поиска (E25-04).
     */
    default List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                            ObstacleIndex obstacleIndex,
                                            SpecialZoneIndex specialZones, List<String> warnings,
                                            Map<String, ConnectionExit> exits, StageTrace trace) {
        return plan(dataset, graph, obstacleIndex, warnings, exits);
    }
}
