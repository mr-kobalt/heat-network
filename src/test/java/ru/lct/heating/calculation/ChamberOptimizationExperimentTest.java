package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

/**
 * ADR-0051: A/B оптимизации положения новых камер (медиана соседей + прямая
 * перепрокладка стыков). Проверяет, что включение не ухудшает показатель и не
 * нарушает обязательные инварианты (FR-26/FR-29/FR-34).
 */
@Tag("slow")
class ChamberOptimizationExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");

    private CalculationOutcome run(boolean chamberOptimization, String name) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestChamberOptimization(chamberOptimization);
        Path result = tempDir.resolve(name + ".geojson");
        CalculationOutcome outcome = service(properties).calculate(SAMPLE, result,
                tempDir.resolve(name + ".json"));
        assertThat(outcome.getSummary()).isNotNull();
        ObjectMapper mapper = new ObjectMapper();
        int selfIntersections = countSelfIntersections(result, mapper);
        int turnViolations = countTurnViolations(result, mapper);
        int degreeViolations = countChamberDegreeViolations(result, mapper);
        System.out.println("CHAMBER " + name + ": score=" + outcome.getSummary().getScore()
                + " length=" + outcome.getSummary().getNewNetworkLengthM()
                + " unconnected=" + outcome.getSummary().getUnconnectedOksIds().size()
                + " selfIntersections=" + selfIntersections
                + " turnViolations=" + turnViolations
                + " degreeViolations=" + degreeViolations);
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(selfIntersections).isZero();
        assertThat(turnViolations).isZero();
        assertThat(degreeViolations).isZero();
        return outcome;
    }

    @Test
    void chamberOptimizationImprovesCostWithoutViolations() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор недоступен");
        CalculationOutcome off = run(false, "chamber-off");
        CalculationOutcome on = run(true, "chamber-on");
        assertThat(on.getSummary().getScore())
                .as("включение оптимизации камер не ухудшает S")
                .isLessThanOrEqualTo(off.getSummary().getScore() + 1e-9);
        assertThat(on.getSummary().getNewNetworkLengthM())
                .as("длина не увеличивается")
                .isLessThanOrEqualTo(off.getSummary().getNewNetworkLengthM() + 1e-3);
        assertThat(on.getSummary().getCalculatedCost())
                .isLessThanOrEqualTo(off.getSummary().getCalculatedCost());
    }
}
