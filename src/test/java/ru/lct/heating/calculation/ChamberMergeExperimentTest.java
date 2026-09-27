package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

/**
 * E50-05 A/B: объединение близких новых камер ({@code forest-chamber-merge}) и
 * режим {@code forest-chamber-merge-repair} (ремонт углов на кандидата vs
 * валидация без ремонта). Сравнивает: merge off; merge+repair; merge без repair
 * на основном наборе и наборе с препятствиями OSM.
 *
 * <p>Запуск: {@code mvn test -Dtest=ChamberMergeExperimentTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@Tag("slow")
class ChamberMergeExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path MAIN = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OSM = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void comparesChamberMerge() throws Exception {
        assumeTrue(Files.exists(MAIN) && Files.exists(OSM), "наборы недоступны");
        for (Path sample : new Path[] {MAIN, OSM}) {
            run(sample.getFileName().toString(), sample, "off", false, true);
            run(sample.getFileName().toString(), sample, "repair", true, true);
            run(sample.getFileName().toString(), sample, "norepair", true, false);
        }
    }

    private void run(String label, Path sample, String mode, boolean merge, boolean repair)
            throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestChamberMerge(merge);
        properties.setForestChamberMergeRepair(repair);
        Path resultFile = tempDir.resolve(label + "-" + mode + ".geojson");
        Path summaryFile = tempDir.resolve(label + "-" + mode + "-summary.json");
        long start = System.nanoTime();
        CalculationOutcome outcome = service(properties).calculate(sample, resultFile, summaryFile);
        long totalMs = (System.nanoTime() - start) / 1_000_000L;
        System.out.println("CHAMBER_MERGE mode=" + mode + " [" + label + "]"
                + " S=" + outcome.getSummary().getScore()
                + " L=" + outcome.getSummary().getNewNetworkLengthM()
                + " C=" + outcome.getSummary().getCalculatedCost()
                + " chamberCost=" + outcome.getSummary().getChamberConstructionCost()
                + " totalMs=" + totalMs
                + " warnings=" + outcome.getWarnings().size());
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
    }
}
