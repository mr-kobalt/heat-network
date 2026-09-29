package ru.lct.heating.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.lct.heating.calculation.CalculationMode;
import ru.lct.heating.calculation.RunService;
import ru.lct.heating.persistence.CalculationRunEntity;

/** Запуск расчёта, статус, результат и промежуточные этапы (ADR-0016, ADR-0036). */
@Tag(name = "Runs", description = "Запуск расчёта, статус, результат и этапы.")
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunController {

    private final RunService runService;
    private final ApiMapper apiMapper;

    public RunController(RunService runService, ApiMapper apiMapper) {
        this.runService = runService;
        this.apiMapper = apiMapper;
    }

    @Operation(
            summary = "Запустить расчёт",
            description = "Создаёт асинхронный запуск расчёта для датасета. "
                    + "Алгоритм (`algorithm`) и режим (`mode`) необязательны: по умолчанию "
                    + "`grid-forest` и `2d`. При `trace=true` сохраняются промежуточные этапы "
                    + "(доступны через `/runs/{id}/stages`). Возвращает созданный запуск со "
                    + "статусом `PENDING`; далее опрашивайте `GET /runs/{id}`. "
                    + "Режим `depth` даёт отдельный набор вариантов (ADR-0073).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Запуск создан и поставлен в очередь",
                    content = @Content(schema = @Schema(implementation = RunResponse.class))),
            @ApiResponse(responseCode = "400", description = "Неизвестный алгоритм или режим",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Датасет не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(path = "/datasets/{datasetId}/runs")
    public ResponseEntity<RunResponse> create(
            @Parameter(description = "Идентификатор датасета", required = true)
            @PathVariable UUID datasetId,
            @Parameter(description = "Алгоритм трассировки; по умолчанию `grid-forest` "
                    + "(см. `GET /api/v1/algorithms`)", example = "grid-forest")
            @RequestParam(name = "algorithm", required = false) String algorithm,
            @Parameter(description = "Сохранить промежуточные этапы для визуализации (ADR-0036)",
                    example = "false")
            @RequestParam(name = "trace", required = false, defaultValue = "false") boolean trace,
            @Parameter(description = "Режим расчёта: `2d` (по умолчанию) или `depth` (ADR-0073)",
                    schema = @Schema(allowableValues = {"2d", "depth"}),
                    example = "2d")
            @RequestParam(name = "mode", required = false) String mode) {
        CalculationRunEntity run = runService.create(datasetId, algorithm, trace,
                CalculationMode.fromParameter(mode));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(apiMapper.toRunResponse(run));
    }

    @Operation(summary = "Статус запуска",
            description = "Возвращает статус, этап, прогресс и (по завершении) сводку лучшего "
                    + "варианта. Опрашивайте, пока статус `PENDING`/`RUNNING`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Состояние запуска",
                    content = @Content(schema = @Schema(implementation = RunResponse.class))),
            @ApiResponse(responseCode = "404", description = "Запуск не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/runs/{runId}")
    public RunResponse get(
            @Parameter(description = "Идентификатор запуска", required = true)
            @PathVariable UUID runId) {
        return apiMapper.toRunResponse(runService.require(runId));
    }

    @Operation(summary = "Список запусков датасета",
            description = "История запусков датасета (новые первыми).")
    @ApiResponse(responseCode = "200", description = "Список запусков",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = RunResponse.class))))
    @GetMapping("/datasets/{datasetId}/runs")
    public List<RunResponse> list(
            @Parameter(description = "Идентификатор датасета", required = true)
            @PathVariable UUID datasetId) {
        return runService.listByDataset(datasetId).stream()
                .map(apiMapper::toRunResponse)
                .collect(Collectors.toList());
    }

    @Operation(summary = "Результат расчёта (GeoJSON)",
            description = "Выгружает `FeatureCollection` в формате заказчика "
                    + "(см. `docs/02-domain/data-model.md`): участки `heat_network`, камеры, "
                    + "технические узлы и `variant_summary` для всех вариантов. Потоковая отдача.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "GeoJSON результата",
                    content = @Content(mediaType = "application/geo+json",
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404", description = "Запуск не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Результат ещё не готов",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(path = "/runs/{runId}/result", produces = "application/geo+json")
    public ResponseEntity<StreamingResponseBody> result(
            @Parameter(description = "Идентификатор запуска", required = true)
            @PathVariable UUID runId) {
        Path path = runService.requireResultFile(runId);
        return stream(path, "result-" + runId + ".geojson");
    }

    @Operation(summary = "Манифест промежуточных этапов (ADR-0036)",
            description = "Возвращает `manifest.json` с перечнем этапов (доступно при "
                    + "`trace=true`). Каждый этап — `GET /runs/{id}/stages/{stageId}`. "
                    + "Требуется завершённый запуск с трассировкой.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Манифест этапов (JSON)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "404", description = "Запуск без трассировки или этапы отсутствуют",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "409", description = "Расчёт ещё не завершён",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(path = "/runs/{runId}/stages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> stages(
            @Parameter(description = "Идентификатор запуска", required = true)
            @PathVariable UUID runId) {
        Path path = runService.requireStageManifestFile(runId);
        return stream(path, "manifest-" + runId + ".json");
    }

    @Operation(summary = "Файл одного этапа",
            description = "GeoJSON-снимок этапа (`network`, `restrictions`, `exits`, "
                    + "`trees-<проход>`, `relink`, `refine`, …) либо `grid.json` для растровой "
                    + "диагностики сетки. Список этапов — в манифесте `/runs/{id}/stages`.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Файл этапа (GeoJSON или JSON)",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(type = "string", format = "binary"))),
            @ApiResponse(responseCode = "400", description = "Некорректный идентификатор этапа",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "404", description = "Этап не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping(path = "/runs/{runId}/stages/{stageId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<StreamingResponseBody> stage(
            @Parameter(description = "Идентификатор запуска", required = true)
            @PathVariable UUID runId,
            @Parameter(description = "Идентификатор этапа (`[a-z0-9-]{1,32}`)",
                    required = true, example = "trees-1")
            @PathVariable String stageId) {
        Path path = runService.requireStageFile(runId, stageId);
        return stream(path, path.getFileName().toString());
    }

    private ResponseEntity<StreamingResponseBody> stream(Path path, String filename) {
        StreamingResponseBody body = outputStream -> {
            try (java.io.InputStream inputStream = Files.newInputStream(path)) {
                inputStream.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .body(body);
    }
}
