package ru.lct.heating.api;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Состояние запуска расчёта (ADR-0016/0057). */
@Value
@Builder
@Schema(description = "Состояние запуска расчёта")
public class RunResponse {

    @Schema(description = "Идентификатор запуска (UUID)",
            example = "7d9a1f2c-3b4c-4d5e-8f90-abcdef012345")
    UUID id;

    @Schema(description = "Идентификатор датасета")
    UUID datasetId;

    @Schema(description = "Статус запуска",
            allowableValues = {"PENDING", "RUNNING", "DONE", "PARTIAL", "FAILED"},
            example = "DONE")
    String status;

    @Schema(description = "Алгоритм трассировки", example = "grid-forest")
    String algorithm;

    /** ADR-0073: режим расчёта — {@code 2d} или {@code depth}. */
    @Schema(description = "Режим расчёта", allowableValues = {"2d", "depth"}, example = "2d")
    String mode;

    @Schema(description = "Доступна ли постадийная трассировка (ADR-0036)")
    boolean traced;

    @Schema(description = "Момент создания", example = "2026-09-29T12:00:00Z")
    Instant createdAt;

    @Schema(description = "Момент начала расчёта")
    Instant startedAt;

    @Schema(description = "Момент завершения расчёта")
    Instant finishedAt;

    /** ADR-0057: текущий этап расчёта и прогресс 0…100. */
    @Schema(description = "Текущий этап расчёта",
            example = "generate",
            allowableValues = {"ingest", "graph", "obstacles", "special", "exits",
                    "generate", "write", "trace", "done"})
    String stage;

    @Schema(description = "Прогресс расчёта, 0…100", example = "67")
    Integer progress;

    @Schema(description = "Текст ошибки (при статусе FAILED)")
    String error;

    @Schema(description = "Сводка лучшего варианта (объект variant_summary)")
    JsonNode summary;
}
