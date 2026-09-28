package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

/**
 * E50: инварианты на наборе с препятствиями OSM — канонический выход каждого
 * варианта (в т.ч. точки 11) и запрет входа в собственный ОКС за пределами
 * финального вывода (пункты 2, 3, 5). Здесь же пинится OSM-baseline
 * (дефолт {@code forest-relink-tpoint-max=64} и полный перебор {@code =0}).
 * Счётчик {@code TURN_ANGLE_EXCEEDS_90} фиксируется фактом (маршрутные
 * нарушения ≤90°); угол между рёбрами в камере ограничен ≥30° отдельно.
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

    /**
     * Baseline E29/E50 на наборе OSM (дефолтные параметры). Углы: ≤90° —
     * маршрут/техузлы, ≥30° между парой рёбер в камере
     * ({@code forest-chamber-min-angle-deg}).
     */
    @Test
    void producesOsmBaselineWithDefaultParameters() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет с препятствиями OSM.geojson недоступен");
        Path resultFile = tempDir.resolve("osm-baseline-result.geojson");
        Path summaryFile = tempDir.resolve("osm-baseline-summary.json");

        CalculationOutcome outcome = service().calculate(SAMPLE, resultFile, summaryFile);
        long turns = outcome.getWarnings().stream()
                .filter(w -> w.startsWith("TURN_ANGLE_EXCEEDS_90")).count();

        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(outcome.getSummary().getScore())
                .isCloseTo(13.679614324110897, within(1e-6));
        assertThat(outcome.getSummary().getNewNetworkLengthM())
                .isCloseTo(1970.8629960369658, within(1e-3));
        assertThat(outcome.getSummary().getCalculatedCost()).isEqualTo(277393762L);
        assertThat(outcome.getSummary().getChamberConstructionCost()).isEqualTo(50000000L);
        assertThat(outcome.getSummary().getExistingChamberTieInCount()).isZero();
        assertThat(outcome.getSummary().getExistingChamberTieInCost()).isZero();
        assertThat(turns).isEqualTo(0L);
    }

    /**
     * Baseline OSM с полным перебором T-точек ({@code forest-relink-tpoint-max=0}).
     */
    @Test
    void producesOsmBaselineWithUnlimitedTpoints() throws Exception {
        assumeTrue(Files.exists(SAMPLE),
                "Набор source/Датасет с препятствиями OSM.geojson недоступен");
        AppProperties properties = new AppProperties();
        properties.setForestRelinkTpointMax(0);
        Path resultFile = tempDir.resolve("osm-tpoint0-result.geojson");
        Path summaryFile = tempDir.resolve("osm-tpoint0-summary.json");

        CalculationOutcome outcome = service(properties).calculate(SAMPLE, resultFile, summaryFile);
        long turns = outcome.getWarnings().stream()
                .filter(w -> w.startsWith("TURN_ANGLE_EXCEEDS_90")).count();

        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(outcome.getSummary().getScore())
                .isCloseTo(13.682035191484873, within(1e-6));
        assertThat(outcome.getSummary().getNewNetworkLengthM())
                .isCloseTo(1969.6071824949581, within(1e-3));
        assertThat(outcome.getSummary().getCalculatedCost()).isEqualTo(277614773L);
        assertThat(outcome.getSummary().getChamberConstructionCost()).isEqualTo(50000000L);
        assertThat(outcome.getSummary().getExistingChamberTieInCount()).isZero();
        assertThat(outcome.getSummary().getExistingChamberTieInCost()).isZero();
        assertThat(turns).isEqualTo(0L);
    }
}
