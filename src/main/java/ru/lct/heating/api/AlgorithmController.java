package ru.lct.heating.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.lct.heating.routing.algorithm.AlgorithmInfo;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;

/**
 * Доступные алгоритмы трассировки (ADR-0027).
 */
@Tag(name = "Algorithms", description = "Доступные алгоритмы трассировки (ADR-0027).")
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class AlgorithmController {

    private final TracingAlgorithmRegistry registry;

    public AlgorithmController(TracingAlgorithmRegistry registry) {
        this.registry = registry;
    }

    @Operation(summary = "Список алгоритмов трассировки",
            description = "Возвращает доступные алгоритмы (поле `defaultAlgorithm` — по "
                    + "умолчанию). Устаревшие алгоритмы скрыты. Выбранный `id` передаётся в "
                    + "`POST /datasets/{id}/runs?algorithm=<id>`.")
    @ApiResponse(responseCode = "200", description = "Список алгоритмов",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = AlgorithmInfo.class))))
    @GetMapping("/algorithms")
    public List<AlgorithmInfo> list() {
        return registry.available();
    }
}
