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
 * Свип v2 (ADR-0035): полный перебор поведенческих параметров присоединения
 * терминалов и сетки на реальном наборе, угол поворота фиксирован 90°.
 *
 * <p>Двухстадийный: стадия 1 — поведенческие флаги (96), подсвип
 * {@code entryCells}, стадия 2 — сетка ({@code cell × iterations × storage}).
 * Отчёты — в {@code target/algorithm-sweep-2/} (старый свип не затирается).</p>
 *
 * <p>Запуск: {@code mvn test -Dtest=AlgorithmParameterSweepV2Test
 * -Dsurefire.excludedGroups= -Dgroups=slow}.</p>
 */
@SpringBootTest
@Tag("slow")
class AlgorithmParameterSweepV2Test {

    private static final Path SAMPLE = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OUTPUT = Path.of("target", "algorithm-sweep-2");
    private static final double[] POINT6 = {37.632012226474316, 55.700048261255056};

    private static final boolean[] BOOLS = {false, true};
    private static final String[] SEARCHES = {"raster", "nearest", "toward-network"};
    private static final int[] REFINE_PASSES = {1, 2};
    private static final int[] ENTRY_CELLS = {4, 8, 16};
    private static final double[] CELLS = {1.0, 2.0, 3.0};
    private static final int[] ITERATIONS = {1, 2, 3};
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
    void sweepsAllParameters() throws Exception {
        assumeTrue(Files.exists(SAMPLE), "Набор " + SAMPLE + " недоступен");
        Files.createDirectories(OUTPUT);
        ObjectMapper mapper = new ObjectMapper();

        run(mapper, "stage1", "warmup", 2.0, 2, "memory", true, 8, "nearest", true, 2, 2, true,
                true);

        List<Map<String, Object>> stage1 = new ArrayList<>();
        int index = 0;
        for (boolean multi : BOOLS) {
            for (String search : SEARCHES) {
                for (boolean reattach : BOOLS) {
                    for (int refine : REFINE_PASSES) {
                        for (boolean dogleg : BOOLS) {
                            for (boolean boundary : BOOLS) {
                                stage1.add(run(mapper, "stage1", "s1-" + index++, 2.0, 2, "memory",
                                        multi, 8, search, reattach, 2, refine, dogleg, boundary));
                            }
                        }
                    }
                }
            }
        }
        writeReports(stage1, mapper, "stage1");
        assertStage(stage1, BOOLS.length * SEARCHES.length * BOOLS.length * REFINE_PASSES.length
                * BOOLS.length * BOOLS.length);

        Map<String, Object> best = bestBy(stage1, "score");

        List<Map<String, Object>> entries = new ArrayList<>();
        index = 0;
        for (int entry : ENTRY_CELLS) {
            entries.add(run(mapper, "entrycells", "ec-" + index++, 2.0, 2, "memory", true, entry,
                    str(best, "cellSearch"), bool(best, "reattachPass"), 2,
                    intOf(best, "refinePasses"), bool(best, "exitGridDogleg"),
                    bool(best, "boundary")));
        }
        writeReports(entries, mapper, "entrycells");
        assertStage(entries, ENTRY_CELLS.length);
        Map<String, Object> bestEntry = bestBy(entries, "score");

        List<Map<String, Object>> stage2 = new ArrayList<>();
        index = 0;
        for (double cell : CELLS) {
            for (int iterations : ITERATIONS) {
                for (String storage : STORAGES) {
                    stage2.add(run(mapper, "stage2", "s2-" + index++, cell, iterations, storage,
                            true, intOf(bestEntry, "entryCells"), str(bestEntry, "cellSearch"),
                            bool(bestEntry, "reattachPass"), 2, intOf(bestEntry, "refinePasses"),
                            bool(bestEntry, "exitGridDogleg"), bool(bestEntry, "boundary")));
                }
            }
        }
        writeReports(stage2, mapper, "stage2");
        assertStage(stage2, CELLS.length * ITERATIONS.length * STORAGES.length);

        System.out.println("BEST stage1: " + summarize(bestBy(stage1, "score")));
        System.out.println("BEST entryCells: " + summarize(bestBy(entries, "score")));
        System.out.println("BEST stage2: " + summarize(bestBy(stage2, "score")));
        System.out.println("MIN edgePoint6: " + summarize(minEdgePoint6(stage1)));
        System.out.println("Reports: " + OUTPUT.toAbsolutePath());
    }

