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
 * E25-08: A/B строгого обхода спецпроходов на наборе с препятствиями OSM
 * (`source/Датасет с препятствиями OSM.geojson`). Сравнивает strict=off/on по
 * показателю, длине, стоимости и числу неподключённых; проверяет инварианты.
 */
@Tag("slow")
class SpecialRoutingExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void strictOff() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор E29 не сгенерирован");
        AppProperties properties = new AppProperties();
        properties.setForestSpecialStrict(false);
        ObjectMapper mapper = new ObjectMapper();
        Path result = tempDir.resolve("strict-off.geojson");
        CalculationOutcome outcome = service(properties).calculate(SAMPLE, result,
                tempDir.resolve("off.json"));
        int selfIntersections = countSelfIntersections(result, mapper);
        System.out.println("SPECIAL off: score=" + outcome.getSummary().getScore()
                + " length=" + outcome.getSummary().getNewNetworkLengthM()
                + " cost=" + outcome.getSummary().getCalculatedCost()
                + " unconnected=" + outcome.getSummary().getUnconnectedOksIds().size()
                + " selfIntersections=" + selfIntersections);
        assertThat(outcome.getSummary()).isNotNull();
        // strict=off — не дефолтный диагностический режим; FR-29 здесь не
        // гарантируется (в дефолтном strict — 0).
        assertNoOksCrossingBeyondApproach(SAMPLE, result, mapper);
    }

    @Test
    void diameterAware() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор E29 не сгенерирован");
        AppProperties properties = new AppProperties();
        properties.setForestDiameterAwareBuffers(true);
        ObjectMapper mapper = new ObjectMapper();
        Path result = tempDir.resolve("diameter-aware.geojson");
        CalculationOutcome outcome = service(properties).calculate(SAMPLE, result,
                tempDir.resolve("diameter.json"));
        int selfIntersections = countSelfIntersections(result, mapper);
        System.out.println("SPECIAL diameter-aware: score=" + outcome.getSummary().getScore()
                + " length=" + outcome.getSummary().getNewNetworkLengthM()
                + " cost=" + outcome.getSummary().getCalculatedCost()
                + " unconnected=" + outcome.getSummary().getUnconnectedOksIds().size()
                + " selfIntersections=" + selfIntersections);
        assertThat(outcome.getSummary()).isNotNull();
        assertThat(selfIntersections).isZero();
        assertNoOksCrossingBeyondApproach(SAMPLE, result, mapper);
    }

    @Test
    void strictOn() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор E29 не сгенерирован");
        AppProperties properties = new AppProperties();
        properties.setForestSpecialStrict(true);
        ObjectMapper mapper = new ObjectMapper();
        Path result = tempDir.resolve("strict-on.geojson");
        CalculationOutcome outcome = service(properties).calculate(SAMPLE, result,
                tempDir.resolve("on.json"));
        int selfIntersections = countSelfIntersections(result, mapper);
        System.out.println("SPECIAL on: score=" + outcome.getSummary().getScore()
                + " length=" + outcome.getSummary().getNewNetworkLengthM()
                + " cost=" + outcome.getSummary().getCalculatedCost()
                + " unconnected=" + outcome.getSummary().getUnconnectedOksIds().size()
                + " selfIntersections=" + selfIntersections
                + " warnings=" + outcome.getWarnings().size());
        assertThat(outcome.getSummary()).isNotNull();
        assertThat(selfIntersections).isZero();
        assertNoOksCrossingBeyondApproach(SAMPLE, result, mapper);
    }
}
