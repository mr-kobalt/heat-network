package ru.lct.heating.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/** Единый формат ошибки API. */
@Value
@Builder
@Schema(description = "Стандартная ошибка API")
public class ErrorResponse {

    @Schema(description = "Момент ошибки", example = "2026-09-29T12:00:00Z")
    Instant timestamp;

    @Schema(description = "HTTP-код", example = "404")
    int status;

    @Schema(description = "Краткое название статуса", example = "Not Found")
    String error;

    @Schema(description = "Пояснение", example = "Датасет не найден: 3f1c…")
    String message;
}