    private Map<String, Object> run(ObjectMapper mapper, String stage, String label, double cell,
                                    int iterations, String storage, boolean multi, int entryCells,
                                    String search, boolean reattach, int reattachIters,
                                    int refinePasses, boolean dogleg, boolean boundary)
            throws Exception {
        Path directory = OUTPUT.resolve(stage).resolve(label);
        Files.createDirectories(directory);
        Path resultFile = directory.resolve("result.geojson");
        Path summaryFile = directory.resolve("summary.json");
        Path warningsFile = directory.resolve("warnings.json");

        properties.setForestGridCellM(cell);
        properties.setForestCostIterations(iterations);
        properties.setForestMaxTurnDeg(90.0);
        properties.setForestTurnEnforcement("hard");
        properties.setForestGridStorage(storage);
        properties.setForestTerminalMultiEntry(multi);
        properties.setForestTerminalEntryCells(entryCells);
        properties.setForestTerminalCellSearch(search);
        properties.setForestReattachPass(reattach);
        properties.setForestReattachIterations(reattachIters);
        properties.setForestRefineLocalPasses(refinePasses);
        properties.setForestExitGridDogleg(dogleg);
        properties.setOksOwningIncludeBoundary(boundary);

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run", label);
        row.put("cellM", cell);
        row.put("iterations", iterations);
        row.put("storage", storage);
        row.put("multiEntry", multi);
        row.put("entryCells", entryCells);
        row.put("cellSearch", search);
        row.put("reattachPass", reattach);
        row.put("reattachIters", reattachIters);
        row.put("refinePasses", refinePasses);
        row.put("exitGridDogleg", dogleg);
        row.put("boundary", boundary);
        long start = System.nanoTime();
        try {
            CalculationOutcome outcome = calculationService.calculate(SAMPLE, resultFile,
                    summaryFile, "grid-forest", warningsFile);
            row.put("totalMs", (System.nanoTime() - start) / 1_000_000L);
            readSummary(mapper, summaryFile, row);
            readGrid(mapper, summaryFile.resolveSibling("grid.json"), row);
            readWarnings(mapper, warningsFile, row);
            readEdgePoint6(mapper, resultFile, row);
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
        row.put("chamberCost", best.path("chamberConstructionCost").asLong());
        row.put("unconnected", best.path("unconnectedOksIds").size());
    }

    private void readGrid(ObjectMapper mapper, Path gridFile, Map<String, Object> row)
            throws Exception {
        if (!Files.exists(gridFile)) {
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
        row.put("warnings", 0);
        row.put("warningCodes", "");
        row.put("warn90", 0);
        if (!Files.exists(warningsFile)) {
            return;
        }
        JsonNode warnings = mapper.readTree(warningsFile.toFile());
        Map<String, Integer> codes = new LinkedHashMap<>();
        int warn90 = 0;
        for (JsonNode warning : warnings) {
            String text = warning.asText();
            int colon = text.indexOf(':');
            String code = colon > 0 ? text.substring(0, colon) : text;
            codes.merge(code, 1, Integer::sum);
            if (code.startsWith("TURN_ANGLE_EXCEEDS_90")) {
                warn90++;
            }
        }
        row.put("warnings", warnings.size());
        row.put("warn90", warn90);
        StringBuilder joined = new StringBuilder();
        for (Map.Entry<String, Integer> entry : codes.entrySet()) {
            if (joined.length() > 0) {
                joined.append('|');
            }
            joined.append(entry.getKey()).append('=').append(entry.getValue());
        }
        row.put("warningCodes", joined.toString());
    }

    private void readEdgePoint6(ObjectMapper mapper, Path resultFile, Map<String, Object> row)
            throws Exception {
        row.put("edgePoint6", null);
        if (!Files.exists(resultFile)) {
            return;
        }
        double bestDistance = Double.POSITIVE_INFINITY;
        Double edgeLength = null;
        for (JsonNode feature : mapper.readTree(resultFile.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            JsonNode coordinates = feature.path("geometry").path("coordinates");
            for (JsonNode endpoint : List.of(coordinates.get(0),
                    coordinates.get(coordinates.size() - 1))) {
                double dx = (endpoint.get(0).asDouble() - POINT6[0]) * 62750.0;
                double dy = (endpoint.get(1).asDouble() - POINT6[1]) * 110870.0;
                double distance = Math.hypot(dx, dy);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    edgeLength = properties.path("length").asDouble();
                }
            }
        }
        if (bestDistance < 0.5) {
            row.put("edgePoint6", edgeLength);
        }
    }

    private void writeReports(List<Map<String, Object>> rows, ObjectMapper mapper, String stage)
            throws Exception {
        String[] columns = {"run", "cellM", "iterations", "storage", "multiEntry", "entryCells",
                "cellSearch", "reattachPass", "reattachIters", "refinePasses", "exitGridDogleg",
                "boundary", "totalMs", "gridMs", "gridStorage", "gridSize", "blockedCells",
                "sources", "terminals", "trees", "unconnected", "warn90", "edgePoint6", "score",
                "lengthM", "calculatedCost", "chamberCost", "warnings", "warningCodes", "passes",
                "error"};
        StringBuilder markdown = new StringBuilder("# Свип v2 параметров grid-forest (" + stage
                + ")\n\n");
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
        markdown.append(summarize(bestBy(rows, "score"))).append('\n');
        markdown.append(summarize(minEdgePoint6(rows))).append('\n');
        Path directory = OUTPUT.resolve(stage);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("report.md"), markdown.toString());
        Files.writeString(directory.resolve("report.csv"), csv.toString());
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve("report.json").toFile(),
                rows);
    }

