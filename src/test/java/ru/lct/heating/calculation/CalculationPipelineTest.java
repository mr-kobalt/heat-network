package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Быстрый сквозной тест конвейера на маленьком фикстуре (алгоритм по умолчанию
 * {@code grid-forest}). Полный набор — {@link CalculationPipelineSlowTest} с
 * тегом slow.
 */
class CalculationPipelineTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("src", "test", "resources", "datasets",
            "pipeline-small.geojson");

    @Test
    void producesResultOnSmallFixture() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Фикстур pipeline-small.geojson недоступен");
        assertThat(SAMPLE.toFile().length()).isGreaterThan(0);

        ObjectMapper objectMapper = new ObjectMapper();
        Path resultFile = tempDir.resolve("result.geojson");
        Path summaryFile = tempDir.resolve("summary.json");

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile);

        assertGridForestResult(resultFile, outcome, objectMapper);
    }

    @Test
    void writesStageFilesWhenTraceEnabled() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Фикстур pipeline-small.geojson недоступен");

        Path resultFile = tempDir.resolve("traced-result.geojson");
        Path summaryFile = tempDir.resolve("traced-summary.json");
        Path stagesDir = tempDir.resolve("stages");

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile, null,
                null, stagesDir);

        assertThat(outcome.getSummary()).isNotNull();
        assertThat(Files.exists(stagesDir.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(stagesDir.resolve("grid.json"))).isTrue();
        assertThat(Files.exists(stagesDir.resolve("network.geojson"))).isTrue();
        assertThat(Files.exists(stagesDir.resolve("restrictions.geojson"))).isTrue();
        assertThat(Files.exists(stagesDir.resolve("exits.geojson"))).isTrue();
        assertThat(Files.exists(stagesDir.resolve("trees-1.geojson"))).isTrue();
        assertThat(Files.exists(stagesDir.resolve("refine.geojson"))).isTrue();
        assertThat(Files.exists(stagesDir.resolve("relink.geojson"))).isTrue();

        JsonNode manifest = new ObjectMapper().readTree(stagesDir.resolve("manifest.json").toFile());
        assertThat(manifest.path("algorithm").asText()).isEqualTo("grid-forest");
        assertThat(manifest.path("bestPass").asInt()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void producesResultWithExplicitGridForestOnSmallFixture() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Фикстур pipeline-small.geojson недоступен");

        ObjectMapper objectMapper = new ObjectMapper();
        Path resultFile = tempDir.resolve("result-grid.geojson");
        Path summaryFile = tempDir.resolve("summary-grid.json");

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile,
                "grid-forest");

        assertGridForestResult(resultFile, outcome, objectMapper);
    }

    private void assertGridForestResult(Path resultFile, CalculationOutcome outcome,
                                        ObjectMapper objectMapper) throws Exception {
        assertThat(Files.exists(resultFile)).isTrue();
        assertThat(Files.size(resultFile)).isGreaterThan(0);
        assertThat(Files.exists(resultFile.resolveSibling("grid.json"))).isTrue();
        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getScore()).isGreaterThan(0.0);
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        String result = Files.readString(resultFile);
        assertThat(result).contains("\"object_type\":\"variant_summary\"");
        assertThat(result).contains("\"variant_id\":\"v1\"");
        assertThat(result).doesNotContain("\"object_type\":\"tie_in\"");
        assertNodeReferencesMatchGeometry(SAMPLE, resultFile, objectMapper);
        assertNoChamberAtConnectionPoint(SAMPLE, resultFile, objectMapper);
        assertNoOksCrossingBeyondApproach(SAMPLE, resultFile, objectMapper);
        assertConnectionPointsAreLeaves(SAMPLE, resultFile, objectMapper);
        assertBranchOnlyInChambers(resultFile, objectMapper);
    }

    /**
     * FR-25: тепловая камера не может располагаться в точке подключения ОКС.
     */
    private void assertNoChamberAtConnectionPoint(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        Set<String> oksIds = new HashSet<>();
        for (JsonNode feature : mapper.readTree(input.toFile()).path("features")) {
            if ("oks_connection_point".equals(
                    feature.path("properties").path("object_type").asText())) {
                oksIds.add(feature.path("properties").path("id").asText());
            }
        }
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            if ("heat_chamber".equals(feature.path("properties").path("object_type").asText())) {
                assertThat(oksIds)
                        .as("камера %s не должна совпадать с точкой подключения",
                                feature.path("properties").path("id").asText())
                        .doesNotContain(feature.path("properties").path("id").asText());
            }
        }
    }
}
