package ru.lct.heating.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.domain.RestrictionObject;

/**
 * Валидация сгенерированного набора с препятствиями OSM (E29-05): сервисный
 * ingest-парсер не должен давать ошибок, а типы ограничений должны
 * присутствовать. Набор генерируется скриптом
 * {@code scripts/generate-obstacle-dataset.mjs}.
 */
@Tag("slow")
class GeneratedObstacleDatasetIngestTest {

    private static final Path DATASET =
            Path.of("source", "Датасет с препятствиями OSM.geojson");

    @Test
    void ingestHasNoErrorsAndContainsExpectedRestrictionTypes() throws Exception {
        assumeTrue(Files.exists(DATASET), "Набор E29 не сгенерирован");
        IngestService service = new IngestService(
                new GeoJsonStreamReader(new ObjectMapper()), new FeatureParser(new CrsTransformer()));
        IngestResult result;
        try (var input = Files.newInputStream(DATASET)) {
            result = service.ingest(input);
        }
        assertThat(result.getDiagnostics().getErrors()).isEmpty();
        Set<String> types = result.getDataset().getRestrictions().stream()
                .map(RestrictionObject::getRestrictionType)
                .collect(Collectors.toSet());
        assertThat(types).contains("road", "railway", "water", "park", "social_area", "oks");
        // Пробелы покрытия после клипа по bbox базового набора (E29-09): tram_tracks
        // за пределами зоны; gas_pipeline/power_cable/prohibited_site нет в OSM.
        // Зафиксированы в манифесте генерации.
        assertThat(types).doesNotContain("tram_tracks", "gas_pipeline", "power_cable",
                "prohibited_site");
    }
}
