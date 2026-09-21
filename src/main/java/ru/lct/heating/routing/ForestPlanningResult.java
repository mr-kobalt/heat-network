package ru.lct.heating.routing;

import java.util.List;
import lombok.Builder;
import lombok.Value;

/**
 * Результат планирования леса новой сети (ADR-0019).
 */
@Value
@Builder
public class ForestPlanningResult {
    List<ForestTree> trees;
    List<String> unconnectedConnectionPointIds;
    /** Диагностика поиска по сетке (ADR-0033/0034); в GeoJSON не попадает. */
    GridReport gridReport;
}
