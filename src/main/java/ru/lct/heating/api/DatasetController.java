package ru.lct.heating.api;

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

@RestController
@RequestMapping(path = "/api/v1/datasets", produces = MediaType.APPLICATION_JSON_VALUE)
public class DatasetController {

    private final DatasetService datasetService;
    private final ApiMapper apiMapper;

    public DatasetController(DatasetService datasetService, ApiMapper apiMapper) {
        this.datasetService = datasetService;
        this.apiMapper = apiMapper;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public DatasetResponse upload(@RequestParam("file") MultipartFile file) {
        try {
            return apiMapper.toDatasetResponse(datasetService.create(file));
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Не удалось обработать файл: " + exception.getMessage(), exception);
        }
    }

    @GetMapping("/{datasetId}")
    public DatasetResponse get(@PathVariable UUID datasetId) {
        return apiMapper.toDatasetResponse(datasetService.require(datasetId));
    }
}
