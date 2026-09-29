package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E8: диагностика разбивки времени проходов {@code grid-forest} на контрольных
 * наборах. Тег slow — запуск:
 * {@code mvn test -Dsurefire.excludedGroups= -Dgroups=slow -Dtest=PerformanceProfileSlowTest}.
 * Печатает таблицу {@code dijkstra/extract/refine} и число клеток, чтобы
 * оптимизации измерялись, а не угадывались (ADR-0049).
 */
@Tag("slow")
class PerformanceProfileSlowTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OSM = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void profilesCorrectedDataset() throws Exception {
        profile("корректированный", SAMPLE);
    }

    @Test
    void profilesOsmDataset() throws Exception {
        profile("OSM", OSM);
    }

    private void profile(String label, Path input) throws Exception {
        assumeTrue(Files.exists(input), "Набор " + input + " недоступен");

        Path summaryFile = tempDir.resolve(label.replace(' ', '-') + "-summary.json");
        Path resultFile = tempDir.resolve(label.replace(' ', '-') + "-result.geojson");

        long start = System.nanoTime();
        CalculationOutcome outcome = service().calculate(input, resultFile, summaryFile);
        long wallMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(outcome.getSummary()).isNotNull();

        JsonNode grid = new ObjectMapper().readTree(
                summaryFile.resolveSibling("grid.json").toFile());
        System.out.printf("%n=== Профиль [%s] wall=%d ms, grid=%s ms, cell=%s, %sx%s, "
                        + "blocked=%s, trees=%s ===%n",
                label, wallMs, grid.path("timeMs").asLong(), grid.path("cellM").asDouble(),
                grid.path("width").asInt(), grid.path("height").asInt(),
                grid.path("blockedCells").asLong(), grid.path("trees").asInt());
        for (JsonNode pass : grid.path("passes")) {
            System.out.printf("  pass %d: total=%d ms | dijkstra=%d extract=%d relink=%d refine=%d"
                            + " | settled=%d heapPushes=%d | score=%.5f%n",
                    pass.path("index").asInt(), pass.path("timeMs").asLong(),
                    pass.path("dijkstraMs").asLong(), pass.path("extractMs").asLong(),
                    pass.path("relinkMs").asLong(), pass.path("refineMs").asLong(),
                    pass.path("dijkstraSettled").asLong(), pass.path("heapPushes").asLong(),
                    pass.path("score").asDouble());
        }

        assertThat(grid.path("timeMs").asLong()).isPositive();
        for (JsonNode pass : grid.path("passes")) {
            assertThat(pass.path("dijkstraMs").asLong()).isNotNegative();
            assertThat(pass.path("extractMs").asLong()).isNotNegative();
            assertThat(pass.path("refineMs").asLong()).isNotNegative();
            assertThat(pass.path("dijkstraSettled").asLong()).isNotNegative();
        }
    }
}
