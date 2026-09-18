package ru.lct.heating.calculation;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import ru.lct.heating.ingest.DatasetService;
import ru.lct.heating.persistence.CalculationRunEntity;
import ru.lct.heating.persistence.CalculationRunRepository;
import ru.lct.heating.persistence.StorageService;

/**
 * Создание и сопровождение запусков расчёта (ADR-0016).
 */
@Service
public class RunService {

    private final CalculationRunRepository runRepository;
    private final DatasetService datasetService;
    private final RunExecutor runExecutor;
    private final StorageService storageService;

    public RunService(CalculationRunRepository runRepository, DatasetService datasetService,
                      RunExecutor runExecutor, StorageService storageService) {
        this.runRepository = runRepository;
        this.datasetService = datasetService;
        this.runExecutor = runExecutor;
        this.storageService = storageService;
    }

    @Transactional
    public CalculationRunEntity create(UUID datasetId) {
        datasetService.require(datasetId);
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        run.setDatasetId(datasetId);
        run.setStatus(RunStatus.PENDING.name());
        run.setCreatedAt(Instant.now());
        runRepository.save(run);
        runExecutor.execute(run.getId());
        return run;
    }

    @Transactional(readOnly = true)
    public CalculationRunEntity require(UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Запуск не найден: " + runId));
    }

    @Transactional(readOnly = true)
    public List<CalculationRunEntity> listByDataset(UUID datasetId) {
        return runRepository.findByDatasetIdOrderByCreatedAtDesc(datasetId);
    }

    @Transactional(readOnly = true)
    public Path requireResultFile(UUID runId) {
        CalculationRunEntity run = require(runId);
        if (run.getResultPath() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Результат ещё не готов, статус: " + run.getStatus());
        }
        return Paths.get(run.getResultPath());
    }

    public Path summaryFile(UUID runId) {
        return storageService.runSummaryFile(runId);
    }
}
