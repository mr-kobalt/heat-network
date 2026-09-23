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
 * E26-04: A/B правил присоединения (10 м и учёт существующих примыканий) на
 * основном наборе. Включение правил меняет топологию; контроль — показатель,
 * длина, стоимость, неподключённые и число неразрешённых поворотов (FR-34,
 * открытый E23-04).
 */
@Tag("slow")
class ChamberTieInExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");

    @Test
    void chamberTieInRulesOn() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор недоступен");
        AppProperties properties = new AppProperties();
        properties.setForestChamberTieInRules(true);
        ObjectMapper mapper = new ObjectMapper();
        Path result = tempDir.resolve("tie-in-rules.geojson");
        CalculationOutcome outcome = service(properties).calculate(SAMPLE, result,
                tempDir.resolve("tie-in-rules.json"));
        int turns = countTurnViolations(result, mapper);
        long degreeWarnings = outcome.getWarnings().stream()
                .filter(warning -> warning.startsWith("FOREST_CHAMBER_DEGREE_EXCEEDED")).count();
        System.out.println("E26 on: score=" + outcome.getSummary().getScore()
                + " length=" + outcome.getSummary().getNewNetworkLengthM()
                + " cost=" + outcome.getSummary().getCalculatedCost()
                + " unconnected=" + outcome.getSummary().getUnconnectedOksIds().size()
                + " turnViolations=" + turns
                + " degreeWarnings=" + degreeWarnings);
        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getUnconnectedOksIds()).isEmpty();
        assertThat(turns).isZero();
    }
}
