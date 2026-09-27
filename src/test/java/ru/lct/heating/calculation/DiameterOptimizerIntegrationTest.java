package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;

/**
 * ADR-0062: при связывающей предельной длине финальный глобальный подбор Ду
 * включается и не ухудшает стоимость; инварианты сохраняются. Длины уменьшены,
 * чтобы предел реально ограничивал (на реальных наборах он не связывает).
 */
class DiameterOptimizerIntegrationTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("src", "test", "resources", "datasets",
            "pipeline-small.geojson");

    @Override
    protected HeatingTablesProperties diameters() {
        HeatingTablesProperties properties = super.diameters();
        for (DiameterRow row : properties.getDiameters()) {
            row.setMaxLengthM(row.getMaxLengthM() * 0.2);
        }
        return properties;
    }

    @Test
    void optimizerDoesNotWorsenCostWhenLengthBinds() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Фикстур pipeline-small.geojson недоступен");
        ObjectMapper mapper = new ObjectMapper();

        Path offResult = tempDir.resolve("dn-off.geojson");
        AppProperties offProperties = permissiveExitProperties();
        offProperties.setForestDiameterOptimizer(false);
        CalculationOutcome off = service(offProperties)
                .calculate(SAMPLE, offResult, tempDir.resolve("dn-off.json"));

        AppProperties properties = permissiveExitProperties();
        properties.setForestDiameterOptimizer(true);
        Path onResult = tempDir.resolve("dn-on.geojson");
        CalculationOutcome on = service(properties)
                .calculate(SAMPLE, onResult, tempDir.resolve("dn-on.json"));

        assertThat(on.getSummary()).isNotNull();
        assertThat(on.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(on.getSummary().getCalculatedCost())
                .isLessThanOrEqualTo(off.getSummary().getCalculatedCost());
        assertThat(countTurnViolations(onResult, mapper)).isZero();
        assertThat(countSelfIntersections(onResult, mapper)).isZero();
        assertConnectionPointsAreLeaves(SAMPLE, onResult, mapper);
        assertBranchOnlyInChambers(onResult, mapper);
        System.out.printf("DIAMOND-INT off S=%.6f C=%d | on S=%.6f C=%d%n",
                off.getSummary().getScore(), off.getSummary().getCalculatedCost(),
                on.getSummary().getScore(), on.getSummary().getCalculatedCost());
    }
}
