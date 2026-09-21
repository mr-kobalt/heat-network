package ru.lct.heating.calculation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.ObstacleIndexBuilder;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.geometry.SpecialZoneIndexBuilder;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.ingest.IngestResult;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.output.VariantResult;
import ru.lct.heating.output.VariantSummary;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.OksApproachResolver;
import ru.lct.heating.routing.algorithm.TracingAlgorithm;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;
import ru.lct.heating.variants.VariantGenerator;

/**
 * Конвейер расчёта: разбор → граф → ограничения → лес маршрутизации → вывод.
 */
@Service
public class CalculationService {

    private static final Logger log = LoggerFactory.getLogger(CalculationService.class);

    private final IngestService ingestService;
    private final NetworkGraphBuilder graphBuilder;
    private final ObstacleIndexBuilder obstacleIndexBuilder;
    private final SpecialZoneIndexBuilder specialZoneIndexBuilder;
    private final VariantGenerator variantGenerator;
    private final GeoJsonResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;
    private final TracingAlgorithmRegistry algorithmRegistry;
    private final OksApproachResolver approachResolver;

    public CalculationService(IngestService ingestService, NetworkGraphBuilder graphBuilder,
                              ObstacleIndexBuilder obstacleIndexBuilder,
                              SpecialZoneIndexBuilder specialZoneIndexBuilder,
                              VariantGenerator variantGenerator, GeoJsonResultWriter resultWriter,
                              ObjectMapper objectMapper, AppProperties appProperties,
                              TracingAlgorithmRegistry algorithmRegistry,
                              OksApproachResolver approachResolver) {
        this.ingestService = ingestService;
        this.graphBuilder = graphBuilder;
        this.obstacleIndexBuilder = obstacleIndexBuilder;
        this.specialZoneIndexBuilder = specialZoneIndexBuilder;
        this.variantGenerator = variantGenerator;
        this.resultWriter = resultWriter;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
        this.algorithmRegistry = algorithmRegistry;
        this.approachResolver = approachResolver;
    }

    public CalculationOutcome calculate(Path inputFile, Path resultFile, Path summaryFile)
            throws IOException {
        return calculate(inputFile, resultFile, summaryFile, null, null);
    }

    /**
     * @param algorithmId id алгоритма трассировки; пусто — алгоритм по умолчанию
     *                    (ADR-0027)
     */
    public CalculationOutcome calculate(Path inputFile, Path resultFile, Path summaryFile,
                                        String algorithmId) throws IOException {
        return calculate(inputFile, resultFile, summaryFile, algorithmId, null);
    }

    /**
     * @param warningsFile файл диагностики расчёта (может быть {@code null})
     */
    public CalculationOutcome calculate(Path inputFile, Path resultFile, Path summaryFile,
                                        String algorithmId, Path warningsFile) throws IOException {
        long totalStart = System.nanoTime();
        TracingAlgorithm algorithm = algorithmRegistry.require(algorithmId);
        List<String> warnings = new ArrayList<>();
        long stage = System.nanoTime();
        IngestResult ingestResult;
        try (InputStream inputStream = Files.newInputStream(inputFile)) {
            ingestResult = ingestService.ingest(inputStream);
        }
        warnings.addAll(ingestResult.getDiagnostics().getWarnings().stream()
                .map(warning -> warning.getCode() + ": " + warning.getMessage())
                .collect(Collectors.toList()));

        NetworkDataset dataset = ingestResult.getDataset();
        log.info("Stage ingest: {} ms; connectionPoints={} segments={} chambers={} restrictions={} warnings={}",
                elapsedMs(stage), size(dataset.getConnectionPoints()), size(dataset.getNetworkSegments()),
                size(dataset.getHeatChambers()), size(dataset.getRestrictions()), warnings.size());

        stage = System.nanoTime();
        ExistingNetworkGraph graph = graphBuilder.build(dataset);
        warnings.addAll(graph.getWarnings());
        log.info("Stage graph: {} ms; segments={} chambers={} attachments={}",
                elapsedMs(stage), graph.getSegments().size(), graph.getChambers().size(),
                graph.getChamberAttachments().size());

        stage = System.nanoTime();
        ObstacleIndex obstacleIndex = obstacleIndexBuilder.build(
                dataset, appProperties.getDefaultDiameterMm(), warnings);
        log.info("Stage obstacle index: {} ms; size={}", elapsedMs(stage), obstacleIndex.size());

        stage = System.nanoTime();
        SpecialZoneIndex specialZones = specialZoneIndexBuilder.build(
                dataset, appProperties.getDefaultDiameterMm(), warnings);
        log.info("Stage special zones: {} ms; size={}", elapsedMs(stage), specialZones.size());

        stage = System.nanoTime();
        Map<String, ConnectionExit> exits = approachResolver.resolveExits(dataset);
        log.info("Stage exits: {} ms; resolved={}", elapsedMs(stage), exits.size());

        stage = System.nanoTime();
        List<VariantResult> variants = variantGenerator.generate(
                dataset, obstacleIndex, graph, specialZones, warnings, exits, algorithm);
        log.info("Stage generate: {} ms; algorithm={} variants={}", elapsedMs(stage),
                algorithm.id(), variants.size());

        stage = System.nanoTime();
        try (OutputStream outputStream = Files.newOutputStream(resultFile)) {
            resultWriter.write(variants, outputStream);
        }
        log.info("Stage write: {} ms; resultBytes={}", elapsedMs(stage), Files.size(resultFile));

        List<VariantSummary> summaries = variants.stream()
                .map(VariantResult::getSummary)
                .collect(Collectors.toList());
        objectMapper.writeValue(summaryFile.toFile(), summaries);
        VariantSummary best = summaries.isEmpty() ? null : summaries.get(0);
        if (!variants.isEmpty() && variants.get(0).getGridReport() != null) {
            Path gridFile = summaryFile.resolveSibling("grid.json");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(gridFile.toFile(), variants.get(0).getGridReport());
        }
        if (best != null) {
            log.info("Summary: score={} length={} cost={} unconnected={}", best.getScore(),
                    best.getNewNetworkLengthM(), best.getCalculatedCost(),
                    best.getUnconnectedOksIds().size());
        }
        if (warningsFile != null) {
            objectMapper.writeValue(warningsFile.toFile(), warnings);
        }
        int warningLimit = 50;
        for (int i = 0; i < Math.min(warningLimit, warnings.size()); i++) {
            log.info("Warning[{}]: {}", i + 1, warnings.get(i));
        }
        if (warnings.size() > warningLimit) {
            log.info("Warning: ещё {} предупреждений (см. warnings.json)", warnings.size() - warningLimit);
        }
        log.info("Calculation total: {} ms; warnings={}", elapsedMs(totalStart), warnings.size());
        return CalculationOutcome.builder()
                .summary(best)
                .warnings(warnings)
                .build();
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
