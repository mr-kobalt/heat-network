package ru.lct.heating.routing.algorithm;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.routing.GridForestPlanner;

/**
 * Алгоритм единого леса (ADR-0031/0034): топология поиском по сетке от
 * существующей сети, точное уточнение геометрии. Id {@code stub} сохранён как
 * временный (ADR-0027); на период разработки повороты и врезка ослаблены.
 */
@Component
public class GridForestTracingAlgorithm implements TracingAlgorithm {

    public static final String ID = "grid-forest";

    private final GridForestPlanner forestPlanner;

    public GridForestTracingAlgorithm(GridForestPlanner forestPlanner) {
        this.forestPlanner = forestPlanner;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Единый лес: поиск по сетке от существующей сети (общие стволы)";
    }

    @Override
    public List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                           ObstacleIndex obstacleIndex, List<String> warnings,
                                           Map<String, ConnectionExit> exits) {
        return List.of(forestPlanner.plan(dataset, graph, obstacleIndex, warnings, exits));
    }
}
