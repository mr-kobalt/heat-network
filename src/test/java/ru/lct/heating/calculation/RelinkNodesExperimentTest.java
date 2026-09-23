package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

/**
 * Эксперимент ADR-0044: перестройка дерева в relink (перенос узлов с
 * поддеревом). Сравнивает `forest-relink-nodes=false` (дефолт) и `true`,
 * отчёт — {@code target/relink-nodes/report.md}.
 *
 * <p>Запуск: {@code mvn test -Dtest=RelinkNodesExperimentTest
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@Tag("slow")
class RelinkNodesExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OUTPUT = Path.of("target", "relink-nodes");

    @Test
    void comparesNodeRelocation() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор " + SAMPLE + " недоступен");
        Files.createDirectories(OUTPUT);
        Map<String, Object> off = run("off", false);
        Map<String, Object> on = run("on", true);
        System.out.println("RELINK_NODES off: " + off);
        System.out.println("RELINK_NODES on:  " + on);
        writeReport(List.of(off, on));
        assertThat(off.get("error")).isNull();
        assertThat(on.get("error")).isNull();
    }

    private Map<String, Object> run(String label, boolean enabled) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestRelinkNodes(enabled);
        Path dir = OUTPUT.resolve(label);
        Files.createDirectories(dir);
        Path resultFile = dir.resolve("result.geojson");
        Path summaryFile = dir.resolve("summary.json");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("relinkNodes", enabled);
        long start = System.nanoTime();
        try {
            CalculationOutcome outcome = service(properties).calculate(SAMPLE, resultFile, summaryFile);
            row.put("timeMs", (System.nanoTime() - start) / 1_000_000L);
            row.put("score", outcome.getSummary().getScore());
            row.put("lengthM", outcome.getSummary().getNewNetworkLengthM());
            row.put("calculatedCost", outcome.getSummary().getCalculatedCost());
            row.put("chamberCost", outcome.getSummary().getChamberConstructionCost());
            row.put("unconnected", outcome.getSummary().getUnconnectedOksIds().size());
            row.put("turnWarnings", count(outcome.getWarnings(), "TURN_ANGLE_EXCEEDS_90"));
            row.put("unresolvedTurns", count(outcome.getWarnings(), "FOREST_TURN_UNRESOLVED"));
            row.put("error", null);
        } catch (Exception exception) {
            row.put("timeMs", (System.nanoTime() - start) / 1_000_000L);
            row.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return row;
    }

    private int count(List<String> warnings, String prefix) {
        int count = 0;
        for (String warning : warnings) {
            if (warning.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private void writeReport(List<Map<String, Object>> rows) throws Exception {
        StringBuilder report = new StringBuilder("# Эксперимент relink: перенос узлов (ADR-0044)\n\n");
        report.append("| Метрика | off | on |\n|---|---|---|\n");
        for (String key : List.of("score", "lengthM", "calculatedCost", "chamberCost",
                "unconnected", "turnWarnings", "unresolvedTurns", "timeMs")) {
            report.append("| ").append(key).append(" |");
            for (Map<String, Object> row : rows) {
                report.append(' ').append(row.getOrDefault(key, "-")).append(" |");
            }
            report.append('\n');
        }
        Files.writeString(OUTPUT.resolve("report.md"), report.toString());
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(OUTPUT.resolve("report.json").toFile(), rows);
    }
}
