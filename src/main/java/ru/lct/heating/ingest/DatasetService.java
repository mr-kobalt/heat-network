package ru.lct.heating.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heating.persistence.DatasetEntity;
import ru.lct.heating.persistence.DatasetRepository;
import ru.lct.heating.persistence.StorageService;

/**
 * Загрузка датасета: сохранение файла, диагностический разбор, запись метаданных.
 */
@Service
public class DatasetService {

    private final StorageService storageService;
    private final IngestService ingestService;
    private final DatasetRepository datasetRepository;
    private final ObjectMapper objectMapper;

    public DatasetService(StorageService storageService, IngestService ingestService,
                          DatasetRepository datasetRepository, ObjectMapper objectMapper) {
        this.storageService = storageService;
        this.ingestService = ingestService;
        this.datasetRepository = datasetRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DatasetEntity create(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Файл не передан или пуст");
        }
        UUID id = UUID.randomUUID();
        Path target = storageService.datasetFile(id);
        try (InputStream inputStream = file.getInputStream()) {
            Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
        }

        IngestResult result;
        try (InputStream inputStream = Files.newInputStream(target)) {
            result = ingestService.ingest(inputStream);
        }
        if (result.getDiagnostics().hasErrors()) {
            Files.deleteIfExists(target);
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    String.join("; ", result.getDiagnostics().getErrors()));
        }

        objectMapper.writeValue(storageService.datasetDiagnosticsFile(id).toFile(), result.getDiagnostics());

        DatasetEntity entity = new DatasetEntity();
        entity.setId(id);
        entity.setCreatedAt(Instant.now());
        entity.setOriginalFilename(file.getOriginalFilename());
        entity.setStatus("READY");
        entity.setObjectCounts(objectMapper.writeValueAsString(result.getDiagnostics().getCountsByType()));
        entity.setBbox(bboxToString(result.getDiagnostics().getBbox()));
        entity.setDiagnostics(objectMapper.writeValueAsString(result.getDiagnostics().getWarnings()));
        return datasetRepository.save(entity);
    }

    @Transactional(readOnly = true)
    public DatasetEntity require(UUID id) {
        return datasetRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Датасет не найден: " + id));
    }

    private String bboxToString(double[] bbox) {
        if (bbox.length == 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (double value : bbox) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.toString();
    }
}
