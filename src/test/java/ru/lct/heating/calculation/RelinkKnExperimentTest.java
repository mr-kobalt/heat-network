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
 * R3 A/B: kNN-отбор кандидатов relink. Сравнивает
 * {@code forest-relink-candidate-k} = 0 (полный перебор), 8, 16, 32, 64 на
 * основном наборе и наборе с препятствиями OSM.
 *
 * <p>Запуск: {@code mvn test -Dtest=RelinkKnExperimentTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@Tag("slow")
class RelinkKnExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path MAIN = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OSM = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void comparesCandidateK() throws Exception {
        assumeTrue(Files.exists(MAIN) && Files.exists(OSM), "наборы недоступны");
        for (Path sample : new Path[] {MAIN, OSM}) {
            for (int k : new int[] {0, 8, 16, 32, 64}) {
                run(sample.getFileName().toString(), sample, k);
            }
        }
    }

    private void run(String label, Path sample, int k) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestRelinkCandidateK(k);
        Path resultFile = tempDir.resolve(label + "-k" + k + ".geojson");
        Path summaryFile = tempDir.resolve(label + "-k" + k + "-summary.json");
        long start = System.nanoTime();
        CalculationOutcome outcome = service(properties).calculate(sample, resultFile, summaryFile);
        long totalMs = (System.nanoTime() - start) / 1_000_000L;
        JsonNode grid = new ObjectMapper().readTree(summaryFile.resolveSibling("grid.json").toFile());
        JsonNode pass = grid.path("passes").get(0);
        System.out.println("R3 k=" + k + " [" + label + "]"
                + " S=" + outcome.getSummary().getScore()
                + " L=" + outcome.getSummary().getNewNetworkLengthM()
                + " totalMs=" + totalMs
                + " relinkMs=" + pass.path("relinkMs").asLong()
                + " nodes=" + pass.path("relinkCandidateNodes").asLong()
                + " edges=" + pass.path("relinkCandidateEdges").asLong()
                + " tpoints=" + pass.path("relinkTpoints").asLong()
                + " validSeg=" + pass.path("relinkValidSegmentCalls").asLong()
                + " rebuilds=" + pass.path("relinkRebuildCalls").asLong()
                + " segMs=" + pass.path("relinkValidSegmentMs").asLong()
                + " rebMs=" + pass.path("relinkRebuildMs").asLong()
                + " idxMs=" + pass.path("relinkIndexBuildMs").asLong());
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
    }
}
