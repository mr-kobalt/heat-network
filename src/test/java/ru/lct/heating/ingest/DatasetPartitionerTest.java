package ru.lct.heating.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** E8-15d2c: потоковое пространственное партиционирование входа. */
class DatasetPartitionerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void partitionsByTilesAndDuplicatesBoundaryFeatures(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("input.geojson");
        Files.write(input, featureCollection().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path work = tempDir.resolve("work");

        DatasetPartitioner partitioner = new DatasetPartitioner(
                new GeoJsonStreamReader(mapper), mapper);
        DatasetPartitioner.PartitionPlan plan = partitioner.partition(input, work, 200.0, 30.0,
                new ArrayList<>());

        assertThat(plan.isSingle()).isFalse();
        assertThat(plan.partitions().size()).isGreaterThan(1);
        assertThat(Files.exists(work.resolve("partition.json"))).isTrue();

        int sources = 0;
        int connectionPoints = 0;
        int networks = 0;
        int tilesWithNetwork = 0;
        for (DatasetPartitioner.Partition partition : plan.partitions()) {
            assertThat(Files.exists(partition.file())).isTrue();
            List<String> lines = Files.readAllLines(partition.file());
            assertThat(lines).isNotEmpty();
            assertThat(lines.size()).isEqualTo(partition.count());
            boolean hasNetwork = false;
            for (String line : lines) {
                String type = mapper.readTree(line).path("properties")
                        .path("object_type").asText();
                switch (type) {
                    case "source":
                        sources++;
                        break;
                    case "oks_connection_point":
                        connectionPoints++;
                        break;
                    case "heat_network":
                        networks++;
                        hasNetwork = true;
                        break;
                    default:
                        break;
                }
            }
            if (hasNetwork) {
                tilesWithNetwork++;
            }
        }
        // Источники дублируются во все тайлы; точки — ровно по одной; сеть
        // (длинная линия) попадает более чем в один тайл.
        assertThat(sources).isEqualTo(plan.partitions().size());
        assertThat(connectionPoints).isEqualTo(2);
        assertThat(networks).isEqualTo(tilesWithNetwork);
        assertThat(tilesWithNetwork).isGreaterThan(1);

        JsonNode manifest = mapper.readTree(work.resolve("partition.json").toFile());
        assertThat(manifest.path("cols").asInt()).isGreaterThan(1);
        assertThat(manifest.path("partitions").size()).isEqualTo(plan.partitions().size());
    }

    @Test
    void singlePlanWhenTileCoverDisables(@TempDir Path tempDir) throws Exception {
        Path input = tempDir.resolve("input.geojson");
        Files.write(input, featureCollection().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        DatasetPartitioner partitioner = new DatasetPartitioner(
                new GeoJsonStreamReader(mapper), mapper);
        DatasetPartitioner.PartitionPlan plan = partitioner.partition(input, tempDir.resolve("w"),
                0.0, 0.0, new ArrayList<>());
        assertThat(plan.isSingle()).isTrue();
        assertThat(plan.singleInput()).isEqualTo(input);
    }

    private String featureCollection() throws Exception {
        ObjectNode collection = mapper.createObjectNode();
        collection.put("type", "FeatureCollection");
        ArrayNode features = collection.putArray("features");
        features.add(feature("src", "source", point(37.621, 55.701)));
        features.add(feature("cp1", "oks_connection_point", point(37.622, 55.701)));
        features.add(feature("cp2", "oks_connection_point", point(37.635, 55.705)));
        features.add(feature("net", "heat_network",
                line(37.620, 55.700, 37.638, 55.708)));
        features.add(feature("oks1", "restriction", polygon(37.630, 55.704, 37.633, 55.706)));
        return mapper.writeValueAsString(collection);
    }

    private ObjectNode feature(String id, String objectType, ObjectNode geometry) {
        ObjectNode feature = mapper.createObjectNode();
        feature.put("type", "Feature");
        ObjectNode properties = feature.putObject("properties");
        properties.put("id", id);
        properties.put("object_type", objectType);
        if ("restriction".equals(objectType)) {
            properties.put("restriction_type", "oks");
        }
        feature.set("geometry", geometry);
        return feature;
    }

    private ObjectNode point(double x, double y) {
        ObjectNode geometry = mapper.createObjectNode();
        geometry.put("type", "Point");
        ArrayNode coordinates = geometry.putArray("coordinates");
        coordinates.add(x);
        coordinates.add(y);
        return geometry;
    }

    private ObjectNode line(double x1, double y1, double x2, double y2) {
        ObjectNode geometry = mapper.createObjectNode();
        geometry.put("type", "LineString");
        ArrayNode coordinates = geometry.putArray("coordinates");
        coordinates.add(coord(x1, y1));
        coordinates.add(coord(x2, y2));
        return geometry;
    }

    private ObjectNode polygon(double x1, double y1, double x2, double y2) {
        ObjectNode geometry = mapper.createObjectNode();
        geometry.put("type", "Polygon");
        ArrayNode rings = geometry.putArray("coordinates");
        ArrayNode ring = rings.addArray();
        ring.add(coord(x1, y1));
        ring.add(coord(x2, y1));
        ring.add(coord(x2, y2));
        ring.add(coord(x1, y2));
        ring.add(coord(x1, y1));
        return geometry;
    }

    private ArrayNode coord(double x, double y) {
        ArrayNode coordinate = mapper.createArrayNode();
        coordinate.add(x);
        coordinate.add(y);
        return coordinate;
    }
}
