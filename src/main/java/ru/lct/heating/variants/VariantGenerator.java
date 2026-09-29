package ru.lct.heating.variants;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import ru.lct.heating.calculation.CalculationMode;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.output.ForestResultBuilder;
import ru.lct.heating.output.VariantResult;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.routing.algorithm.TracingAlgorithm;
import ru.lct.heating.trace.StageTrace;

/**
 * Формирование до трёх вариантов из плана выбранного алгоритма трассировки и
 * ранжирование по показателю S (ТП 9; FR-75, FR-76; ADR-0027). Алгоритм задаёт
 * состав вариантов, общий расчёт стоимости/спецпроходов — здесь.
 */
@Component
public class VariantGenerator {

    public static final int MAX_VARIANTS = 3;

    private final ForestResultBuilder resultBuilder;

    public VariantGenerator(ForestResultBuilder resultBuilder) {
        this.resultBuilder = resultBuilder;
    }

    public List<VariantResult> generate(NetworkDataset dataset, ObstacleIndex obstacleIndex,
                                        ExistingNetworkGraph graph, SpecialZoneIndex specialZones,
                                        List<String> warnings, Map<String, ConnectionExit> exits,
                                        TracingAlgorithm algorithm) {
        return generate(dataset, obstacleIndex, graph, specialZones, warnings, exits, algorithm,
                StageTrace.disabled());
    }

    public List<VariantResult> generate(NetworkDataset dataset, ObstacleIndex obstacleIndex,
                                        ExistingNetworkGraph graph, SpecialZoneIndex specialZones,
                                        List<String> warnings, Map<String, ConnectionExit> exits,
                                        TracingAlgorithm algorithm, StageTrace trace) {
        return generate(dataset, obstacleIndex, graph, specialZones, warnings, exits, algorithm,
                trace, CalculationMode.TWO_D);
    }

    public List<VariantResult> generate(NetworkDataset dataset, ObstacleIndex obstacleIndex,
                                        ExistingNetworkGraph graph, SpecialZoneIndex specialZones,
                                        List<String> warnings, Map<String, ConnectionExit> exits,
                                        TracingAlgorithm algorithm, StageTrace trace,
                                        CalculationMode mode) {
        List<VariantResult> variants = new ArrayList<>();
        for (ForestPlanningResult planning : algorithm.plan(dataset, graph, obstacleIndex,
                specialZones, warnings, exits, trace)) {
            if (variants.size() >= MAX_VARIANTS) {
                break;
            }
            variants.add(resultBuilder.build(planning, dataset, specialZones, obstacleIndex,
                    warnings, "v" + (variants.size() + 1), 0, planning.getPassNumber(), mode));
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
}
