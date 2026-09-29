package ru.lct.heating.routing.algorithm;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/**
 * Описание доступного алгоритма трассировки для API (ADR-0027).
 */
@Value
@Builder
@Schema(description = "Доступный алгоритм трассировки")
public class AlgorithmInfo {

    @Schema(description = "Идентификатор алгоритма", example = "grid-forest")
    String id;

    @Schema(description = "Краткое описание", example = "Единый лес: поиск пути по сетке")
    String description;

    @Schema(description = "Алгоритм по умолчанию")
    boolean defaultAlgorithm;
}
