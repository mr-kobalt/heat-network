package ru.lct.heating.calculation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
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
import ru.lct.heating.variants.VariantGenerator;

/**
 * Конвейер расчёта: разбор → граф → ограничения → лес маршрутизации → вывод.
 */
@Service
public class CalculationService {

    private final IngestService ingestService;
    private final NetworkGraphBuilder graphBuilder;
    private final ObstacleIndexBuilder obstacleIndexBuilder;
    private final SpecialZoneIndexBuilder specialZoneIndexBuilder;
    private final VariantGenerator variantGenerator;
    private final GeoJsonResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public CalculationService(IngestService ingestService, NetworkGraphBuilder graphBuilder,
                              ObstacleIndexBuilder obstacleIndexBuilder,
                              SpecialZoneIndexBuilder specialZoneIndexBuilder,
                              VariantGenerator variantGenerator, GeoJsonResultWriter resultWriter,
                              ObjectMapper objectMapper, AppProperties appProperties) {
        this.ingestService = ingestService;
        this.graphBuilder = graphBuilder;
        this.obstacleIndexBuilder = obstacleIndexBuilder;
        this.specialZoneIndexBuilder = specialZoneIndexBuilder;
        this.variantGenerator = variantGenerator;
        this.resultWriter = resultWriter;
        this.objectMapper = objectMapper;
        this.appProperties = appProperties;
    }

    public CalculationOutcome calculate(Path inputFile, Path resultFile, Path summaryFile)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        IngestResult ingestResult;
        try (InputStream inputStream = Files.newInputStream(inputFile)) {
            ingestResult = ingestService.ingest(inputStream);
        }
        warnings.addAll(ingestResult.getDiagnostics().getWarnings().stream()
                .map(warning -> warning.getCode() + ": " + warning.getMessage())
                .collect(Collectors.toList()));

        NetworkDataset dataset = ingestResult.getDataset();
        ExistingNetworkGraph graph = graphBuilder.build(dataset);
        warnings.addAll(graph.getWarnings());

        ObstacleIndex obstacleIndex = obstacleIndexBuilder.build(
                dataset, appProperties.getDefaultDiameterMm(), warnings);
        SpecialZoneIndex specialZones = specialZoneIndexBuilder.build(
                dataset, appProperties.getDefaultDiameterMm(), warnings);

        List<VariantResult> variants = variantGenerator.generate(
                dataset, obstacleIndex, graph, specialZones, warnings);

        try (OutputStream outputStream = Files.newOutputStream(resultFile)) {
            resultWriter.write(variants, outputStream);
        }
        List<VariantSummary> summaries = variants.stream()
                .map(VariantResult::getSummary)
                .collect(Collectors.toList());
        objectMapper.writeValue(summaryFile.toFile(), summaries);
        VariantSummary best = summaries.isEmpty() ? null : summaries.get(0);
        return CalculationOutcome.builder()
                .summary(best)
                .warnings(warnings)
                .build();
    }
}
