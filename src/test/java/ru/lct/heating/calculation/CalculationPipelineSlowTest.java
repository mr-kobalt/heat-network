package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

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
     * Регресс рабочих дефолтов (ADR-0034/0035): cell=2.0, cost-iterations=2,
     * turn=90, storage=auto, локальный ремонт поворотов, grid-заход на выход,
     * выбор клетки входа терминала {@code nearest}, граница ОКС.
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
        // ADR-0037: буферы по мин. Ду, выход — ближайшая точка на внешнем контуре
        // буфера всего ОКС (с учётом узких промежутков и достижимости), кандидаты 1 м.
        // ADR-0038: уточнение геометрии (refine) после переприсоединения.
        // ADR-0040: фильтрация выходов. ADR-0041: гекс-сетка. Дефолт ячейки — 1 м;
        // ADR-0039: по умолчанию relink НЕ меняет выход growth —
        // baseline пересчитан (S 12.973 → 14.321, длина 1836.5 → 2071.8 м,
        // стоимость 266.5 → 289.5 млн, камеры 58 млн).
        assertThat(outcome.getSummary().getScore()).isCloseTo(14.320729670783953, within(1e-6));
        assertThat(outcome.getSummary().getNewNetworkLengthM())
                .isCloseTo(2071.7759542613176, within(1e-3));
        assertThat(outcome.getSummary().getCalculatedCost()).isEqualTo(289478636L);
        assertThat(outcome.getSummary().getChamberConstructionCost()).isEqualTo(58000000L);
        assertThat(outcome.getWarnings().stream()
                .filter(warning -> warning.startsWith("TURN_ANGLE_EXCEEDS_90")).count())
                .isLessThanOrEqualTo(6L);
        assertNoExcessiveVertices(resultFile, new ObjectMapper(), 12);
        // ADR-0035/0037: точка 6 присоединена коротким ребром, а не через ствол.
        assertEdgeAtPointShorterThan(resultFile, new ObjectMapper(), 37.632012226474316,
                55.700048261255056, 60.0);
    }

    /**
     * ADR-0039: при включённом переназначении выхода relink консолидирует точки
     * 3, 6, 8 на одной камере (опция, не дефолт).
     */
    @Test
    void relinkExitRelocation_consolidatesPoints368() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет скорректированный.geojson недоступен");

        AppProperties properties = new AppProperties();
        properties.setForestRelinkExitRelocation(true);
        Path resultFile = tempDir.resolve("reloc-result.geojson");
        Path summaryFile = tempDir.resolve("reloc-summary.json");

        CalculationOutcome outcome = service(properties).calculate(SAMPLE, resultFile, summaryFile);

        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertConnectionPointsShareChamber(resultFile, new ObjectMapper(), "3", "6", "8");
    }

    /** ADR-0036: трассировка этапов на реальном наборе даёт валидные артефакты. */
    @Test
    void writesTraceStagesOnCorrectedDataset() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет скорректированный.geojson недоступен");

        ObjectMapper objectMapper = new ObjectMapper();
        Path resultFile = tempDir.resolve("trace-result.geojson");
        Path summaryFile = tempDir.resolve("trace-summary.json");
        Path stagesDir = tempDir.resolve("stages");

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile, null,
                null, stagesDir);

        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();

        JsonNode manifest = objectMapper.readTree(stagesDir.resolve("manifest.json").toFile());
        assertThat(manifest.path("bestPass").asInt()).isGreaterThanOrEqualTo(1);
        JsonNode trees = manifest.path("stages").get(5);
        assertThat(trees.path("id").asText()).isEqualTo("trees");
        assertThat(trees.path("passes").size()).isGreaterThanOrEqualTo(1);

        JsonNode grid = objectMapper.readTree(stagesDir.resolve("grid.json").toFile());
        assertThat(grid.path("imageWidth").asInt()).isGreaterThan(0);
        assertThat(grid.path("imageHeight").asInt()).isGreaterThan(0);
        assertThat(grid.path("blocked").asText()).isNotEmpty();
        assertThat(grid.path("boundsWgs84").size()).isEqualTo(4);
        assertThat(grid.path("sources").size()).isGreaterThan(0);
        assertThat(grid.path("terminalCells").size()).isGreaterThan(0);

        JsonNode network = objectMapper.readTree(stagesDir.resolve("network.geojson").toFile());
        assertThat(network.path("features").size()).isGreaterThan(0);
        JsonNode refine = objectMapper.readTree(stagesDir.resolve("refine.geojson").toFile());
        assertThat(refine.path("features").size()).isGreaterThan(0);
        assertThat(Files.exists(stagesDir.resolve("relink.geojson"))).isTrue();
    }
}
