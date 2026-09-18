package ru.lct.heating.api;

import java.io.IOException;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import ru.lct.heating.calculation.RunService;
import ru.lct.heating.persistence.CalculationRunEntity;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class RunController {

    private final RunService runService;
    private final ApiMapper apiMapper;

    public RunController(RunService runService, ApiMapper apiMapper) {
        this.runService = runService;
        this.apiMapper = apiMapper;
    }

    @PostMapping(path = "/datasets/{datasetId}/runs")
    public ResponseEntity<RunResponse> create(@PathVariable UUID datasetId) {
        CalculationRunEntity run = runService.create(datasetId);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(apiMapper.toRunResponse(run));
    }

    @GetMapping("/runs/{runId}")
    public RunResponse get(@PathVariable UUID runId) {
        return apiMapper.toRunResponse(runService.require(runId));
    }

    @GetMapping("/datasets/{datasetId}/runs")
    public List<RunResponse> list(@PathVariable UUID datasetId) {
        return runService.listByDataset(datasetId).stream()
                .map(apiMapper::toRunResponse)
                .collect(Collectors.toList());
    }

    @GetMapping(path = "/runs/{runId}/result", produces = "application/geo+json")
    public ResponseEntity<StreamingResponseBody> result(@PathVariable UUID runId) {
        Path path = runService.requireResultFile(runId);
        StreamingResponseBody body = outputStream -> {
            try (java.io.InputStream inputStream = Files.newInputStream(path)) {
                inputStream.transferTo(outputStream);
            }
        };
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"result-" + runId + ".geojson\"")
                .body(body);
    }
}
