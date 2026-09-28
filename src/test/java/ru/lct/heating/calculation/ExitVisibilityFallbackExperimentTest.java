package ru.lct.heating.calculation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import ru.lct.heating.config.AppProperties;

/**
 * E50-07 (спайк): A/B visibility-фолбэка терминального ребра на наборе OSM.
 * Печатает длину терминального ребра точки 17 и сводку {@code S} с флагом
 * {@code forest-exit-visibility-fallback} off/on. Тег slow (реальный набор).
 */
@Tag("slow")
class ExitVisibilityFallbackExperimentTest extends AbstractCalculationPipelineTest {

    private static final Path DATASET = Path.of("source/Датасет с препятствиями OSM.geojson");
    /** WGS84 точки подключения `17` (id=17) из набора OSM. */
    private static final double POINT_LON = 37.639538146115;
    private static final double POINT_LAT = 55.6968518286392;

    @Test
    void compareFallbackOnOff() throws Exception {
        assumeDataset();
        double off = run(false);
        double on = run(true);
        System.out.println("E50-07 term17: fallbackOff=" + off + " fallbackOn=" + on);
    }

    private double run(boolean fallback) throws Exception {
        AppProperties properties = new AppProperties();
        properties.setForestExitVisibilityFallback(fallback);
        Path result = tempDir.resolve("exit-visibility-" + fallback + ".geojson");
        Path summary = tempDir.resolve("exit-visibility-" + fallback + ".json");
        CalculationOutcome outcome = service(properties).calculate(DATASET, result, summary);
        ObjectMapper mapper = new ObjectMapper();
        String best = bestVariantId(result, mapper);
        double length = terminalEdgeLength(result, mapper, best);
        System.out.println("E50-07 fallback=" + fallback
                + " score=" + outcome.getSummary().getScore()
                + " length=" + outcome.getSummary().getNewNetworkLengthM()
                + " term17=" + length);
        return length;
    }

    private double terminalEdgeLength(Path result, ObjectMapper mapper, String variant)
            throws Exception {
        double found = Double.NaN;
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            if (variant != null && !variant.equals(properties.path("variant_id").asText())) {
                continue;
            }
            JsonNode coords = feature.path("geometry").path("coordinates");
            for (int endIndex : new int[]{0, coords.size() - 1}) {
                double lon = coords.get(endIndex).get(0).asDouble();
                double lat = coords.get(endIndex).get(1).asDouble();
                if (Math.hypot(lon - POINT_LON, lat - POINT_LAT) < 1e-7) {
                    LineString line = (LineString) ru.lct.heating.ingest.GeoJsonGeometryParser
                            .parse(feature.path("geometry"));
                    Coordinate[] cs = ((LineString) crsTransformer.toUtm(line)).getCoordinates();
                    double total = 0.0;
                    for (int i = 0; i + 1 < cs.length; i++) {
                        total += cs[i].distance(cs[i + 1]);
                    }
                    System.out.println("E50-07 edge=" + properties.path("id").asText()
                            + " vertices=" + cs.length + " length=" + total);
                    found = total;
                }
            }
        }
        return found;
    }

    private void assumeDataset() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(DATASET),
                "набор OSM не найден: " + DATASET);
    }
}
