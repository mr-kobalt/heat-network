package ru.lct.heating.trace;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.ingest.CrsTransformer;

class StageTraceWriterTest {

    @TempDir
    Path tempDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CrsTransformer crs = new CrsTransformer();

    @Test
    void writesManifestAndStageFiles() throws Exception {
        StageTrace trace = StageTrace.enabled();
        trace.addStage(StageTrace.NETWORK, List.of(StageFeature.builder()
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createLineString(new Coordinate[]{
                        new Coordinate(701000, 6170000), new Coordinate(701100, 6170000)}))
                .objectType("network_segment")
                .properties(Map.of("id", "seg1", "diameter_mm", 400))
                .build()));
        trace.addStage(StageTrace.EXITS, List.of(StageFeature.builder()
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(
                        new Coordinate(701050, 6170010)))
                .objectType("exit_target")
                .properties(Map.of("id", "p1", "blocked", false))
                .build()));
        trace.addTreePass(1, List.of(StageFeature.builder()
                .geometry(GeometrySupport.GEOMETRY_FACTORY.createLineString(new Coordinate[]{
                        new Coordinate(701000, 6170000), new Coordinate(701002, 6170002)}))
                .objectType("tree_cell")
                .properties(Map.of("pass", 1))
                .build()));
        GridMaskCodec.Downscale ds = GridMaskCodec.downscale(2, 2, 0);
        trace.addGrid(GridMaskPayload.builder()
                .originX(701000).originY(6170000).cellM(2.0)
                .width(2).height(2).imageWidth(ds.imageWidth).imageHeight(ds.imageHeight)
                .imageCellM(2.0).downscaled(false)
                .blocked(GridMaskCodec.encode(2, 2, ds, index -> index == 0))
                .reachable(GridMaskCodec.encode(2, 2, ds, index -> true))
                .sources(new ArrayList<>(List.of(new double[]{701001, 6170001})))
                .terminalCells(new ArrayList<>(List.of(new double[]{701003, 6170001})))
                .build());
        trace.setBestPass(1);
        trace.setPasses(2);

        StageTraceWriter writer = new StageTraceWriter(objectMapper, crs);
        Path dir = tempDir.resolve("stages");
        writer.write(trace, "run-1", "grid-forest", dir);

        assertThat(Files.exists(dir.resolve("manifest.json"))).isTrue();
        assertThat(Files.exists(dir.resolve("network.geojson"))).isTrue();
        assertThat(Files.exists(dir.resolve("exits.geojson"))).isTrue();
        assertThat(Files.exists(dir.resolve("trees-1.geojson"))).isTrue();
        assertThat(Files.exists(dir.resolve("grid.json"))).isTrue();

        JsonNode manifest = objectMapper.readTree(dir.resolve("manifest.json").toFile());
        assertThat(manifest.path("runId").asText()).isEqualTo("run-1");
        assertThat(manifest.path("bestPass").asInt()).isEqualTo(1);
        List<String> ids = new ArrayList<>();
        manifest.path("stages").forEach(stage -> ids.add(stage.path("id").asText()));
        assertThat(ids).containsExactly("input", "network", "obstacles", "special", "exits", "grid",
                "trees", "refine", "relink");
        JsonNode trees = manifest.path("stages").get(6);
        assertThat(trees.path("passes").get(0).asInt()).isEqualTo(1);

        JsonNode grid = objectMapper.readTree(dir.resolve("grid.json").toFile());
        assertThat(grid.path("boundsWgs84").size()).isEqualTo(4);
        assertThat(grid.path("sources").size()).isEqualTo(1);
        assertThat(grid.path("sources").get(0).get(0).asDouble()).isGreaterThan(37.0);

        JsonNode network = objectMapper.readTree(dir.resolve("network.geojson").toFile());
        assertThat(network.path("features").get(0).path("properties").path("object_type").asText())
                .isEqualTo("network_segment");
        assertThat(network.path("features").get(0).path("geometry").path("coordinates").get(0)
                .get(0).asDouble()).isGreaterThan(37.0);
    }

    @Test
    void disabledTrace_writesNothing() throws Exception {
        StageTrace trace = StageTrace.disabled();
        trace.addStage(StageTrace.NETWORK, List.of());
        StageTraceWriter writer = new StageTraceWriter(objectMapper, crs);
        Path dir = tempDir.resolve("stages");
        writer.write(trace, "run-1", "grid-forest", dir);

        assertThat(Files.exists(dir)).isFalse();
    }
}
