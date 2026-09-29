package ru.lct.heating.calculation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
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
import ru.lct.heating.ingest.DatasetPartitioner;
import ru.lct.heating.ingest.IngestResult;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.output.OutputChamber;
import ru.lct.heating.output.OutputSegment;
import ru.lct.heating.output.OutputTechnicalNode;
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
    private final DatasetPartitioner datasetPartitioner;
    private final CostModel costModel;

    public CalculationService(IngestService ingestService, NetworkGraphBuilder graphBuilder,
                              ObstacleIndexBuilder obstacleIndexBuilder,
                              SpecialZoneIndexBuilder specialZoneIndexBuilder,
                              VariantGenerator variantGenerator, GeoJsonResultWriter resultWriter,
                              ObjectMapper objectMapper, AppProperties appProperties,
                              TracingAlgorithmRegistry algorithmRegistry,
                              OksApproachResolver approachResolver,
                              StageTraceWriter traceWriter,
                              DatasetPartitioner datasetPartitioner, CostModel costModel) {
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
        this.datasetPartitioner = datasetPartitioner;
        this.costModel = costModel;
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
        return calculate(inputFile, resultFile, summaryFile, algorithmId, warningsFile, stagesDir,
                ProgressReporter.NOOP);
    }

    /**
     * @param reporter отчёт о ходе расчёта (ADR-0057); {@code null} — без отчёта.
     *                 Прогресс монотонно неубывающий.
     */
    public CalculationOutcome calculate(Path inputFile, Path resultFile, Path summaryFile,
                                        String algorithmId, Path warningsFile, Path stagesDir,
                                        ProgressReporter reporter) throws IOException {
        ProgressReporter progress = monotonic(reporter == null
                ? ProgressReporter.NOOP : reporter);
        long totalStart = System.nanoTime();
        progress.report("ingest", 5);
        TracingAlgorithm algorithm = algorithmRegistry.require(algorithmId);
        StageTrace trace = stagesDir != null ? StageTrace.enabled() : StageTrace.disabled();
        trace.setPassProgress((pass, total) -> progress.report("generate",
                45 + (int) Math.round(45.0 * pass / Math.max(1, total))));
        List<String> warnings = new ArrayList<>();
        List<VariantResult> variants;
        double tileM = appProperties.getForestPartitionTileM();
        if (tileM > 0.0) {
            double margin = appProperties.getForestPartitionMarginM() > 0.0
                    ? appProperties.getForestPartitionMarginM()
                    : appProperties.getForestClusterMarginM();
            Path partitionDir = summaryFile.resolveSibling("partitions");
            DatasetPartitioner.PartitionPlan plan = datasetPartitioner.partition(
                    inputFile, partitionDir, tileM, margin, warnings);
            if (plan.isSingle()) {
                variants = runPipeline(ingestFile(plan.singleInput(), warnings), algorithm,
                        warnings, trace, progress);
            } else {
                if (stagesDir != null) {
                    warnings.add("TRACE_DISABLED_FOR_PARTITIONS: трассировка этапов "
                            + "не поддерживается в режиме партиционирования");
                }
                variants = calculatePartitioned(plan, algorithm, warnings, progress);
            }
        } else {
            variants = runPipeline(ingestFile(inputFile, warnings), algorithm, warnings, trace,
                    progress);
        }
        progress.report("generate", 90);

        long stage = System.nanoTime();
        try (OutputStream outputStream = Files.newOutputStream(resultFile)) {
            resultWriter.write(variants, outputStream);
        }
        log.info("Stage write: {} ms; resultBytes={}", elapsedMs(stage), Files.size(resultFile));
        progress.report("write", 95);

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
        boolean traced = false;
        if (stagesDir != null && tileM <= 0.0) {
            String runId = stagesDir.getParent() == null
                    ? null : stagesDir.getParent().getFileName().toString();
            traceWriter.write(trace, runId, algorithm.id(), stagesDir);
            traced = true;
            progress.report("trace", 98);
        }
        log.info("Calculation total: {} ms; warnings={}", elapsedMs(totalStart), warnings.size());
        progress.report("done", 100);
        return CalculationOutcome.builder()
                .summary(best)
                .warnings(warnings)
                .traced(traced)
                .build();
    }

    /** Разбор одного файла (FeatureCollection) с диагностикой. */
    private IngestResult ingestFile(Path inputFile, List<String> warnings) throws IOException {
        long stage = System.nanoTime();
        IngestResult ingestResult;
        try (InputStream inputStream = Files.newInputStream(inputFile)) {
            ingestResult = ingestService.ingest(inputStream);
        }
        addIngestWarnings(ingestResult, warnings);
        NetworkDataset dataset = ingestResult.getDataset();
        log.info("Stage ingest: {} ms; connectionPoints={} segments={} chambers={} restrictions={} warnings={}",
                elapsedMs(stage), size(dataset.getConnectionPoints()), size(dataset.getNetworkSegments()),
                size(dataset.getHeatChambers()), size(dataset.getRestrictions()), warnings.size());
        return ingestResult;
    }

    private void addIngestWarnings(IngestResult ingestResult, List<String> warnings) {
        warnings.addAll(ingestResult.getDiagnostics().getWarnings().stream()
                .map(warning -> warning.getCode() + ": " + warning.getMessage())
                .collect(Collectors.toList()));
    }

    /** Стадии конвейера для одного набора: граф → ограничения → выходы → лес. */
    private List<VariantResult> runPipeline(IngestResult ingestResult, TracingAlgorithm algorithm,
                                            List<String> warnings, StageTrace trace,
                                            ProgressReporter progress) {
        NetworkDataset dataset = ingestResult.getDataset();
        long stage = System.nanoTime();
        ExistingNetworkGraph graph = graphBuilder.build(dataset);
        warnings.addAll(graph.getWarnings());
        log.info("Stage graph: {} ms; segments={} chambers={} attachments={}",
                elapsedMs(stage), graph.getSegments().size(), graph.getChambers().size(),
                graph.getChamberAttachments().size());
        traceNetwork(trace, graph);
        progress.report("graph", 20);

        stage = System.nanoTime();
        ObstacleIndex obstacleIndex = obstacleIndexBuilder.build(dataset, warnings);
        log.info("Stage obstacle index: {} ms; size={}", elapsedMs(stage), obstacleIndex.size());
        progress.report("obstacles", 30);

        stage = System.nanoTime();
        SpecialZoneIndex specialZones = specialZoneIndexBuilder.build(
                dataset, appProperties.getDefaultDiameterMm(), warnings);
        log.info("Stage special zones: {} ms; size={}", elapsedMs(stage), specialZones.size());
        traceRestrictions(trace, obstacleIndex, specialZones);
        progress.report("special", 35);

        stage = System.nanoTime();
        Map<String, ConnectionExit> exits = approachResolver.resolveExits(dataset);
        log.info("Stage exits: {} ms; resolved={}", elapsedMs(stage), exits.size());
        traceExits(trace, exits);
        progress.report("exits", 40);

        stage = System.nanoTime();
        List<VariantResult> variants = generateVariants(dataset, obstacleIndex, graph, specialZones,
                warnings, exits, algorithm, trace);
        log.info("Stage generate: {} ms; algorithm={} variants={}", elapsedMs(stage),
                algorithm.id(), variants.size());
        return variants;
    }

    /** E8-15d2c2: обработка партиций и слияние вариантов. */
    private List<VariantResult> calculatePartitioned(DatasetPartitioner.PartitionPlan plan,
                                                     TracingAlgorithm algorithm,
                                                     List<String> warnings,
                                                     ProgressReporter progress) throws IOException {
        List<List<VariantResult>> perPartition = new ArrayList<>();
        Map<String, Integer> chamberUsage = new HashMap<>();
        int index = 0;
        for (DatasetPartitioner.Partition partition : plan.partitions()) {
            index++;
            IngestResult ingestResult;
            try (InputStream inputStream = Files.newInputStream(partition.file())) {
                ingestResult = ingestService.ingestLines(inputStream);
            }
            addIngestWarnings(ingestResult, warnings);
            // E8-03c: камеры, заполненные предыдущими тайлами, исключаем из
            // локального набора (FR-26 между тайлами).
            NetworkDataset dataset = excludeFullChambers(ingestResult.getDataset(), chamberUsage);
            IngestResult localized = IngestResult.builder().dataset(dataset)
                    .diagnostics(ingestResult.getDiagnostics()).build();
            List<VariantResult> variants = runPipeline(localized, algorithm, warnings,
                    StageTrace.disabled(), progress);
            Set<String> keepIds = inputNodeIds(dataset);
            perPartition.add(namespaceVariants(variants, "p" + index + "_", keepIds));
            countExistingChamberUsage(variants, dataset, chamberUsage);
            progress.report("generate", 45 + (int) Math.round(45.0 * index
                    / Math.max(1, plan.partitions().size())));
        }
        log.info("Partitioned: partitions={}", perPartition.size());
        return mergeVariants(perPartition);
    }

    /** Убрать из локального набора камеры, достигшие предельной степени. */
    private NetworkDataset excludeFullChambers(NetworkDataset dataset,
                                               Map<String, Integer> chamberUsage) {
        if (chamberUsage.isEmpty() || dataset.getHeatChambers() == null
                || dataset.getHeatChambers().isEmpty()) {
            return dataset;
        }
        int maxDegree = appProperties.getForestMaxChamberDegree();
        if (maxDegree <= 0) {
            return dataset;
        }
        List<HeatChamberObject> kept = new ArrayList<>(dataset.getHeatChambers().size());
        boolean removed = false;
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            if (chamberUsage.getOrDefault(chamber.getId(), 0) >= maxDegree) {
                removed = true;
                continue;
            }
            kept.add(chamber);
        }
        if (!removed) {
            return dataset;
        }
        return NetworkDataset.builder()
                .sources(dataset.getSources())
                .networkSegments(dataset.getNetworkSegments())
                .heatChambers(kept)
                .oksFutures(dataset.getOksFutures())
                .connectionPoints(dataset.getConnectionPoints())
                .oksExisting(dataset.getOksExisting())
                .restrictions(dataset.getRestrictions())
                .bounds(dataset.getBounds())
                .build();
    }

    /** Учесть новые врезки в существующие камеры по лучшему варианту тайла. */
    private void countExistingChamberUsage(List<VariantResult> variants, NetworkDataset dataset,
                                           Map<String, Integer> chamberUsage) {
        if (variants.isEmpty() || dataset.getHeatChambers() == null) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (HeatChamberObject chamber : dataset.getHeatChambers()) {
            existing.add(chamber.getId());
        }
        VariantResult best = variants.get(0);
        Map<String, Integer> perChamber = new HashMap<>();
        for (OutputSegment segment : best.getSegments()) {
            for (String node : new String[]{segment.getStartNodeId(), segment.getEndNodeId()}) {
                if (existing.contains(node)) {
                    perChamber.merge(node, 1, Integer::sum);
                }
            }
        }
        perChamber.forEach((id, count) -> chamberUsage.merge(id, count, Integer::sum));
    }

    /** ID узлов, которые нельзя префиксовать (точки подключения, существующие камеры). */
    private Set<String> inputNodeIds(NetworkDataset dataset) {
        Set<String> ids = new HashSet<>();
        if (dataset.getConnectionPoints() != null) {
            for (var point : dataset.getConnectionPoints()) {
                ids.add(point.getId());
            }
        }
        if (dataset.getHeatChambers() != null) {
            for (HeatChamberObject chamber : dataset.getHeatChambers()) {
                ids.add(chamber.getId());
            }
        }
        return ids;
    }

    private List<VariantResult> namespaceVariants(List<VariantResult> variants, String prefix,
                                                  Set<String> keepIds) {
        List<VariantResult> result = new ArrayList<>(variants.size());
        for (VariantResult variant : variants) {
            List<OutputSegment> segments = new ArrayList<>(variant.getSegments().size());
            for (OutputSegment segment : variant.getSegments()) {
                segments.add(OutputSegment.builder()
                        .id(prefix + segment.getId())
                        .startNodeId(mapNode(segment.getStartNodeId(), prefix, keepIds))
                        .endNodeId(mapNode(segment.getEndNodeId(), prefix, keepIds))
                        .flowTph(segment.getFlowTph()).diameterMm(segment.getDiameterMm())
                        .lengthM(segment.getLengthM()).layingMethod(segment.getLayingMethod())
                        .depthStart(segment.getDepthStart()).depthEnd(segment.getDepthEnd())
                        .cost(segment.getCost()).geometryWgs84(segment.getGeometryWgs84())
                        .build());
            }
            List<OutputChamber> chambers = new ArrayList<>(variant.getChambers().size());
            for (OutputChamber chamber : variant.getChambers()) {
                chambers.add(OutputChamber.builder()
                        .id(mapNode(chamber.getId(), prefix, keepIds))
                        .diameterMm(chamber.getDiameterMm()).cost(chamber.getCost())
                        .geometryWgs84(chamber.getGeometryWgs84()).build());
            }
            List<OutputTechnicalNode> nodes = new ArrayList<>(variant.getTechnicalNodes().size());
            for (OutputTechnicalNode node : variant.getTechnicalNodes()) {
                nodes.add(OutputTechnicalNode.builder()
                        .id(prefix + node.getId())
                        .geometryWgs84(node.getGeometryWgs84()).build());
            }
            result.add(variant.toBuilder().segments(segments).chambers(chambers)
                    .technicalNodes(nodes).build());
        }
        return result;
    }

    private String mapNode(String nodeId, String prefix, Set<String> keepIds) {
        return keepIds.contains(nodeId) ? nodeId : prefix + nodeId;
    }

    /** Слияние вариантов партиций: конкатенация по индексу (рангу), пересчёт S. */
    private List<VariantResult> mergeVariants(List<List<VariantResult>> perPartition) {
        int maxVariants = 0;
        for (List<VariantResult> variants : perPartition) {
            maxVariants = Math.max(maxVariants, variants.size());
        }
        List<VariantResult> merged = new ArrayList<>(maxVariants);
        for (int k = 0; k < maxVariants; k++) {
            List<OutputSegment> segments = new ArrayList<>();
            List<OutputChamber> chambers = new ArrayList<>();
            List<OutputTechnicalNode> nodes = new ArrayList<>();
            long constructionCost = 0L;
            long chamberCost = 0L;
            long tieInCost = 0L;
            int tieInCount = 0;
            long penalty = 0L;
            double length = 0.0;
            List<String> unconnected = new ArrayList<>();
            Set<String> numeric = new HashSet<>();
            int passNumber = 1;
            for (List<VariantResult> variants : perPartition) {
                VariantResult variant = variants.get(Math.min(k, variants.size() - 1));
                segments.addAll(variant.getSegments());
                chambers.addAll(variant.getChambers());
                nodes.addAll(variant.getTechnicalNodes());
                VariantSummary summary = variant.getSummary();
                constructionCost += summary.getConstructionCost();
                chamberCost += summary.getChamberConstructionCost();
                tieInCost += summary.getExistingChamberTieInCost();
                tieInCount += summary.getExistingChamberTieInCount();
                penalty += summary.getUnconnectedPenalty();
                length += summary.getNewNetworkLengthM();
                unconnected.addAll(summary.getUnconnectedOksIds());
                if (summary.getNumericOksIds() != null) {
                    numeric.addAll(summary.getNumericOksIds());
                }
                passNumber = summary.getPassNumber();
            }
            long calculatedCost = constructionCost + penalty;
            double score = costModel.score(calculatedCost, length);
            VariantSummary summary = VariantSummary.builder()
                    .variantId("v" + (k + 1)).rank(k + 1).passNumber(passNumber)
                    .constructionCost(constructionCost).chamberConstructionCost(chamberCost)
                    .existingChamberTieInCount(tieInCount).existingChamberTieInCost(tieInCost)
                    .unconnectedPenalty(penalty).calculatedCost(calculatedCost)
                    .newNetworkLengthM(length).score(score)
                    .unconnectedOksIds(unconnected).numericOksIds(numeric).build();
            merged.add(VariantResult.builder().variantId("v" + (k + 1)).segments(segments)
                    .chambers(chambers).technicalNodes(nodes).summary(summary).build());
        }
        merged.sort(java.util.Comparator.comparingDouble(
                variant -> variant.getSummary().getScore()));
        List<VariantResult> ranked = new ArrayList<>(merged.size());
        int rank = 1;
        for (VariantResult variant : merged) {
            ranked.add(variant.toBuilder()
                    .summary(variant.getSummary().toBuilder().rank(rank++).build()).build());
        }
        return ranked;
    }

    /** Прогресс не должен убывать (повторы генерации при адаптиве). */
    private ProgressReporter monotonic(ProgressReporter delegate) {
        int[] last = { -1 };
        return (stage, value) -> {
            int clamped = Math.max(0, Math.min(100, value));
            if (clamped < last[0]) {
                return;
            }
            last[0] = clamped;
            try {
                delegate.report(stage, clamped);
            } catch (RuntimeException exception) {
                log.warn("Не удалось сообщить прогресс {}={}", stage, clamped, exception);
            }
        };
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
