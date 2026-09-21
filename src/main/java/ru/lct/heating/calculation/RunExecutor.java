package ru.lct.heating.calculation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import ru.lct.heating.persistence.CalculationRunEntity;
import ru.lct.heating.persistence.CalculationRunRepository;
import ru.lct.heating.persistence.StorageService;

/**
 * Асинхронное выполнение расчёта (ADR-0016).
 */
@Component
public class RunExecutor {

    private static final Logger log = LoggerFactory.getLogger(RunExecutor.class);

    private final CalculationRunRepository runRepository;
    private final CalculationService calculationService;
    private final StorageService storageService;
    private final ObjectMapper objectMapper;

    public RunExecutor(CalculationRunRepository runRepository, CalculationService calculationService,
                       StorageService storageService, ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.calculationService = calculationService;
        this.storageService = storageService;
        this.objectMapper = objectMapper;
    }

    @Async("calculationExecutor")
    public void execute(UUID runId) {
        CalculationRunEntity run = runRepository.findById(runId).orElse(null);
        if (run == null) {
            return;
        }
        run.setStatus(RunStatus.RUNNING.name());
        run.setStartedAt(Instant.now());
        runRepository.save(run);
        try {
            Path resultFile = storageService.runResultFile(runId);
            Path summaryFile = storageService.runSummaryFile(runId);
            Path warningsFile = storageService.runWarningsFile(runId);
            CalculationOutcome outcome = calculationService.calculate(
                    storageService.datasetFile(run.getDatasetId()), resultFile, summaryFile,
                    run.getAlgorithm(), warningsFile);
            boolean partial = outcome.getSummary() == null
                    || !outcome.getSummary().getUnconnectedOksIds().isEmpty();
            run.setStatus(partial ? RunStatus.PARTIAL.name() : RunStatus.DONE.name());
            run.setFinishedAt(Instant.now());
            run.setResultPath(resultFile.toString());
            run.setSummary(outcome.getSummary() == null
                    ? null : objectMapper.writeValueAsString(outcome.getSummary()));
            runRepository.save(run);
        } catch (Exception exception) {
            log.error("Расчёт {} завершился ошибкой", runId, exception);
            run.setStatus(RunStatus.FAILED.name());
            run.setFinishedAt(Instant.now());
            run.setError(exception.getMessage());
            runRepository.save(run);
        }
    }
}
