package ru.lct.heating.calculation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.cost.CostProperties;
import ru.lct.heating.geometry.DistanceBand;
import ru.lct.heating.geometry.LineStringSimplifier;
import ru.lct.heating.geometry.ObstacleIndexBuilder;
import ru.lct.heating.geometry.RestrictionAxisBuilder;
import ru.lct.heating.geometry.RestrictionMode;
import ru.lct.heating.geometry.RestrictionRule;
import ru.lct.heating.geometry.RestrictionRuleResolver;
import ru.lct.heating.geometry.RestrictionRulesProperties;
import ru.lct.heating.geometry.SpecialSpanSplitter;
import ru.lct.heating.geometry.SpecialZoneIndexBuilder;
import ru.lct.heating.graph.NetworkGraphBuilder;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.hydraulics.DiameterRow;
import ru.lct.heating.hydraulics.EnvelopeCatalog;
import ru.lct.heating.hydraulics.EnvelopeRow;
import ru.lct.heating.hydraulics.HeatingTablesProperties;
import ru.lct.heating.hydraulics.MaxLengthEnforcer;
import ru.lct.heating.ingest.CrsTransformer;
import ru.lct.heating.ingest.FeatureParser;
import ru.lct.heating.ingest.GeoJsonGeometryParser;
import ru.lct.heating.ingest.GeoJsonStreamReader;
import ru.lct.heating.ingest.IngestService;
import ru.lct.heating.output.ForestResultBuilder;
import ru.lct.heating.output.GeoJsonResultWriter;
import ru.lct.heating.routing.TieInCandidateProvider;
import ru.lct.heating.routing.algorithm.GridForestTracingAlgorithm;
import ru.lct.heating.routing.algorithm.TracingAlgorithmRegistry;
import ru.lct.heating.routing.GridForestPlanner;
import ru.lct.heating.variants.VariantGenerator;

/**
 * Общая обвязка сквозного теста конвейера (без БД и Spring). Быстрый тест
 * использует маленький фикстур, полный — реальный набор (тег slow).
 */
abstract class AbstractCalculationPipelineTest {

    @TempDir
    protected Path tempDir;

    /** Проекция для проверки углов поворота в расчётной СК (ТП v2, EPSG:32637). */
    protected final CrsTransformer crsTransformer = new CrsTransformer();

    protected CalculationService service() {
        return service(new AppProperties());
    }

    /**
     * Настройки для фикстур с крупными ОКС и точками вдали от стен: фильтр
     * выходов (ADR-0040) отключён, чтобы проверялся конвейер, а не политика выхода.
     */
    protected AppProperties permissiveExitProperties() {
        AppProperties properties = new AppProperties();
        properties.setOksExitFilter(false);
        return properties;
    }

    protected CalculationService service(AppProperties appProperties) {
        ObjectMapper objectMapper = new ObjectMapper();
        CrsTransformer crs = new CrsTransformer();
        IngestService ingest = new IngestService(new GeoJsonStreamReader(objectMapper),
                new FeatureParser(crs));
        HeatingTablesProperties tables = diameters();
        DiameterCatalog catalog = new DiameterCatalog(tables);
        EnvelopeCatalog envelopes = new EnvelopeCatalog(tables);
        CostModel costModel = new CostModel(catalog, new CostProperties());
        RestrictionRuleResolver resolver = new RestrictionRuleResolver(rules());
        appProperties.setTurnPenaltyM(30.0);
        LineStringSimplifier simplifier = new LineStringSimplifier();
        ru.lct.heating.routing.OksApproachResolver approachResolver =
                new ru.lct.heating.routing.OksApproachResolver(resolver, envelopes, catalog,
                        appProperties);
        GridForestPlanner forestPlanner = new GridForestPlanner(
                new TieInCandidateProvider(appProperties), catalog, costModel,
                new MaxLengthEnforcer(catalog),
                simplifier, new ru.lct.heating.geometry.ObstacleMaskBuilder(),
                new ru.lct.heating.routing.CellStoreFactory(appProperties, null), appProperties,
                approachResolver);
        TracingAlgorithmRegistry registry = new TracingAlgorithmRegistry(
                List.of(new GridForestTracingAlgorithm(forestPlanner)), appProperties);
        VariantGenerator variantGenerator = new VariantGenerator(
                new ForestResultBuilder(crs, costModel, new SpecialSpanSplitter(), appProperties));

        return new CalculationService(ingest, new NetworkGraphBuilder(),
                new ObstacleIndexBuilder(resolver, envelopes, catalog, appProperties,
                        new ru.lct.heating.geometry.SpecialGateCarver(
                                new RestrictionAxisBuilder())),
                new SpecialZoneIndexBuilder(resolver, new RestrictionAxisBuilder(), envelopes),
                variantGenerator, new GeoJsonResultWriter(objectMapper), objectMapper,
                appProperties, registry, approachResolver,
                new ru.lct.heating.trace.StageTraceWriter(objectMapper, crs),
                new ru.lct.heating.ingest.DatasetPartitioner(
                        new GeoJsonStreamReader(objectMapper), objectMapper),
                costModel);
    }

