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
import ru.lct.heating.routing.algorithm.TracingAlgorithm;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;

/**
 * Создание и сопровождение запусков расчёта (ADR-0016, ADR-0027).
 */
@Service
public class RunService {

    private final CalculationRunRepository runRepository;
    private final DatasetService datasetService;
    private final RunExecutor runExecutor;
    private final StorageService storageService;
    private final TracingAlgorithmRegistry algorithmRegistry;

    public RunService(CalculationRunRepository runRepository, DatasetService datasetService,
                      RunExecutor runExecutor, StorageService storageService,
                      TracingAlgorithmRegistry algorithmRegistry) {
        this.runRepository = runRepository;
        this.datasetService = datasetService;
        this.runExecutor = runExecutor;
        this.storageService = storageService;
        this.algorithmRegistry = algorithmRegistry;
    }

    @Transactional
    public CalculationRunEntity create(UUID datasetId) {
        return create(datasetId, null);
    }

    /**
     * @param algorithmId id алгоритма трассировки; пусто — по умолчанию (ADR-0027)
     */
    @Transactional
    public CalculationRunEntity create(UUID datasetId, String algorithmId) {
        datasetService.require(datasetId);
        TracingAlgorithm algorithm = algorithmRegistry.require(algorithmId);
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        run.setDatasetId(datasetId);
        run.setStatus(RunStatus.PENDING.name());
        run.setAlgorithm(algorithm.id());
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
