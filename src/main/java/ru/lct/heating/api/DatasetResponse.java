package ru.lct.heating.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Метаданные загруженного датасета (результат ingest-диагностики). */
@Value
@Builder
@Schema(description = "Метаданные загруженного датасета")
public class DatasetResponse {

    @Schema(description = "Идентификатор датасета (UUID)",
            example = "3f1c2b4e-1a2b-4c3d-9e8f-0123456789ab")
    UUID id;

    @Schema(description = "Момент загрузки", example = "2026-09-29T12:00:00Z")
    Instant createdAt;

    @Schema(description = "Имя загруженного файла", example = "dataset.geojson")
    String originalFilename;

    @Schema(description = "Статус обработки", example = "READY", allowableValues = {"READY"})
    String status;

    @Schema(description = "Количество объектов по типам (object_type → количество)")
    JsonNode objectCounts;

    @Schema(description = "Bounding box в EPSG:32637: minX,minY,maxX,maxY",
            example = "371234.5,6178901.2,373456.7,6180123.4")
    String bbox;

    @Schema(description = "Предупреждения ingest (FR-08)")
    JsonNode warnings;
}
