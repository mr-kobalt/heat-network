package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * FR-30: {@code technical_node} — только смена параметра без разветвления;
 * обычный поворот/сквозная вершина узла не требует. Инвариант: в выводе нет
 * технического узла степени 2, у которого оба инцидентных участка совпадают по
 * {@code diameter} и {@code laying_method} (либо узел вовсе не нужен).
 */
@Tag("slow")
class TechnicalNodeInvariantSlowTest extends AbstractCalculationPipelineTest {

    private static final Path CORRECTED = Path.of("source", "Датасет скорректированный.geojson");
    private static final Path OBSTACLE = Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void noPassThroughTechnicalNodesOnCorrectedDataset() throws Exception {
        check(CORRECTED);
    }

    @Test
    void noPassThroughTechnicalNodesOnObstacleDataset() throws Exception {
        check(OBSTACLE);
    }

    private void check(Path sample) throws Exception {
        assumeTrue(Files.exists(sample), "Набор не найден: " + sample);
        Path resultFile = tempDir.resolve("tech-nodes-result.geojson");
        Path summaryFile = tempDir.resolve("tech-nodes-summary.json");
        CalculationOutcome outcome = service().calculate(sample, resultFile, summaryFile);
        assertThat(outcome.getSummary()).isNotNull();

        ObjectMapper mapper = new ObjectMapper();
        JsonNode features = mapper.readTree(resultFile.toFile()).path("features");

        Map<String, Set<String>> technicalByVariant = new HashMap<>();
        Map<String, Map<String, List<JsonNode>>> incidentByVariant = new HashMap<>();
        Map<String, Integer> maxVertices = new HashMap<>();
        for (JsonNode feature : features) {
            JsonNode properties = feature.path("properties");
            String objectType = properties.path("object_type").asText();
            String variant = properties.path("variant_id").asText();
            if ("technical_node".equals(objectType)) {
                technicalByVariant.computeIfAbsent(variant, key -> new HashSet<>())
                        .add(properties.path("id").asText());
            } else if ("heat_network".equals(objectType)) {
                Map<String, List<JsonNode>> incident =
                        incidentByVariant.computeIfAbsent(variant, key -> new HashMap<>());
                incident.computeIfAbsent(properties.path("start_node_id").asText(),
                        key -> new ArrayList<>()).add(properties);
                incident.computeIfAbsent(properties.path("end_node_id").asText(),
                        key -> new ArrayList<>()).add(properties);
                int vertices = feature.path("geometry").path("coordinates").size();
                maxVertices.merge(variant, vertices, Math::max);
            }
        }

        int checked = 0;
        for (Map.Entry<String, Set<String>> entry : technicalByVariant.entrySet()) {
            String variant = entry.getKey();
            Map<String, List<JsonNode>> incident = incidentByVariant.getOrDefault(variant, Map.of());
            for (String nodeId : entry.getValue()) {
                List<JsonNode> segments = incident.getOrDefault(nodeId, List.of());
                checked++;
                if (segments.size() != 2) {
                    continue;
                }
                JsonNode first = segments.get(0);
                JsonNode second = segments.get(1);
                boolean sameDiameter = first.path("diameter").asDouble()
                        == second.path("diameter").asDouble();
                boolean sameMethod = first.path("laying_method").asText()
                        .equals(second.path("laying_method").asText());
                assertThat(sameDiameter && sameMethod)
                        .as("сквозной технический узел %s (вариант %s) без смены параметра",
                                nodeId, variant)
                        .isFalse();
            }
        }
        System.out.println("TECHNICAL_NODES sample=" + sample + " checked=" + checked
                + " maxVertices=" + maxVertices);
    }
}
