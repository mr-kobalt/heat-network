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
 * R5b A/B: учёт предельной длины при подборе Ду в оценке хода relink
 * ({@code forest-relink-length-cost}). Сравнивает false и true на основном
 * наборе и наборе с препятствиями OSM.
 *
 * <p>Запуск: {@code mvn test -Dtest=RelinkLengthCostExperimentTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@Tag("slow")
class RelinkLengthCostExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path MAIN = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OSM = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void comparesLengthCost() throws Exception {
        assumeTrue(Files.exists(MAIN) && Files.exists(OSM), "наборы недоступны");
        for (Path sample : new Path[] {MAIN, OSM}) {
            for (boolean lengthCost : new boolean[] {false, true}) {
                run(sample.getFileName().toString(), sample, lengthCost);
            }
        }
    }

    private void run(String label, Path sample, boolean lengthCost) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestRelinkLengthCost(lengthCost);
        Path resultFile = tempDir.resolve(label + "-lc" + lengthCost + ".geojson");
        Path summaryFile = tempDir.resolve(label + "-lc" + lengthCost + "-summary.json");
        long start = System.nanoTime();
        CalculationOutcome outcome = service(properties).calculate(sample, resultFile, summaryFile);
        long totalMs = (System.nanoTime() - start) / 1_000_000L;
        JsonNode grid = new ObjectMapper().readTree(summaryFile.resolveSibling("grid.json").toFile());
        JsonNode pass = grid.path("passes").get(0);
        System.out.println("R5b lengthCost=" + lengthCost + " [" + label + "]"
                + " S=" + outcome.getSummary().getScore()
                + " L=" + outcome.getSummary().getNewNetworkLengthM()
                + " C=" + outcome.getSummary().getCalculatedCost()
                + " totalMs=" + totalMs
                + " relinkMs=" + pass.path("relinkMs").asLong()
                + " upsized=" + pass.path("relinkLengthUpsizedEdges").asLong()
                + " lengthMs=" + pass.path("relinkLengthMs").asLong());
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
    }
}
