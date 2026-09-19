package ru.lct.heating.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IngestServiceTest {

    private final IngestService ingestService = new IngestService(
            new GeoJsonStreamReader(new ObjectMapper()),
            new FeatureParser(new CrsTransformer()));

    private static final String GEOJSON = "{"
            + "\"type\":\"FeatureCollection\",\"features\":["
            + "{\"type\":\"Feature\",\"properties\":{\"id\":1,\"object_type\":\"source\",\"name\":\"T\"},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6300,55.7000]}},"
            + "{\"type\":\"Feature\",\"properties\":{\"id\":2,\"object_type\":\"heat_network\",\"diameter\":500},"
            + "\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[37.6300,55.7000],[37.6400,55.7000]]}},"
            + "{\"type\":\"Feature\",\"properties\":{\"id\":3,\"object_type\":\"oks_connection_point\",\"flow_tph\":50.0},"
            + "\"geometry\":{\"type\":\"Point\",\"coordinates\":[37.6350,55.6950]}},"
            + "{\"type\":\"Feature\",\"properties\":{\"id\":4,\"object_type\":\"unknown_thing\"},"
            + "\"geometry\":null}"
            + "]}";

    @Test
    void parsesKnownObjectsAndReportsUnknown() throws Exception {
        IngestResult result = ingestService.ingest(
                new ByteArrayInputStream(GEOJSON.getBytes(StandardCharsets.UTF_8)));

        IngestDiagnostics diagnostics = result.getDiagnostics();
        assertThat(diagnostics.getTotalFeatures()).isEqualTo(4);
        assertThat(diagnostics.getCountsByType())
                .containsEntry("source", 1)
                .containsEntry("heat_network", 1)
                .containsEntry("oks_connection_point", 1);
        assertThat(diagnostics.getWarnings())
                .anySatisfy(warning -> assertThat(warning.getCode()).isEqualTo("UNKNOWN_OBJECT_TYPE"));
        assertThat(diagnostics.getBbox()).hasSize(4);

        var dataset = result.getDataset();
        assertThat(dataset.getSources()).hasSize(1);
        assertThat(dataset.getNetworkSegments()).hasSize(1);
        assertThat(dataset.getConnectionPoints()).hasSize(1);
        assertThat(dataset.getConnectionPoints().get(0).isNumericId()).isTrue();
        // координаты переведены в UTM (метры)
        assertThat(dataset.getSources().get(0).getGeometry().getCoordinate().x).isGreaterThan(400_000);
    }
}
