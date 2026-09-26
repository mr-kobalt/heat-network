package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

/**
 * R5a A/B: учёт {@code Kспец} в оценке хода relink
 * ({@code forest-relink-special-cost}). Сравнивает false (прежняя прокси-оценка)
 * и true на основном наборе и наборе с препятствиями OSM.
 *
 * <p>Запуск: {@code mvn test -Dtest=RelinkSpecialCostExperimentTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@Tag("slow")
class RelinkSpecialCostExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path MAIN = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OSM = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void comparesSpecialCost() throws Exception {
        assumeTrue(Files.exists(MAIN) && Files.exists(OSM), "наборы недоступны");
        for (Path sample : new Path[] {MAIN, OSM}) {
            for (boolean specialCost : new boolean[] {false, true}) {
                run(sample.getFileName().toString(), sample, specialCost);
            }
        }
    }

    private void run(String label, Path sample, boolean specialCost) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestRelinkSpecialCost(specialCost);
        Path resultFile = tempDir.resolve(label + "-sc" + specialCost + ".geojson");
        Path summaryFile = tempDir.resolve(label + "-sc" + specialCost + "-summary.json");
        long start = System.nanoTime();
        CalculationOutcome outcome = service(properties).calculate(sample, resultFile, summaryFile);
        long totalMs = (System.nanoTime() - start) / 1_000_000L;
        JsonNode grid = new ObjectMapper().readTree(summaryFile.resolveSibling("grid.json").toFile());
        JsonNode pass = grid.path("passes").get(0);
        System.out.println("R5a specialCost=" + specialCost + " [" + label + "]"
                + " S=" + outcome.getSummary().getScore()
                + " L=" + outcome.getSummary().getNewNetworkLengthM()
                + " C=" + outcome.getSummary().getCalculatedCost()
                + " totalMs=" + totalMs
                + " relinkMs=" + pass.path("relinkMs").asLong()
                + " kSpecialCalls=" + pass.path("relinkKSpecialCalls").asLong()
                + " kSpecialMs=" + pass.path("relinkKSpecialMs").asLong());
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
    }
}