    /**
     * FR-32/ТП 2.2: трасса может входить в `oks`-полигон только финальным
     * прямым выводом к собственной точке подключения (ADR-0024).
     */
    protected void assertNoOksCrossingBeyondApproach(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode inputRoot = mapper.readTree(input.toFile());
        com.fasterxml.jackson.databind.JsonNode resultRoot = mapper.readTree(result.toFile());
        List<Geometry> oksPolygons = new ArrayList<>();
        Map<String, Coordinate> connectionPoints = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature : inputRoot.path("features")) {
            com.fasterxml.jackson.databind.JsonNode properties = feature.path("properties");
            String type = properties.path("object_type").asText();
            if ("restriction".equals(type)
                    && "oks".equals(properties.path("restriction_type").asText())) {
                oksPolygons.add(GeoJsonGeometryParser.parse(feature.path("geometry")));
            } else if ("oks_connection_point".equals(type)) {
                com.fasterxml.jackson.databind.JsonNode c = feature.path("geometry").path("coordinates");
                connectionPoints.put(properties.path("id").asText(),
                        new Coordinate(c.get(0).asDouble(), c.get(1).asDouble()));
            }
        }
        int violations = 0;
        for (com.fasterxml.jackson.databind.JsonNode feature : resultRoot.path("features")) {
            com.fasterxml.jackson.databind.JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            Geometry line = GeoJsonGeometryParser.parse(feature.path("geometry"));
            Coordinate startPoint = connectionPoints.get(properties.path("start_node_id").asText());
            Coordinate endPoint = connectionPoints.get(properties.path("end_node_id").asText());
            for (Geometry polygon : oksPolygons) {
                Geometry intersection = line.intersection(polygon);
                if (intersection.isEmpty() || intersection.getLength() < 1e-9) {
                    continue;
                }
                if (!touchesOwnPoint(intersection, polygon, startPoint)
                        && !touchesOwnPoint(intersection, polygon, endPoint)) {
                    violations++;
                }
            }
        }
        assertThat(violations).isZero();
    }

    private boolean touchesOwnPoint(Geometry intersection, Geometry polygon, Coordinate point) {
        if (point == null || !polygon.covers(ru.lct.heating.domain.GeometrySupport.GEOMETRY_FACTORY
                .createPoint(point))) {
            return false;
        }
        return intersection.distance(ru.lct.heating.domain.GeometrySupport.GEOMETRY_FACTORY
                .createPoint(point)) < 1e-9;
    }

    /**
     * FR-83: start_node_id/end_node_id совпадают с геометрическими концами
     * LineString (для узлов, присутствующих в выводе или во входных точках).
     */
    protected void assertNodeReferencesMatchGeometry(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        com.fasterxml.jackson.databind.JsonNode resultRoot = mapper.readTree(result.toFile());
        com.fasterxml.jackson.databind.JsonNode inputRoot = mapper.readTree(input.toFile());
        Map<String, double[]> nodes = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature : resultRoot.path("features")) {
            String type = feature.path("properties").path("object_type").asText();
            if (("heat_chamber".equals(type) || "technical_node".equals(type))
                    && !feature.path("geometry").isNull()) {
                com.fasterxml.jackson.databind.JsonNode c = feature.path("geometry").path("coordinates");
                nodes.put(feature.path("properties").path("id").asText(),
                        new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            }
        }
        for (com.fasterxml.jackson.databind.JsonNode feature : inputRoot.path("features")) {
            if ("oks_connection_point".equals(feature.path("properties").path("object_type").asText())) {
                com.fasterxml.jackson.databind.JsonNode c = feature.path("geometry").path("coordinates");
                nodes.put(feature.path("properties").path("id").asText(),
                        new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            }
        }
        int checked = 0;
        for (com.fasterxml.jackson.databind.JsonNode feature : resultRoot.path("features")) {
            if (!"heat_network".equals(feature.path("properties").path("object_type").asText())) {
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode coords = feature.path("geometry").path("coordinates");
            double[] first = {coords.get(0).get(0).asDouble(), coords.get(0).get(1).asDouble()};
            double[] last = {coords.get(coords.size() - 1).get(0).asDouble(),
                    coords.get(coords.size() - 1).get(1).asDouble()};
            checked += matchNode(nodes, feature.path("properties").path("start_node_id").asText(), first);
            checked += matchNode(nodes, feature.path("properties").path("end_node_id").asText(), last);
        }
        assertThat(checked).isGreaterThan(0);
    }

    /** Точка подключения — лист: ровно одно инцидентное ребро (FR-25). */
    protected void assertConnectionPointsAreLeaves(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        Set<String> connectionPoints = new java.util.HashSet<>();
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(input.toFile()).path("features")) {
            if ("oks_connection_point".equals(
                    feature.path("properties").path("object_type").asText())) {
                connectionPoints.add(feature.path("properties").path("id").asText());
            }
        }
        Map<String, Map<String, Integer>> degreeByVariant = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(result.toFile()).path("features")) {
            if (!"heat_network".equals(feature.path("properties").path("object_type").asText())) {
                continue;
            }
            String variant = feature.path("properties").path("variant_id").asText();
            Map<String, Integer> degree =
                    degreeByVariant.computeIfAbsent(variant, key -> new HashMap<>());
            degree.merge(feature.path("properties").path("start_node_id").asText(), 1, Integer::sum);
            degree.merge(feature.path("properties").path("end_node_id").asText(), 1, Integer::sum);
        }
        for (Map.Entry<String, Map<String, Integer>> entry : degreeByVariant.entrySet()) {
            for (String id : connectionPoints) {
                if (entry.getValue().containsKey(id)) {
                    assertThat(entry.getValue().get(id))
                            .as("точка подключения %s (вариант %s)", id, entry.getKey())
                            .isEqualTo(1);
                }
            }
        }
    }

    /** Инвариант FR-29: рёбра лучшего варианта не пересекаются вне общих узлов. */
    protected int countSelfIntersections(Path result, ObjectMapper mapper) throws Exception {
        String best = bestVariantId(result, mapper);
        List<OutputEdgeRef> edges = outputEdges(result, mapper, best);
        int violations = 0;
        for (int i = 0; i < edges.size(); i++) {
            for (int j = i + 1; j < edges.size(); j++) {
                OutputEdgeRef a = edges.get(i);
                OutputEdgeRef b = edges.get(j);
                if (shareNode(a, b)) {
                    continue;
                }
                Geometry intersection = a.line().intersection(b.line());
                if (!intersection.isEmpty()) {
                    violations++;
                }
            }
        }
        return violations;
    }

    /** Инвариант FR-34: в лучшем варианте нет поворотов более 90° (кроме стыка вывода). */
    protected int countTurnViolations(Path result, ObjectMapper mapper) throws Exception {
        return countTurnViolations(result, mapper, 90.0);
    }

    /**
     * Повороты более {@code maxDeg}. Для проверочных наборов допускается
     * квантизационный допуск (дискретность «ворот»/сетки).
     */
    protected int countTurnViolations(Path result, ObjectMapper mapper, double maxDeg)
            throws Exception {
        String best = bestVariantId(result, mapper);
        int violations = 0;
        for (OutputEdgeRef edge : outputEdges(result, mapper, best)) {
            org.locationtech.jts.geom.LineString projected = (org.locationtech.jts.geom.LineString)
                    crsTransformer.toUtm(edge.line());
            Coordinate[] coordinates = projected.getCoordinates();
            for (int i = 1; i < coordinates.length - 1; i++) {
                // Допуск 0.05° — погрешность round-trip WGS84↔UTM при измерении.
                if (turnAngle(coordinates[i - 1], coordinates[i], coordinates[i + 1])
                        > maxDeg + 0.05) {
                    violations++;
                }
            }
        }
        return violations;
    }

    /** Инвариант FR-26: к камере примыкает не более 4 участков. */
    protected int countChamberDegreeViolations(Path result, ObjectMapper mapper) throws Exception {
        String best = bestVariantId(result, mapper);
        Set<String> chambers = new HashSet<>();
        Map<String, Integer> degree = new HashMap<>();
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if (!best.equals(properties.path("variant_id").asText())) {
                continue;
            }
            if ("heat_chamber".equals(properties.path("object_type").asText())) {
                chambers.add(properties.path("id").asText());
            }
            if ("heat_network".equals(properties.path("object_type").asText())) {
                degree.merge(properties.path("start_node_id").asText(), 1, Integer::sum);
                degree.merge(properties.path("end_node_id").asText(), 1, Integer::sum);
            }
        }
        int violations = 0;
        for (String chamber : chambers) {
            if (degree.getOrDefault(chamber, 0) > 4) {
                violations++;
            }
        }
        return violations;
    }

    /** Инвариант FR-52: специальный проход — один прямой участок (≤2 точек). */
    protected int countSpecialBends(Path result, ObjectMapper mapper) throws Exception {
        String best = bestVariantId(result, mapper);
        int bends = 0;
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            if (best != null && !best.equals(properties.path("variant_id").asText())) {
                continue;
            }
            if ("special".equals(properties.path("laying_method").asText())
                    && feature.path("geometry").path("coordinates").size() > 2) {
                bends++;
            }
        }
        return bends;
    }

    /**
     * E43: канонический выход точки (резолвер) должен присутствовать вершиной в
     * терминальном ребре каждого варианта, идущем к этой точке.
     */
    protected int countMissingCanonicalExits(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        Map<String, double[]> canonical = canonicalExits(input);
        Map<String, double[]> points = new HashMap<>();
        for (JsonNode feature : mapper.readTree(input.toFile()).path("features")) {
            if ("oks_connection_point".equals(
                    feature.path("properties").path("object_type").asText())) {
                JsonNode c = feature.path("geometry").path("coordinates");
                points.put(feature.path("properties").path("id").asText(),
                        new double[]{c.get(0).asDouble(), c.get(1).asDouble()});
            }
        }
        Map<String, Boolean> found = new HashMap<>();
        for (String pointId : canonical.keySet()) {
            found.put(pointId, false);
        }
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            JsonNode coords = feature.path("geometry").path("coordinates");
            if (coords.size() < 2) {
                continue;
            }
            // E50: терминальное ребро может быть ориентировано в любую сторону —
            // проверяем точку подключения и в начале, и в конце.
            for (int endIndex : new int[]{0, coords.size() - 1}) {
                double[] end = {coords.get(endIndex).get(0).asDouble(),
                        coords.get(endIndex).get(1).asDouble()};
                for (Map.Entry<String, double[]> point : points.entrySet()) {
                    if (Math.hypot(point.getValue()[0] - end[0], point.getValue()[1] - end[1])
                            > 1e-9) {
                        continue;
                    }
                    double[] target = canonical.get(point.getKey());
                    if (target == null) {
                        continue;
                    }
                    for (JsonNode coordinate : coords) {
                        if (Math.hypot(coordinate.get(0).asDouble() - target[0],
                                coordinate.get(1).asDouble() - target[1]) < 1e-7) {
                            found.put(point.getKey(), true);
                        }
                    }
                }
            }
        }
        int missing = 0;
        for (Boolean value : found.values()) {
            if (!value) {
                missing++;
            }
        }
        return missing;
    }

    /** E42: все ссылки start_node_id/end_node_id должны разрешаться в узлы. */
    protected int countDanglingNodeReferences(Path input, Path result, ObjectMapper mapper)
            throws Exception {
        Set<String> known = new HashSet<>();
        for (JsonNode feature : mapper.readTree(input.toFile()).path("features")) {
            String type = feature.path("properties").path("object_type").asText();
            if ("heat_chamber".equals(type) || "oks_connection_point".equals(type)) {
                known.add(feature.path("properties").path("id").asText());
            }
        }
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            String type = feature.path("properties").path("object_type").asText();
            if ("heat_chamber".equals(type) || "technical_node".equals(type)) {
                known.add(feature.path("properties").path("id").asText());
            }
        }
        int dangling = 0;
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            for (String key : List.of("start_node_id", "end_node_id")) {
                String id = properties.path(key).asText();
                if (!id.isEmpty() && !known.contains(id)) {
                    dangling++;
                }
            }
        }
        return dangling;
    }

    /** Канонические выходы (WGS84) по резолверу сервиса. */
    protected Map<String, double[]> canonicalExits(Path input) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        CrsTransformer crs = new CrsTransformer();
        IngestService ingest = new IngestService(new GeoJsonStreamReader(objectMapper),
                new FeatureParser(crs));
        HeatingTablesProperties tables = diameters();
        DiameterCatalog catalog = new DiameterCatalog(tables);
        ru.lct.heating.routing.OksApproachResolver resolver =
                new ru.lct.heating.routing.OksApproachResolver(
                        new RestrictionRuleResolver(rules()), new EnvelopeCatalog(tables), catalog,
                        new AppProperties());
        ru.lct.heating.ingest.IngestResult ingested;
        try (var stream = Files.newInputStream(input)) {
            ingested = ingest.ingest(stream);
        }
        Map<String, double[]> result = new HashMap<>();
        resolver.resolveExits(ingested.getDataset()).forEach((id, exit) -> {
            if (!exit.isBlocked() && exit.getTarget() != null) {
                var point = (org.locationtech.jts.geom.Point) crs.toWgs84(
                        ru.lct.heating.domain.GeometrySupport.GEOMETRY_FACTORY
                                .createPoint(exit.getTarget()));
                result.put(id, new double[]{point.getX(), point.getY()});
            }
        });
        return result;
    }

    protected String bestVariantId(Path result, ObjectMapper mapper) throws Exception {
        String best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if ("variant_summary".equals(properties.path("object_type").asText())
                    && properties.path("score").asDouble() < bestScore) {
                bestScore = properties.path("score").asDouble();
                best = properties.path("variant_id").asText();
            }
        }
        return best;
    }

    protected List<OutputEdgeRef> outputEdges(Path result, ObjectMapper mapper, String variant)
            throws Exception {
        List<OutputEdgeRef> edges = new ArrayList<>();
        for (JsonNode feature : mapper.readTree(result.toFile()).path("features")) {
            JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            if (variant != null && !variant.equals(properties.path("variant_id").asText())) {
                continue;
            }
            edges.add(new OutputEdgeRef(properties.path("id").asText(),
                    properties.path("start_node_id").asText(),
                    properties.path("end_node_id").asText(),
                    (org.locationtech.jts.geom.LineString) GeoJsonGeometryParser.parse(
                            feature.path("geometry"))));
        }
        return edges;
    }

    private boolean shareNode(OutputEdgeRef a, OutputEdgeRef b) {
        return a.startNode().equals(b.startNode()) || a.startNode().equals(b.endNode())
                || a.endNode().equals(b.startNode()) || a.endNode().equals(b.endNode());
    }

    private double turnAngle(Coordinate previous, Coordinate vertex, Coordinate next) {
        double inX = vertex.x - previous.x;
        double inY = vertex.y - previous.y;
        double outX = next.x - vertex.x;
        double outY = next.y - vertex.y;
        return Math.toDegrees(Math.atan2(Math.abs(inX * outY - inY * outX), inX * outX + inY * outY));
    }

    /** Ссылка на ребро вывода для геометрических инвариантов. */
    protected static final class OutputEdgeRef {
        private final String id;
        private final String startNode;
        private final String endNode;
        private final org.locationtech.jts.geom.LineString line;
        OutputEdgeRef(String id, String startNode, String endNode,
                      org.locationtech.jts.geom.LineString line) {
            this.id = id;
            this.startNode = startNode;
            this.endNode = endNode;
            this.line = line;
        }
        String id() {
            return id;
        }
        String startNode() {
            return startNode;
        }
        String endNode() {
            return endNode;
        }
        org.locationtech.jts.geom.LineString line() {
            return line;
        }
    }

    /**
     * Регресс зигзагов: каждый участок новой сети имеет не более
     * {@code maxPerEdge} вершин (ADR-0034, локальный ремонт поворотов).
     */
    protected void assertNoExcessiveVertices(Path result, ObjectMapper mapper, int maxPerEdge)
            throws Exception {
        int checked = 0;
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(result.toFile()).path("features")) {
            if (!"heat_network".equals(feature.path("properties").path("object_type").asText())) {
                continue;
            }
            int vertices = feature.path("geometry").path("coordinates").size();
            assertThat(vertices).as("вершины участка %s",
                    feature.path("properties").path("id").asText()).isLessThanOrEqualTo(maxPerEdge);
            checked++;
        }
        assertThat(checked).isGreaterThan(0);
    }

    /**
     * Геометрический регресс присоединения (ADR-0035): ребро, инцидентное точке
     * подключения с координатами {@code lon,lat} (WGS84), короче {@code maxM}.
     */
    protected void assertEdgeAtPointShorterThan(Path result, ObjectMapper mapper, double lon,
                                                double lat, double maxM) throws Exception {
        double bestDistance = Double.POSITIVE_INFINITY;
        double edgeLength = Double.NaN;
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(result.toFile()).path("features")) {
            com.fasterxml.jackson.databind.JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())) {
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode coordinates = feature.path("geometry")
                    .path("coordinates");
            for (com.fasterxml.jackson.databind.JsonNode endpoint : List.of(
                    coordinates.get(0), coordinates.get(coordinates.size() - 1))) {
                double dx = (endpoint.get(0).asDouble() - lon) * Math.cos(Math.toRadians(lat));
                double dy = endpoint.get(1).asDouble() - lat;
                double distance = Math.hypot(dx, dy);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    edgeLength = properties.path("length").asDouble();
                }
            }
        }
        assertThat(bestDistance).as("точка подключения найдена в выводе").isLessThan(2e-5);
        assertThat(edgeLength).as("длина ребра к точке (%s,%s)", lon, lat).isLessThan(maxM);
    }

    /** Ветвления (степень ≥3) допускаются только в камерах (FR-25). */
    protected void assertBranchOnlyInChambers(Path result, ObjectMapper mapper) throws Exception {
        Set<String> chambers = new java.util.HashSet<>();
        Map<String, Integer> degree = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature
                : mapper.readTree(result.toFile()).path("features")) {
            String type = feature.path("properties").path("object_type").asText();
            String id = feature.path("properties").path("id").asText();
            if ("heat_chamber".equals(type)) {
                chambers.add(id);
            }
            if ("heat_network".equals(type)) {
                degree.merge(feature.path("properties").path("start_node_id").asText(), 1,
                        Integer::sum);
                degree.merge(feature.path("properties").path("end_node_id").asText(), 1,
                        Integer::sum);
            }
        }
        assertThat(degree).isNotEmpty();
        for (Map.Entry<String, Integer> entry : degree.entrySet()) {
            if (entry.getValue() >= 3) {
                assertThat(chambers).as("ветвление в узле %s", entry.getKey())
                        .contains(entry.getKey());
            }
        }
    }

    /**
     * ADR-0039: точки подключения сходятся на одной камере. От каждой точки
     * идём по рёбрам через узлы степени 2 (технические) до первой камеры
     * (степень != 2) и проверяем, что камера общая. Вариант — лучший по score.
     */
    protected void assertConnectionPointsShareChamber(Path result, ObjectMapper mapper,
                                                      String... pointIds) throws Exception {
        com.fasterxml.jackson.databind.JsonNode features =
                mapper.readTree(result.toFile()).path("features");
        String best = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (com.fasterxml.jackson.databind.JsonNode feature : features) {
            com.fasterxml.jackson.databind.JsonNode properties = feature.path("properties");
            if ("variant_summary".equals(properties.path("object_type").asText())
                    && properties.path("score").asDouble() < bestScore) {
                bestScore = properties.path("score").asDouble();
                best = properties.path("variant_id").asText();
            }
        }
        Map<String, Integer> degree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (com.fasterxml.jackson.databind.JsonNode feature : features) {
            com.fasterxml.jackson.databind.JsonNode properties = feature.path("properties");
            if (!"heat_network".equals(properties.path("object_type").asText())
                    || (best != null && !best.equals(properties.path("variant_id").asText()))) {
                continue;
            }
            String a = properties.path("start_node_id").asText();
            String b = properties.path("end_node_id").asText();
            adjacency.computeIfAbsent(a, key -> new ArrayList<>()).add(b);
            adjacency.computeIfAbsent(b, key -> new ArrayList<>()).add(a);
            degree.merge(a, 1, Integer::sum);
            degree.merge(b, 1, Integer::sum);
        }
        Set<String> chambers = new java.util.LinkedHashSet<>();
        for (String point : pointIds) {
            List<String> neighbours = adjacency.get(point);
            assertThat(neighbours).as("ребро точки подключения %s", point).hasSize(1);
            String previous = point;
            String current = neighbours.get(0);
            Set<String> visited = new HashSet<>();
            while (degree.getOrDefault(current, 0) == 2 && visited.add(current)) {
                List<String> next = adjacency.get(current);
                String step = next.get(0).equals(previous) ? next.get(1) : next.get(0);
                previous = current;
                current = step;
            }
            chambers.add(current);
        }
        assertThat(chambers).as("общая камера точек %s", String.join(", ", pointIds)).hasSize(1);
    }

    private int matchNode(Map<String, double[]> nodes, String nodeId, double[] point) {
        double[] node = nodes.get(nodeId);
        if (node == null) {
            return 0;
        }
        double distance = Math.hypot(node[0] - point[0], node[1] - point[1]);
        assertThat(distance).isLessThan(1e-5);
        return 1;
    }

    protected HeatingTablesProperties diameters() {
        HeatingTablesProperties properties = new HeatingTablesProperties();
        List<DiameterRow> rows = new ArrayList<>();
        rows.add(row(50, 3.5, 181, 74023));
        rows.add(row(65, 8.3, 245, 78631));
        rows.add(row(80, 13.2, 327, 83530));
        rows.add(row(100, 22.3, 419, 89748));
        rows.add(row(125, 40.2, 554, 97275));
        rows.add(row(150, 65.1, 696, 105507));
        rows.add(row(200, 152.3, 1042, 120275));
        rows.add(row(250, 274.9, 1379, 135323));
        rows.add(row(300, 437.4, 1718, 150022));
        rows.add(row(400, 943.1, 2477, 190299));
        rows.add(row(500, 1663.4, 3245, 224137));
        rows.add(row(600, 2627.7, 4037, 264790));
        rows.add(row(700, 3735.1, 4775, 324298));
        rows.add(row(800, 5296.8, 5644, 325996));
        rows.add(row(900, 7165.0, 6518, 327693));
        rows.add(row(1000, 9391.8, 7419, 418777));
        rows.add(row(1200, 15012.8, 9288, 428074));
        rows.add(row(1400, 22501.9, 11276, 683417));
        properties.setDiameters(rows);
        properties.setEnvelopes(List.of(
                envelope(50, 0.400, 0.125),
                envelope(65, 0.430, 0.140),
                envelope(80, 0.470, 0.160),
                envelope(100, 0.510, 0.180),
                envelope(125, 0.600, 0.225),
                envelope(150, 0.650, 0.250),
                envelope(200, 0.880, 0.315),
                envelope(250, 1.050, 0.400),
                envelope(300, 1.150, 0.450),
                envelope(400, 1.370, 0.560),
                envelope(500, 1.670, 0.710),
                envelope(600, 1.850, 0.800),
                envelope(700, 2.050, 0.900),
                envelope(800, 2.250, 1.000),
                envelope(900, 2.450, 1.100),
                envelope(1000, 2.650, 1.200),
                envelope(1200, 3.100, 1.425),
                envelope(1400, 3.450, 1.600)));
        return properties;
    }

    private EnvelopeRow envelope(int dn, double pairWidth, double height) {
        EnvelopeRow row = new EnvelopeRow();
        row.setDn(dn);
        row.setPairWidthM(pairWidth);
        row.setHeightM(height);
        return row;
    }

    private DiameterRow row(int dn, double capacity, double length, long cost) {
        DiameterRow row = new DiameterRow();
        row.setDn(dn);
        row.setCapacityTph(capacity);
        row.setMaxLengthM(length);
        row.setNewCostPerM(cost);
        return row;
    }

    /** Правила — как в реальном {@code application.yml} (E39). */
    protected RestrictionRulesProperties rules() {
        RestrictionRulesProperties properties = new RestrictionRulesProperties();
        Map<String, RestrictionRule> rules = new LinkedHashMap<>();
        rules.put("oks", prohibitedWithOksBands());
        rules.put("park", prohibited(1.0));
        rules.put("social_area", prohibited(1.0));
        rules.put("prohibited_site", prohibited(1.0));
        rules.put("water", prohibited(1.0));
        rules.put("railway", prohibited(1.0));
        rules.put("road", special(1.5, 45.0, 1.60, 3.0));
        rules.put("tram_tracks", special(1.5, 45.0, 1.75, 3.0));
        rules.put("gas_pipeline", special(2.0, null, 1.25, null));
        rules.put("power_cable", special(2.0, null, 1.15, null));
        rules.put("heat_network", special(1.0, null, 1.05, null));
        properties.setRules(rules);
        properties.setFallback(prohibited(1.0));
        return properties;
    }

    private RestrictionRule prohibitedWithOksBands() {
        RestrictionRule rule = prohibited(9.0);
        List<DistanceBand> bands = new ArrayList<>();
        bands.add(band(499, 5.0));
        bands.add(band(800, 7.0));
        bands.add(band(100000, 9.0));
        rule.setDistanceBands(bands);
        return rule;
    }

    private DistanceBand band(int maxDn, double distance) {
        DistanceBand band = new DistanceBand();
        band.setMaxDn(maxDn);
        band.setDistanceM(distance);
        return band;
    }

    private RestrictionRule prohibited(double distance) {
        RestrictionRule rule = new RestrictionRule();
        rule.setMode(RestrictionMode.PROHIBITED);
        rule.setMinDistanceM(distance);
        return rule;
    }

    private RestrictionRule special(double distance, Double angle, double k, Double zoneBuffer) {
        RestrictionRule rule = new RestrictionRule();
        rule.setMode(RestrictionMode.SPECIAL);
        rule.setMinDistanceM(distance);
        rule.setAngleMinDeg(angle);
        rule.setKSpecial(k);
        rule.setSpecialZoneBufferM(zoneBuffer);
        return rule;
    }
}
