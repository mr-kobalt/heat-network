package ru.lct.heating.variants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.output.ForestResultBuilder;
import ru.lct.heating.output.VariantResult;
import ru.lct.heating.routing.ForestNode;
import ru.lct.heating.routing.ForestPlanner;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.routing.ForestTree;

/**
 * Генерация до трёх содержательно отличающихся вариантов и ранжирование по
 * показателю S (PDF 2.8, ТП 9; FR-75, FR-76). Варианты различаются радиусом
 * кластеризации (число врезок и общих стволов), а не смещением трассы.
 */
@Component
public class VariantGenerator {

    public static final int MAX_VARIANTS = 3;

    private final ForestPlanner forestPlanner;
    private final ForestResultBuilder resultBuilder;
    private final AppProperties appProperties;

    public VariantGenerator(ForestPlanner forestPlanner, ForestResultBuilder resultBuilder,
                            AppProperties appProperties) {
        this.forestPlanner = forestPlanner;
        this.resultBuilder = resultBuilder;
        this.appProperties = appProperties;
    }

    public List<VariantResult> generate(NetworkDataset dataset, ObstacleIndex obstacleIndex,
                                        ExistingNetworkGraph graph, SpecialZoneIndex specialZones,
                                        List<String> warnings) {
        List<VariantResult> variants = new ArrayList<>();
        Set<String> signatures = new HashSet<>();
        for (double radius : candidateRadii()) {
            ForestPlanningResult planning = forestPlanner.plan(
                    dataset, graph, obstacleIndex, warnings, radius);
            if (!signatures.add(signature(planning))) {
                continue;
            }
            variants.add(resultBuilder.build(planning, dataset, specialZones, warnings,
                    "v" + (variants.size() + 1), 0));
            if (variants.size() >= MAX_VARIANTS) {
                break;
            }
        }
        variants.sort(Comparator.comparingDouble(variant -> variant.getSummary().getScore()));
        List<VariantResult> ranked = new ArrayList<>();
        int rank = 1;
        for (VariantResult variant : variants) {
            ranked.add(variant.toBuilder()
                    .summary(variant.getSummary().toBuilder().rank(rank++).build())
                    .build());
        }
        return ranked;
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
