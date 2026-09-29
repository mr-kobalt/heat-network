package ru.lct.heating.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import ru.lct.heating.persistence.CalculationRunEntity;
import ru.lct.heating.persistence.DatasetEntity;

@Component
public class ApiMapper {

    private final ObjectMapper objectMapper;

    public ApiMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public DatasetResponse toDatasetResponse(DatasetEntity entity) {
        return DatasetResponse.builder()
                .id(entity.getId())
                .createdAt(entity.getCreatedAt())
                .originalFilename(entity.getOriginalFilename())
                .status(entity.getStatus())
                .objectCounts(readTree(entity.getObjectCounts()))
                .bbox(entity.getBbox())
                .warnings(readTree(entity.getDiagnostics()))
                .build();
    }

    public RunResponse toRunResponse(CalculationRunEntity entity) {
        return RunResponse.builder()
                .id(entity.getId())
                .datasetId(entity.getDatasetId())
                .status(entity.getStatus())
                .algorithm(entity.getAlgorithm())
                .mode(entity.getMode())
                .traced(entity.isTrace())
                .createdAt(entity.getCreatedAt())
                .startedAt(entity.getStartedAt())
                .finishedAt(entity.getFinishedAt())
                .stage(entity.getStage())
                .progress(entity.getProgress())
                .error(entity.getError())
                .summary(readTree(entity.getSummary()))
                .build();
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception exception) {
            return null;
        }
    }
}
