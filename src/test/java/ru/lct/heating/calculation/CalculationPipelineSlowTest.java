package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Полный сквозной прогон на реальном наборе (алгоритм {@code grid-forest}).
 * Тег slow — исключён из стандартного {@code mvn test}; запуск:
 * {@code mvn test -Dsurefire.excludedGroups= -Dgroups=slow}.
 */
@Tag("slow")
class CalculationPipelineSlowTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");

    @Test
    void producesGridForestResultOnCorrectedDataset() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет скорректированный.geojson недоступен");
        assertThat(Files.size(SAMPLE)).isGreaterThan(0);

        ObjectMapper objectMapper = new ObjectMapper();
        Path resultFile = tempDir.resolve("result.geojson");
        Path summaryFile = tempDir.resolve("summary.json");

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile);

        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(outcome.getSummary().getScore()).isGreaterThan(0.0);
        assertNodeReferencesMatchGeometry(SAMPLE, resultFile, objectMapper);
        assertNoOksCrossingBeyondApproach(SAMPLE, resultFile, objectMapper);
        assertConnectionPointsAreLeaves(SAMPLE, resultFile, objectMapper);
        assertBranchOnlyInChambers(resultFile, objectMapper);
    }

    /**
     * Регресс рабочих дефолтов (ADR-0034, baseline run-28): cell=2.0,
     * cost-iterations=2, turn=90, storage=auto.
     */
    @Test
    void producesRun28BaselineWithDefaultParameters() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет скорректированный.geojson недоступен");

        Path resultFile = tempDir.resolve("baseline-result.geojson");
        Path summaryFile = tempDir.resolve("baseline-summary.json");

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile);

        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(outcome.getSummary().getScore()).isCloseTo(13.761880820008793, within(1e-6));
        assertThat(outcome.getSummary().getNewNetworkLengthM())
                .isCloseTo(1906.0125240029313, within(1e-3));
        assertThat(outcome.getSummary().getCalculatedCost()).isEqualTo(287280116L);
        assertThat(outcome.getSummary().getChamberConstructionCost()).isEqualTo(68000000L);
    }
}
