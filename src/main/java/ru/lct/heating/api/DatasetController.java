package ru.lct.heating.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heating.ingest.DatasetService;

/** Загрузка входного GeoJSON и чтение метаданных датасета (FR-01, FR-07, FR-08). */
@Tag(name = "Dataset", description = "Загрузка и чтение датасетов (входной GeoJSON).")
@RestController
@RequestMapping(path = "/api/v1/datasets", produces = MediaType.APPLICATION_JSON_VALUE)
public class DatasetController {

    private final DatasetService datasetService;
    private final ApiMapper apiMapper;

    public DatasetController(DatasetService datasetService, ApiMapper apiMapper) {
        this.datasetService = datasetService;
        this.apiMapper = apiMapper;
    }

    @Operation(
            summary = "Загрузить датасет",
            description = "Принимает один совмещённый GeoJSON (`FeatureCollection`, "
                    + "до 3 ГБ) потоково, выполняет диагностику и сохраняет датасет. "
                    + "Тип запроса — `multipart/form-data`, поле `file`. "
                    + "Диагностика (счётчики объектов, bbox, предупреждения) возвращается "
                    + "в ответе и доступна для проверки перед расчётом. "
                    + "Ошибки ingest (например, невалидная геометрия) — 422 без сохранения.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Датасет принят",
                    content = @Content(schema = @Schema(implementation = DatasetResponse.class))),
            @ApiResponse(responseCode = "400", description = "Файл не передан или пуст",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "422", description = "Ошибки ingest входных данных",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "500", description = "Не удалось обработать файл",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatasetResponse upload(
            @Parameter(description = "Файл GeoJSON (`FeatureCollection`), поле `file`",
                    required = true)
            @RequestParam("file") MultipartFile file) {
        try {
            return apiMapper.toDatasetResponse(datasetService.create(file));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось обработать файл: " + exception.getMessage(), exception);
        }
    }

    @Operation(summary = "Получить метаданные датасета",
            description = "Возвращает сохранённые метаданные и диагностику загруженного датасета.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Метаданные датасета",
                    content = @Content(schema = @Schema(implementation = DatasetResponse.class))),
            @ApiResponse(responseCode = "404", description = "Датасет не найден",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/{datasetId}")
    public DatasetResponse get(
            @Parameter(description = "Идентификатор датасета", required = true)
            @PathVariable UUID datasetId) {
        return apiMapper.toDatasetResponse(datasetService.require(datasetId));
    }
}
