package ru.lct.heating.calculation;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
        return create(datasetId, null, false);
    }

    /**
     * @param algorithmId id алгоритма трассировки; пусто — по умолчанию (ADR-0027)
     */
    @Transactional
    public CalculationRunEntity create(UUID datasetId, String algorithmId) {
        return create(datasetId, algorithmId, false);
    }

    /**
     * @param trace записать промежуточные этапы для визуализации (ADR-0036)
     */
    @Transactional
    public CalculationRunEntity create(UUID datasetId, String algorithmId, boolean trace) {
        datasetService.require(datasetId);
        TracingAlgorithm algorithm = algorithmRegistry.require(algorithmId);
        CalculationRunEntity run = new CalculationRunEntity();
        run.setId(UUID.randomUUID());
        run.setDatasetId(datasetId);
        run.setStatus(RunStatus.PENDING.name());
        run.setAlgorithm(algorithm.id());
        run.setTrace(trace);
        run.setProgress(0);
        run.setCreatedAt(Instant.now());
        runRepository.save(run);
        dispatchAfterCommit(run.getId());
        return run;
    }

    /**
     * Запуск расчёта отправляется воркеру только после commit транзакции, иначе
     * {@link RunExecutor} может не найти ещё не зафиксированную запись и молча
     * выйти — запуск навсегда останется в статусе {@code PENDING}.
     */
    private void dispatchAfterCommit(UUID runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    runExecutor.execute(runId);
                }
            });
        } else {
            runExecutor.execute(runId);
        }
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

    /** Манифест промежуточных этапов (ADR-0036). */
    @Transactional(readOnly = true)
    public Path requireStageManifestFile(UUID runId) {
        CalculationRunEntity run = require(runId);
        if (!run.isTrace()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Запуск выполнен без трассировки этапов");
        }
        if (run.getResultPath() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Расчёт ещё не завершён, статус: " + run.getStatus());
        }
        Path manifest = storageService.runStageDir(runId).resolve("manifest.json");
        if (!Files.exists(manifest)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Файлы этапов отсутствуют");
        }
        return manifest;
    }

    /** Файл одного этапа (ADR-0036); {@code stageId} ограничен безопасным шаблоном. */
    @Transactional(readOnly = true)
    public Path requireStageFile(UUID runId, String stageId) {
        if (stageId == null || !stageId.matches("[a-z0-9-]{1,32}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Некорректный идентификатор этапа");
        }
        requireStageManifestFile(runId);
        Path stagesDir = storageService.runStageDir(runId).toAbsolutePath().normalize();
        Path file = stagesDir.resolve(stageId + (stageId.equals("grid") ? ".json" : ".geojson"))
                .normalize();
        if (!file.startsWith(stagesDir) || !Files.exists(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Этап не найден: " + stageId);
        }
        return file;
    }
}
