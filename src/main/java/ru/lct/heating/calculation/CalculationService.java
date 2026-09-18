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
import ru.lct.heating.graph.ExistingNetworkGraph;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.ingest.IngestResult;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.output.ResultBuilder;
import ru.lct.heating.output.VariantResult;
import ru.lct.heating.routing.RoutePlanner;
import ru.lct.heating.routing.RoutePlanningResult;

/**
 * Конвейер расчёта M1: разбор → граф → ограничения → маршрутизация → вывод.
 */
@Service
public class CalculationService {

    private final IngestService ingestService;
    private final NetworkGraphBuilder graphBuilder;
    private final ObstacleIndexBuilder obstacleIndexBuilder;
    private final RoutePlanner routePlanner;
    private final ResultBuilder resultBuilder;
    private final GeoJsonResultWriter resultWriter;
    private final ObjectMapper objectMapper;
    private final AppProperties appProperties;

    public CalculationService(IngestService ingestService, NetworkGraphBuilder graphBuilder,
                              ObstacleIndexBuilder obstacleIndexBuilder, RoutePlanner routePlanner,
                              ResultBuilder resultBuilder, GeoJsonResultWriter resultWriter,
                              ObjectMapper objectMapper, AppProperties appProperties) {
        this.ingestService = ingestService;
        this.graphBuilder = graphBuilder;
        this.obstacleIndexBuilder = obstacleIndexBuilder;
        this.routePlanner = routePlanner;
        this.resultBuilder = resultBuilder;
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

        RoutePlanningResult planning = routePlanner.plan(dataset, obstacleIndex, warnings);
        VariantResult variant = resultBuilder.build(planning, dataset);

        try (OutputStream outputStream = Files.newOutputStream(resultFile)) {
            resultWriter.write(variant, outputStream);
        }
        objectMapper.writeValue(summaryFile.toFile(), variant.getSummary());
        return CalculationOutcome.builder()
                .summary(variant.getSummary())
                .warnings(warnings)
                .build();
    }
}
