package ru.lct.heating.calculation;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.lct.heating.ingest.DatasetService;
import ru.lct.heating.persistence.CalculationRunEntity;
import ru.lct.heating.persistence.CalculationRunRepository;
import ru.lct.heating.persistence.StorageService;
import ru.lct.heating.routing.algorithm.TracingAlgorithm;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;

class RunServiceTest {

    /**
     * Диспатч воркеру должен происходить только после commit: иначе воркер может
     * не найти ещё не зафиксированную запись и запуск навсегда останется PENDING.
     */
    @Test
    void dispatchesRunOnlyAfterCommit() {
        CalculationRunRepository runRepository = mock(CalculationRunRepository.class);
        DatasetService datasetService = mock(DatasetService.class);
        RunExecutor runExecutor = mock(RunExecutor.class);
        StorageService storageService = mock(StorageService.class);
        TracingAlgorithmRegistry registry = mock(TracingAlgorithmRegistry.class);
        TracingAlgorithm algorithm = mock(TracingAlgorithm.class);
        when(algorithm.id()).thenReturn("grid-forest");
        when(registry.require(null)).thenReturn(algorithm);
        when(runRepository.save(any(CalculationRunEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RunService service = new RunService(runRepository, datasetService, runExecutor,
                storageService, registry);

        TransactionSynchronizationManager.initSynchronization();
        try {
            CalculationRunEntity run = service.create(UUID.randomUUID(), null, false);

            verify(runExecutor, never()).execute(any());
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(TransactionSynchronization::afterCommit);
            verify(runExecutor).execute(eq(run.getId()));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
