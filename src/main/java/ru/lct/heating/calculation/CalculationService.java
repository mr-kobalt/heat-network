package ru.lct.heating.calculation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.SourceObject;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.ObstacleIndexBuilder;
import ru.lct.heating.geometry.SpecialZone;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.geometry.SpecialZoneIndexBuilder;
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.ingest.IngestResult;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.output.OutputSegment;
import ru.lct.heating.output.VariantResult;
import ru.lct.heating.output.VariantSummary;
import ru.lct.heating.routing.ConnectionExit;
import ru.lct.heating.routing.OksApproachResolver;
import ru.lct.heating.routing.algorithm.TracingAlgorithm;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;
import ru.lct.heating.trace.StageFeature;
import ru.lct.heating.trace.StageTrace;
import ru.lct.heating.trace.StageTraceWriter;
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
    private final StageTraceWriter traceWriter;

    public CalculationService(IngestService ingestService, NetworkGraphBuilder graphBuilder,
                              ObstacleIndexBuilder obstacleIndexBuilder,
                              SpecialZoneIndexBuilder specialZoneIndexBuilder,
                              VariantGenerator variantGenerator, GeoJsonResultWriter resultWriter,
                              ObjectMapper objectMapper, AppProperties appProperties,
                              TracingAlgorithmRegistry algorithmRegistry,
                              OksApproachResolver approachResolver,
                              StageTraceWriter traceWriter) {
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
        this.traceWriter = traceWriter;
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
        return calculate(inputFile, resultFile, summaryFile, algorithmId, warningsFile, null);
    }

    /**
     * @param stagesDir каталог промежуточных этапов для визуализации (ADR-0036);
     *                  {@code null} — трассировка выключена
     */
    public CalculationOutcome calculate(Path inputFile, Path resultFile, Path summaryFile,
                                        String algorithmId, Path warningsFile, Path stagesDir)
            throws IOException {
        long totalStart = System.nanoTime();
        TracingAlgorithm algorithm = algorithmRegistry.require(algorithmId);
        StageTrace trace = stagesDir != null ? StageTrace.enabled() : StageTrace.disabled();
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
        traceNetwork(trace, graph);

        stage = System.nanoTime();
        ObstacleIndex obstacleIndex = obstacleIndexBuilder.build(dataset, warnings);
        log.info("Stage obstacle index: {} ms; size={}", elapsedMs(stage), obstacleIndex.size());

        stage = System.nanoTime();
        SpecialZoneIndex specialZones = specialZoneIndexBuilder.build(
                dataset, appProperties.getDefaultDiameterMm(), warnings);
        log.info("Stage special zones: {} ms; size={}", elapsedMs(stage), specialZones.size());
        traceRestrictions(trace, obstacleIndex, specialZones);

        stage = System.nanoTime();
        Map<String, ConnectionExit> exits = approachResolver.resolveExits(dataset);
        log.info("Stage exits: {} ms; resolved={}", elapsedMs(stage), exits.size());
        traceExits(trace, exits);

        stage = System.nanoTime();
        List<VariantResult> variants = generateVariants(dataset, obstacleIndex, graph, specialZones,
                warnings, exits, algorithm, trace);
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
        if (stagesDir != null) {
            String runId = stagesDir.getParent() == null
                    ? null : stagesDir.getParent().getFileName().toString();
            traceWriter.write(trace, runId, algorithm.id(), stagesDir);
        }
        log.info("Calculation total: {} ms; warnings={}", elapsedMs(totalStart), warnings.size());
        return CalculationOutcome.builder()
                .summary(best)
                .warnings(warnings)
                .build();
    }

    /**
     * E25-05b: при включённом флаге пересобирает запретный индекс по
     * максимальному фактическому Ду варианта и повторяет расчёт, пока буферы
     * достаточны для итоговых Ду (иначе отступы по Ду были бы занижены).
     */
    private List<VariantResult> generateVariants(NetworkDataset dataset, ObstacleIndex obstacleIndex,
                                                 ExistingNetworkGraph graph,
                                                 SpecialZoneIndex specialZones,
                                                 List<String> warnings,
                                                 Map<String, ConnectionExit> exits,
                                                 TracingAlgorithm algorithm, StageTrace trace) {
        List<VariantResult> variants = variantGenerator.generate(dataset, obstacleIndex, graph,
                specialZones, warnings, exits, algorithm, trace);
        int appliedDn = 0;
        if (appProperties.isForestDiameterAwareBuffers()) {
            int usedDn = maxDiameterMm(variants);
            int iterations = Math.max(1, appProperties.getForestDiameterAwareIterations());
            for (int i = 0; i < iterations && usedDn > 0; i++) {
                ObstacleIndex index = obstacleIndexBuilder.buildAtDiameter(dataset, usedDn, warnings);
                List<VariantResult> candidate = variantGenerator.generate(dataset, index, graph,
                        specialZones, warnings, exits, algorithm, trace);
                int candidateDn = maxDiameterMm(candidate);
                variants = candidate;
                appliedDn = usedDn;
                log.info("Stage generate (diameter-aware {}/{}): usedDn={} candidateDn={}",
                        i + 1, iterations, usedDn, candidateDn);
                if (candidateDn <= usedDn) {
                    break;
                }
                usedDn = candidateDn;
            }
        }
        // E25-07: адаптив достижимости — при неподключённых точках уменьшаем шаг
        // «ворот» вдвое и повторяем (ТП §2.5: неподключение только при отсутствии
        // допустимого маршрута).
        if (appProperties.isForestSpecialStrict() && appProperties.getForestGateRetries() > 0) {
            variants = retryWithFinerGates(dataset, graph, specialZones, warnings, exits, algorithm,
                    trace, variants, appliedDn);
        }
        return variants;
    }

    private List<VariantResult> retryWithFinerGates(NetworkDataset dataset, ExistingNetworkGraph graph,
                                                    SpecialZoneIndex specialZones,
                                                    List<String> warnings,
                                                    Map<String, ConnectionExit> exits,
                                                    TracingAlgorithm algorithm, StageTrace trace,
                                                    List<VariantResult> best, int appliedDn) {
        int bestUnconnected = unconnectedCount(best);
        double step = appProperties.getSpecialGateStepM();
        int retries = appProperties.getForestGateRetries();
        for (int attempt = 0; attempt < retries && bestUnconnected > 0 && step > 0.5; attempt++) {
            step = step / 2.0;
            ObstacleIndex index = obstacleIndexBuilder.buildWithGateStep(dataset,
                    appliedDn > 0 ? appliedDn : null, step, warnings);
            List<VariantResult> candidate = variantGenerator.generate(dataset, index, graph,
                    specialZones, warnings, exits, algorithm, trace);
            int unconnected = unconnectedCount(candidate);
            log.info("Stage generate (gate {}/{}): step={} unconnected={}", attempt + 1, retries,
                    step, unconnected);
            if (unconnected < bestUnconnected) {
                best = candidate;
                bestUnconnected = unconnected;
            }
        }
        return best;
    }

    private int unconnectedCount(List<VariantResult> variants) {
        return variants.isEmpty() ? 0
                : variants.get(0).getSummary().getUnconnectedOksIds().size();
    }

    private int maxDiameterMm(List<VariantResult> variants) {
        if (variants.isEmpty()) {
            return 0;
        }
        int max = 0;
        for (OutputSegment segment : variants.get(0).getSegments()) {
            max = Math.max(max, segment.getDiameterMm());
        }
        return max;
    }

    private void traceNetwork(StageTrace trace, ExistingNetworkGraph graph) {
        if (!trace.isEnabled()) {
            return;
        }
        List<StageFeature> features = new ArrayList<>();
        for (NetworkSegment segment : graph.getSegments().values()) {
            features.add(StageFeature.builder()
                    .geometry(segment.getGeometry())
                    .objectType("network_segment")
                    .properties(Map.of("id", segment.getId(), "diameter_mm", segment.getDiameterMm()))
                    .build());
        }
        for (HeatChamberObject chamber : graph.getChambers().values()) {
            features.add(StageFeature.builder()
                    .geometry(chamber.getGeometry())
                    .objectType("heat_chamber")
                    .properties(Map.of("id", chamber.getId(), "attachments",
                            graph.getChamberAttachments().getOrDefault(chamber.getId(), 0)))
                    .build());
        }
        for (SourceObject source : graph.getSources()) {
            features.add(StageFeature.builder()
                    .geometry(source.getGeometry())
                    .objectType("source")
                    .properties(Map.of("id", source.getId()))
                    .build());
        }
        trace.addStage(StageTrace.NETWORK, features);
    }

    /** Объединённый этап «Ограничения»: запретные буферы + спецзоны (ADR-0037). */
    private void traceRestrictions(StageTrace trace, ObstacleIndex obstacleIndex,
                                   SpecialZoneIndex specialZones) {
        if (!trace.isEnabled()) {
            return;
        }
        List<StageFeature> features = new ArrayList<>();
        for (Geometry geometry : obstacleIndex.geometries()) {
            features.add(StageFeature.builder()
                    .geometry(geometry)
                    .objectType("obstacle")
                    .properties(Map.of())
                    .build());
        }
        for (SpecialZone zone : specialZones.zones()) {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("restriction_type", zone.getRestrictionType());
            properties.put("k_special", zone.getKSpecial());
            if (zone.getAngleMinDeg() != null) {
                properties.put("angle_min_deg", zone.getAngleMinDeg());
            }
            features.add(StageFeature.builder()
                    .geometry(zone.getZone())
                    .objectType("special_zone")
                    .properties(properties)
                    .build());
        }
        trace.addStage(StageTrace.RESTRICTIONS, features);
    }

    private void traceExits(StageTrace trace, Map<String, ConnectionExit> exits) {
        if (!trace.isEnabled()) {
            return;
        }
        List<StageFeature> features = new ArrayList<>();
        for (ConnectionExit exit : exits.values()) {
            if (exit.getTarget() != null) {
                features.add(StageFeature.builder()
                        .geometry(GeometrySupport.GEOMETRY_FACTORY.createPoint(exit.getTarget()))
                        .objectType("exit_target")
                        .properties(Map.of(
                                "id", exit.getConnectionPointId(),
                                "blocked", exit.isBlocked(),
                                "design_diameter_mm", exit.getDesignDiameterMm()))
                        .build());
            }
            if (exit.hasTail()) {
                features.add(StageFeature.builder()
                        .geometry(GeometrySupport.GEOMETRY_FACTORY.createLineString(
                                exit.getTail().toArray(new Coordinate[0])))
                        .objectType("exit_tail")
                        .properties(Map.of("id", exit.getConnectionPointId()))
                        .build());
            }
        }
        trace.addStage(StageTrace.EXITS, features);
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    private int size(List<?> values) {
        return values == null ? 0 : values.size();
    }
}
