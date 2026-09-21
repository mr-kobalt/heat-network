package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import ru.lct.heating.config.AppProperties;

/**
 * Свип мета-параметров алгоритма {@code grid-forest} на реальном наборе:
 * полный перебор (ячейка × проходы × угол × хранилище), сводный отчёт в
 * {@code target/algorithm-sweep/} (markdown/csv/json) и в консоль.
 *
 * <p>Полный Spring-контекст; PostGIS поднимается Testcontainers
 * ({@code postgis/postgis:16-3.4}), при недоступности Docker используется
 * внешняя БД из конфигурации (localhost). Запуск: {@code mvn test
 * -Dtest=AlgorithmParameterSweepTest -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@SpringBootTest
@Tag("slow")
class AlgorithmParameterSweepTest {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OUTPUT = Path.of("target", "algorithm-sweep");

    private static final double[] CELLS = {0.5, 1.0, 2.0, 3.0};
    private static final int[] ITERATIONS = {1, 2, 3};
    private static final double[] TURNS = {90.0, 180.0};
    private static final String[] STORAGES = {"memory", "postgis"};

    private static final PostgreSQLContainer<?> POSTGIS = startPostgis();

    private static PostgreSQLContainer<?> startPostgis() {
        try {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>(
                    DockerImageName.parse("postgis/postgis:16-3.4")
                            .asCompatibleSubstituteFor("postgres"))
                    .withDatabaseName("heating")
                    .withUsername("heating")
                    .withPassword("heating");
            container.start();
            return container;
        } catch (Throwable unavailable) {
            System.out.println("PostGIS Testcontainer недоступен, используется внешняя БД: "
                    + unavailable.getMessage());
            return null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        if (POSTGIS != null) {
            registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
            registry.add("spring.datasource.username", POSTGIS::getUsername);
            registry.add("spring.datasource.password", POSTGIS::getPassword);
        }
    }

    @Autowired
    private CalculationService calculationService;

    @Autowired
    private AppProperties properties;

    @Test
    void sweepsMetaParameters() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор " + SAMPLE + " недоступен");
        Files.createDirectories(OUTPUT);
        ObjectMapper mapper = new ObjectMapper();

        // Прогрев (вне отчёта).
        run(mapper, "warmup", 2.0, 3, 90.0, "memory");

        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (double cell : CELLS) {
            for (int iterations : ITERATIONS) {
                for (double turn : TURNS) {
                    for (String storage : STORAGES) {
                        rows.add(run(mapper, "run-" + index++, cell, iterations, turn, storage));
                    }
                }
            }
        }

        writeReports(rows, mapper);
        printTable(rows);
        System.out.println("Report: " + OUTPUT.toAbsolutePath());

        assertThat(rows).hasSize(CELLS.length * ITERATIONS.length * TURNS.length * STORAGES.length);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("error")).as("прогон %s", row.get("run")).isNull();
            assertThat((Integer) row.get("trees")).isGreaterThan(0);
            assertThat((Integer) row.get("unconnected")).isLessThanOrEqualTo(17);
        }
    }

    private Map<String, Object> run(ObjectMapper mapper, String label, double cell, int iterations,
                                    double turn, String storage) throws Exception {
        Path directory = OUTPUT.resolve(label);
        Files.createDirectories(directory);
        Path resultFile = directory.resolve("result.geojson");
        Path summaryFile = directory.resolve("summary.json");
        Path warningsFile = directory.resolve("warnings.json");

        properties.setForestGridCellM(cell);
        properties.setForestCostIterations(iterations);
        properties.setForestMaxTurnDeg(turn);
        properties.setForestGridStorage(storage);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run", label);
        row.put("cellM", cell);
        row.put("iterations", iterations);
        row.put("maxTurnDeg", turn);
        row.put("storage", storage);
        long start = System.nanoTime();
        try {
            CalculationOutcome outcome = calculationService.calculate(SAMPLE, resultFile,
                    summaryFile, "grid-forest", warningsFile);
            row.put("totalMs", (System.nanoTime() - start) / 1_000_000L);
            row.put("summaryPresent", outcome.getSummary() != null);
            readSummary(mapper, summaryFile, row);
            readGrid(mapper, summaryFile.resolveSibling("grid.json"), row);
            readWarnings(mapper, warningsFile, row);
            row.put("error", null);
        } catch (Exception exception) {
            row.put("totalMs", (System.nanoTime() - start) / 1_000_000L);
            row.put("error", exception.getClass().getSimpleName() + ": " + exception.getMessage());
        }
        return row;
    }

    private void readSummary(ObjectMapper mapper, Path summaryFile, Map<String, Object> row)
            throws Exception {
        JsonNode root = mapper.readTree(summaryFile.toFile());
        JsonNode best = root.isArray() && root.size() > 0 ? root.get(0) : null;
        if (best == null) {
            row.put("score", null);
            return;
        }
        row.put("score", best.path("score").asDouble());
        row.put("lengthM", best.path("newNetworkLengthM").asDouble());
        row.put("calculatedCost", best.path("calculatedCost").asLong());
        row.put("constructionCost", best.path("constructionCost").asLong());
        row.put("chamberCost", best.path("chamberConstructionCost").asLong());
        row.put("existingTieIns", best.path("existingChamberTieInCount").asInt());
        row.put("unconnectedPenalty", best.path("unconnectedPenalty").asLong());
    }

    private void readGrid(ObjectMapper mapper, Path gridFile, Map<String, Object> row)
            throws Exception {
        if (!Files.exists(gridFile)) {
            row.put("gridMs", null);
            return;
        }
        JsonNode grid = mapper.readTree(gridFile.toFile());
        row.put("gridMs", grid.path("timeMs").asLong());
        row.put("gridSize", grid.path("width").asInt() + "x" + grid.path("height").asInt());
        row.put("blockedCells", grid.path("blockedCells").asLong());
        row.put("gridStorage", grid.path("storage").asText());
        row.put("sources", grid.path("sources").asInt());
        row.put("terminals", grid.path("terminals").asInt());
        row.put("trees", grid.path("trees").asInt());
        row.put("unconnected", grid.path("unconnected").asInt());
        StringBuilder passes = new StringBuilder();
        for (JsonNode pass : grid.path("passes")) {
            if (passes.length() > 0) {
                passes.append(';');
            }
            passes.append(pass.path("score").asDouble()).append('@')
                    .append(pass.path("timeMs").asLong());
        }
        row.put("passes", passes.toString());
    }

    private void readWarnings(ObjectMapper mapper, Path warningsFile, Map<String, Object> row)
            throws Exception {
        if (!Files.exists(warningsFile)) {
            row.put("warnings", 0);
            row.put("warningCodes", "");
            return;
        }
        JsonNode warnings = mapper.readTree(warningsFile.toFile());
        Map<String, Integer> codes = new LinkedHashMap<>();
        for (JsonNode warning : warnings) {
            String text = warning.asText();
            int colon = text.indexOf(':');
            String code = colon > 0 ? text.substring(0, colon) : text;
            codes.merge(code, 1, Integer::sum);
        }
        row.put("warnings", warnings.size());
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, Integer> entry : codes.entrySet()) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        row.put("warningCodes", joined.toString());
    }

    private void writeReports(List<Map<String, Object>> rows, ObjectMapper mapper) throws Exception {
        String[] columns = {"run", "cellM", "iterations", "maxTurnDeg", "storage", "totalMs",
                "gridMs", "gridStorage", "gridSize", "blockedCells", "sources", "terminals",
                "trees", "unconnected", "score", "lengthM", "calculatedCost", "chamberCost",
                "existingTieIns", "unconnectedPenalty", "warnings", "warningCodes", "passes",
                "error"};
        StringBuilder markdown = new StringBuilder("# Свип параметров grid-forest\n\n");
        markdown.append("| ").append(String.join(" | ", columns)).append(" |\n");
        markdown.append('|').append("---|".repeat(columns.length)).append('\n');
        StringBuilder csv = new StringBuilder(String.join(",", columns)).append('\n');
        for (Map<String, Object> row : rows) {
            List<String> values = new ArrayList<>();
            for (String column : columns) {
                values.add(String.valueOf(row.get(column)));
            }
            markdown.append("| ").append(String.join(" | ", values)).append(" |\n");
            csv.append(String.join(",", values)).append('\n');
        }
        markdown.append("\n## Итоги\n\n");
        markdown.append(bestBy(rows, "score", true));
        markdown.append('\n');
        markdown.append(bestBy(rows, "totalMs", true));
        markdown.append('\n');
        Files.writeString(OUTPUT.resolve("report.md"), markdown.toString());
        Files.writeString(OUTPUT.resolve("report.csv"), csv.toString());
        mapper.writerWithDefaultPrettyPrinter().writeValue(OUTPUT.resolve("report.json").toFile(),
                rows);
    }

    private String bestBy(List<Map<String, Object>> rows, String key, boolean minimum) {
        Map<String, Object> best = null;
        double bestValue = minimum ? Double.POSITIVE_INFINITY : Double.NEGATIVE_INFINITY;
        for (Map<String, Object> row : rows) {
            Object value = row.get(key);
            if (!(value instanceof Number)) {
                continue;
            }
            double number = ((Number) value).doubleValue();
            if (minimum ? number < bestValue : number > bestValue) {
                bestValue = number;
                best = row;
            }
        }
        return best == null ? "нет данных" : ("- лучший `" + key + "`=" + bestValue
                + ": cell=" + best.get("cellM") + ", iter=" + best.get("iterations")
                + ", turn=" + best.get("maxTurnDeg") + ", storage=" + best.get("storage"));
    }

    private void printTable(List<Map<String, Object>> rows) {
        System.out.println("cell | iter | turn | storage | totalMs | score | length | trees | unconn | warn");
        for (Map<String, Object> row : rows) {
            System.out.printf("%s | %s | %s | %s | %s | %s | %s | %s | %s | %s%n",
                    row.get("cellM"), row.get("iterations"), row.get("maxTurnDeg"),
                    row.get("storage"), row.get("totalMs"), row.get("score"), row.get("lengthM"),
                    row.get("trees"), row.get("unconnected"), row.get("warnings"));
        }
    }
}
