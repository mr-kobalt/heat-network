package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * E50: инварианты на наборе с препятствиями OSM — канонический выход каждого
 * варианта (в т.ч. точки 11) и запрет входа в собственный ОКС за пределами
 * финального вывода (пункты 2, 3, 5). Повороты здесь не проверяются: на этом
 * наборе остаётся одно унаследованное нарушение {@code >90°} вне рамок E50.
 */
@Tag("slow")
class GridForestOsmInvariantsSlowTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void checksCanonicalExitsAndOwnOksOnOsm() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет с препятствиями OSM.geojson недоступен");
        Path resultFile = tempDir.resolve("osm-invariants-result.geojson");
        Path summaryFile = tempDir.resolve("osm-invariants-summary.json");
        ObjectMapper mapper = new ObjectMapper();

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile);

        int exitMismatches = countMissingCanonicalExits(SAMPLE, resultFile, mapper);
        int selfIntersections = countSelfIntersections(resultFile, mapper);
        System.out.println("OSM INVARIANTS unconnected="
                + outcome.getSummary().getUnconnectedOksIds().size()
                + " exitMismatches=" + exitMismatches
                + " selfIntersections=" + selfIntersections);
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(exitMismatches).isZero();
        assertThat(selfIntersections).isZero();
        assertNoOksCrossingBeyondApproach(SAMPLE, resultFile, mapper);
    }
}