    private void assertStage(List<Map<String, Object>> rows, int expected) {
        assertThat(rows).hasSize(expected);
        for (Map<String, Object> row : rows) {
            assertThat(row.get("error")).as("прогон %s", row.get("run")).isNull();
            assertThat((Integer) row.get("trees")).as("деревья %s", row.get("run"))
                    .isGreaterThan(0);
            assertThat((Integer) row.get("unconnected")).isLessThanOrEqualTo(17);
        }
    }

    private Map<String, Object> bestBy(List<Map<String, Object>> rows, String key) {
        Map<String, Object> best = null;
        double bestValue = Double.POSITIVE_INFINITY;
        for (Map<String, Object> row : rows) {
            if (row.get("error") != null || (Integer) row.getOrDefault("trees", 0) <= 0) {
                continue;
            }
            Object value = row.get(key);
            if (!(value instanceof Number)) {
                continue;
            }
            double number = ((Number) value).doubleValue();
            if (number < bestValue) {
                bestValue = number;
                best = row;
            }
        }
        return best;
    }

    private Map<String, Object> minEdgePoint6(List<Map<String, Object>> rows) {
        Map<String, Object> best = null;
        double bestValue = Double.POSITIVE_INFINITY;
        for (Map<String, Object> row : rows) {
            if (row.get("error") != null || (Integer) row.getOrDefault("unconnected", 17) != 0) {
                continue;
            }
            Object value = row.get("edgePoint6");
            if (!(value instanceof Number)) {
                continue;
            }
            double number = ((Number) value).doubleValue();
            if (number < bestValue) {
                bestValue = number;
                best = row;
            }
        }
        return best;
    }

    private String summarize(Map<String, Object> row) {
        if (row == null) {
            return "нет данных";
        }
        return "- run=" + row.get("run") + ", score=" + row.get("score")
                + ", length=" + row.get("lengthM") + ", edgePoint6=" + row.get("edgePoint6")
                + ", totalMs=" + row.get("totalMs")
                + ", cell=" + row.get("cellM") + ", iter=" + row.get("iterations")
                + ", storage=" + row.get("storage") + ", multi=" + row.get("multiEntry")
                + ", entryCells=" + row.get("entryCells") + ", search=" + row.get("cellSearch")
                + ", reattach=" + row.get("reattachPass") + ", refine=" + row.get("refinePasses")
                + ", dogleg=" + row.get("exitGridDogleg") + ", boundary=" + row.get("boundary");
    }

    private String str(Map<String, Object> row, String key) {
        return row == null ? "nearest" : String.valueOf(row.get(key));
    }

    private boolean bool(Map<String, Object> row, String key) {
        return row != null && Boolean.TRUE.equals(row.get(key));
    }

    private int intOf(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number ? ((Number) value).intValue() : 2;
    }
}
