package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import ru.lct.heating.config.AppProperties;

/**
 * E8-15a: нагрузочный прогон на крупном наборе (вход до ~3 ГБ, NFR-08) с замером
 * времени и пика heap. Не проверяет baseline {@code S} — только завершение и
 * отчёт, чтобы воспроизводимо ловить деградацию/переполнение памяти.
 *
 * <p>Запуск:
 * {@code mvn -B test -Dtest=LoadProfileSlowTest -Dload.dataset=<path> \
 *   -DargLine="-Xmx4g" -Dsurefire.excludedGroups= -Dgroups=slow}
 * Дополнительно: {@code -Dload.algorithm=grid-forest}.</p>
 */
@Tag("slow")
class LoadProfileSlowTest extends AbstractCalculationPipelineTest {

    @Test
    void profileDataset() throws Exception {
        String datasetPath = System.getProperty("load.dataset", "");
        assumeTrue(!datasetPath.isBlank(), "load.dataset не задан");
        Path dataset = Path.of(datasetPath);
        assumeTrue(Files.exists(dataset), "Набор не найден: " + datasetPath);

        AppProperties properties = new AppProperties();
        String algorithm = System.getProperty("load.algorithm", "grid-forest");
        if (System.getProperty("load.partitionTileM") != null) {
            properties.setForestPartitionTileM(
                    Double.parseDouble(System.getProperty("load.partitionTileM")));
        }
        if (System.getProperty("load.partitionMarginM") != null) {
            properties.setForestPartitionMarginM(
                    Double.parseDouble(System.getProperty("load.partitionMarginM")));
        }
        Path result = tempDir.resolve("load-result.geojson");
        Path summary = tempDir.resolve("load-summary.json");
        Path warnings = tempDir.resolve("load-warnings.json");

        AtomicLong peakHeap = new AtomicLong();
        AtomicLong peakNonHeap = new AtomicLong();
        Thread sampler = heapSampler(peakHeap, peakNonHeap);
        sampler.start();

        long start = System.nanoTime();
        CalculationOutcome outcome = service(properties).calculate(dataset, result, summary,
                algorithm, warnings);
        long wallMs = (System.nanoTime() - start) / 1_000_000L;
        sampler.interrupt();

        long resultBytes = Files.size(result);

        System.out.printf("%n=== LOAD [%s] algorithm=%s wall=%d ms result=%.1f MB "
                        + "peakHeap=%.0f MB peakNonHeap=%.0f MB maxHeap=%.0f MB ===%n",
                dataset.getFileName(), algorithm, wallMs, resultBytes / 1048576.0,
                peakHeap.get() / 1048576.0, peakNonHeap.get() / 1048576.0,
                ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax() / 1048576.0);
        assertThat(outcome.getSummary()).isNotNull();
        assertThat(outcome.getSummary().getScore()).isGreaterThan(0.0);
    }

    private Thread heapSampler(AtomicLong peakHeap, AtomicLong peakNonHeap) {
        Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    peakHeap.accumulateAndGet(
                            ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),
                            Math::max);
                    peakNonHeap.accumulateAndGet(
                            ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed(),
                            Math::max);
                    Thread.sleep(50);
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "load-heap-sampler");
        thread.setDaemon(true);
        return thread;
    }
}
