package ru.lct.heating.routing.algorithm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.ForestNode;
import ru.lct.heating.routing.ForestPlanner;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.routing.ForestTree;

/**
 * Базовый алгоритм трассировки: лес на евклидовом MST, варианты различаются
 * радиусом кластеризации (ADR-0019/0027). Поведение по умолчанию сохранено.
 */
@Component
public class MstTracingAlgorithm implements TracingAlgorithm {

    public static final String ID = "mst";

    private static final int MAX_PLANS = 3;

    private final ForestPlanner forestPlanner;
    private final AppProperties appProperties;

    public MstTracingAlgorithm(ForestPlanner forestPlanner, AppProperties appProperties) {
        this.forestPlanner = forestPlanner;
        this.appProperties = appProperties;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Устаревший (deprecated): классический лес MST по точкам";
    }

    @Override
    public boolean deprecated() {
        return true;
    }

    @Override
    public List<ForestPlanningResult> plan(NetworkDataset dataset, ExistingNetworkGraph graph,
                                           ObstacleIndex obstacleIndex, List<String> warnings,
                                           Map<String, ConnectionExit> exits) {
        // До перевода mst на общий резолвер точки выхода (ADR-0032) считаются
        // внутри ForestPlanner; параметр exits пока не используется.
        List<ForestPlanningResult> plans = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (double radius : candidateRadii()) {
            ForestPlanningResult planning = forestPlanner.plan(
                    dataset, graph, obstacleIndex, warnings, radius);
            if (!signatures.add(signature(planning))) {
                continue;
            }
            plans.add(planning);
            if (plans.size() >= MAX_PLANS) {
                break;
            }
        }
        return plans;
    }

    private double[] candidateRadii() {
        double base = appProperties.getClusterRadiusM();
        return new double[]{base, base * 2.0, Math.max(base / 2.0, 1.0)};
    }

    /**
     * Подпись варианта: число деревьев/врезок и их привязка к сети. Вариант с
     * той же подписью считается неотличимым (FR-75).
     */
    private String signature(ForestPlanningResult planning) {
        String tieIns = planning.getTrees().stream()
                .map(this::tieInId)
                .sorted()
                .collect(Collectors.joining(","));
        return planning.getTrees().size() + "|" + tieIns;
    }

    private String tieInId(ForestTree tree) {
        ForestNode node = tree.requireNode(tree.getTieInNodeId());
        String existing = node.getExistingObjectId() == null ? "?" : node.getExistingObjectId();
        return existing + "@" + Math.round(node.getCoordinate().x) + ":"
                + Math.round(node.getCoordinate().y);
    }
}
