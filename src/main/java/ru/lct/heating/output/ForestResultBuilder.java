package ru.lct.heating.output;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.lct.heating.calculation.CalculationMode;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.depth.DepthProfileBuilder;
import ru.lct.heating.depth.DepthSegment;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.geometry.ObstacleIndex;
import ru.lct.heating.geometry.RouteChunk;
import ru.lct.heating.geometry.SpecialSpan;
import ru.lct.heating.geometry.SpecialSpanSplitter;
import ru.lct.heating.geometry.SpecialZoneIndex;
import ru.lct.heating.hydraulics.Rounding;
import ru.lct.heating.ingest.CrsTransformer;
import ru.lct.heating.routing.ForestEdge;
import ru.lct.heating.routing.ForestNode;
import ru.lct.heating.routing.ForestPlanningResult;
import ru.lct.heating.routing.ForestTree;
import ru.lct.heating.routing.NodeType;

/**
 * Формирование результата варианта (ТП v2 §6, §7): спецпроходы, округление,
 * камеры (включая присоединение) и технические узлы. Без реконструкции и
 * `tie_in`.
 */
@Component
public class ForestResultBuilder {

    public static final String DEFAULT_VARIANT_ID = "1";

    private final CrsTransformer crsTransformer;
    private final CostModel costModel;
    private final SpecialSpanSplitter spanSplitter;
    private final DepthProfileBuilder depthProfileBuilder;
    private final AppProperties appProperties;

    public ForestResultBuilder(CrsTransformer crsTransformer, CostModel costModel,
                               SpecialSpanSplitter spanSplitter,
                               DepthProfileBuilder depthProfileBuilder,
                               AppProperties appProperties) {
        this.crsTransformer = crsTransformer;
        this.costModel = costModel;
        this.spanSplitter = spanSplitter;
        this.depthProfileBuilder = depthProfileBuilder;
        this.appProperties = appProperties;
    }

    public VariantResult build(ForestPlanningResult planning, NetworkDataset dataset,
                               SpecialZoneIndex specialZones, ObstacleIndex obstacleIndex,
                               List<String> warnings) {
        return build(planning, dataset, specialZones, obstacleIndex, warnings, DEFAULT_VARIANT_ID, 1,
                planning.getPassNumber(), CalculationMode.TWO_D);
    }

    public VariantResult build(ForestPlanningResult planning, NetworkDataset dataset,
                               SpecialZoneIndex specialZones, ObstacleIndex obstacleIndex,
                               List<String> warnings, String variantId, int rank, int passNumber) {
        return build(planning, dataset, specialZones, obstacleIndex, warnings, variantId, rank,
                passNumber, CalculationMode.TWO_D);
    }

    public VariantResult build(ForestPlanningResult planning, NetworkDataset dataset,
                               SpecialZoneIndex specialZones, ObstacleIndex obstacleIndex,
                               List<String> warnings, String variantId, int rank, int passNumber,
                               CalculationMode mode) {
        boolean depthMode = mode != null && mode.isDepth();
        List<OutputSegment> segments = new ArrayList<>();
        List<OutputChamber> chambers = new ArrayList<>();
        List<OutputTechnicalNode> technicalNodes = new ArrayList<>();

        long constructionCost = 0L;
        long chamberConstructionCost = 0L;
        long existingTieInCost = 0L;
        int existingTieInCount = 0;
        double newLength = 0.0;

        // E27-06: все рёбра нового леса — чтобы не выпрямлять спецучасток хордой,
        // пересекающей другое ребро.
        List<LineString> treeLines = new ArrayList<>();
        for (ForestTree tree : planning.getTrees()) {
            for (ForestEdge edge : tree.getEdges()) {
                treeLines.add(GeometrySupport.GEOMETRY_FACTORY.createLineString(
                        edge.getCoordinates().toArray(new Coordinate[0])));
            }
        }

        for (ForestTree tree : planning.getTrees()) {
            ForestNode connectionNode = tree.requireNode(tree.getTieInNodeId());
            Map<String, Integer> chamberDiameter = new HashMap<>();
            for (ForestEdge edge : tree.getEdges()) {
                chamberDiameter.merge(edge.getFromNodeId(), edge.getDiameterMm(), Math::max);
                chamberDiameter.merge(edge.getToNodeId(), edge.getDiameterMm(), Math::max);
            }
            // Идентификаторы узлов уникальны в пределах варианта: сгенерированные
            // камеры/тех. узлы получают префикс variant_id, существующие камеры и
            // точки подключения сохраняют входные id.
            Map<String, String> nodeIds = outputNodeIds(variantId, tree);
            int connectionDiameter = chamberDiameter.getOrDefault(connectionNode.getId(), 0);
            if (connectionNode.isExisting()) {
                int tieIns = tieInCount(connectionNode.getId(), tree.getEdges());
                existingTieInCount += tieIns;
                existingTieInCost += (long) tieIns * costModel.existingChamberTieInCost();
            } else {
                long chamberCost = costModel.chamberCost(connectionDiameter);
                chamberConstructionCost += chamberCost;
                chambers.add(OutputChamber.builder()
                        .id(nodeIds.get(connectionNode.getId()))
                        .diameterMm(connectionDiameter)
                        .cost(chamberCost)
                        .geometryWgs84(toWgs84Point(connectionNode.getCoordinate()))
                        .build());
            }

            // E42: планировочные технические узлы (границы смены параметров, в т.ч.
            // base↔special) должны быть выпущены — иначе ссылки висят.
            for (ForestNode node : tree.getNodes().values()) {
                if (node.getType() == NodeType.TECHNICAL_NODE && !node.isExisting()) {
                    technicalNodes.add(OutputTechnicalNode.builder()
                            .id(nodeIds.get(node.getId()))
                            .geometryWgs84(toWgs84Point(node.getCoordinate()))
                            .build());
                }
            }

            for (ForestNode node : tree.getNodes().values()) {
                if (node.getType() == NodeType.CHAMBER && !node.isExisting()
                        && !node.getId().equals(connectionNode.getId())) {
                    int diameterMm = chamberDiameter.getOrDefault(node.getId(), 0);
                    long chamberCost = costModel.chamberCost(diameterMm);
                    chamberConstructionCost += chamberCost;
                    chambers.add(OutputChamber.builder()
                            .id(nodeIds.get(node.getId()))
                            .diameterMm(diameterMm)
                            .cost(chamberCost)
                            .geometryWgs84(toWgs84Point(node.getCoordinate()))
                            .build());
                }
            }

            for (ForestEdge edge : tree.getEdges()) {
                LineString line = GeometrySupport.GEOMETRY_FACTORY.createLineString(
                        edge.getCoordinates().toArray(new Coordinate[0]));
                checkTurns(line, edge.getId(), warnings);
                List<EdgePiece> pieces = depthMode
                        ? depthPieces(line, edge, specialZones, warnings)
                        : List.of(new EdgePiece(line, 0.0, line.getLength(),
                                appProperties.getDepthNormalM(), appProperties.getDepthNormalM(), 1.0));
                for (int p = 0; p < pieces.size(); p++) {
                    EdgePiece piece = pieces.get(p);
                    LineString pieceLine = piece.geometry;
                    List<SpecialSpan> spans = specialZones.spans(pieceLine, warnings);
                    List<RouteChunk> chunks = spanSplitter.split(pieceLine, spans, obstacleIndex,
                            warnings, treeLines, pieceLine);
                    String firstStart = p == 0
                            ? nodeIds.get(edge.getFromNodeId())
                            : techNodeId(variantId, edge, piece.startDistance,
                                    pieceLine.getCoordinateN(0), technicalNodes);
                    String lastEnd = p == pieces.size() - 1
                            ? nodeIds.get(edge.getToNodeId())
                            : techNodeId(variantId, edge, piece.endDistance,
                                    pieceLine.getCoordinateN(pieceLine.getNumPoints() - 1),
                                    technicalNodes);
                    double distance = 0.0;
                    int chunkIndex = 0;
                    for (RouteChunk chunk : chunks) {
                        double length = Rounding.roundUp(chunk.getGeometry().getLength(),
                                appProperties.getRoundingToleranceM());
                        long cost = costModel.segmentCost(length, edge.getDiameterMm(),
                                piece.kDepth, chunk.getKSpecial());
                        constructionCost += cost;
                        newLength += length;

                        String startNode = chunkIndex == 0 ? firstStart
                                : techNodeId(variantId, edge, piece.startDistance + distance,
                                        chunk.getGeometry().getCoordinateN(0), technicalNodes);
                        String endNode = chunkIndex == chunks.size() - 1 ? lastEnd
                                : techNodeId(variantId, edge,
                                        piece.startDistance + distance
                                                + chunk.getGeometry().getLength(),
                                        chunk.getGeometry().getCoordinateN(
                                                chunk.getGeometry().getNumPoints() - 1),
                                        technicalNodes);
                        segments.add(OutputSegment.builder()
                                .id(edge.getId() + "_" + segments.size())
                                .startNodeId(startNode)
                                .endNodeId(endNode)
                                .flowTph(edge.getFlowTph())
                                .diameterMm(edge.getDiameterMm())
                                .lengthM(length)
                                .layingMethod(chunk.layingMethod())
                                .depthStart(depthMode ? piece.depthAt(distance) : null)
                                .depthEnd(depthMode
                                        ? piece.depthAt(distance + chunk.getGeometry().getLength())
                                        : null)
                                .cost(cost)
                                .geometryWgs84((LineString) crsTransformer.toWgs84(
                                        chunk.getGeometry()))
                                .build());
                        distance += chunk.getGeometry().getLength();
                        chunkIndex++;
                    }
                }
            }
        }

        long unconnectedPenalty = unconnectedPenalty(planning, dataset);
        long calculatedCost = constructionCost + chamberConstructionCost + existingTieInCost
                + unconnectedPenalty;
        double score = costModel.score(calculatedCost, newLength);

        VariantSummary summary = VariantSummary.builder()
                .variantId(variantId)
                .rank(rank)
                .passNumber(passNumber)
                .constructionCost(constructionCost + chamberConstructionCost + existingTieInCost)
                .chamberConstructionCost(chamberConstructionCost)
                .existingChamberTieInCount(existingTieInCount)
                .existingChamberTieInCost(existingTieInCost)
                .unconnectedPenalty(unconnectedPenalty)
                .calculatedCost(calculatedCost)
                .newNetworkLengthM(newLength)
                .score(score)
                .unconnectedOksIds(planning.getUnconnectedConnectionPointIds())
                .numericOksIds(numericIds(planning, dataset))
                .build();

        return VariantResult.builder()
                .variantId(variantId)
                .segments(segments)
                .chambers(chambers)
                .technicalNodes(technicalNodes)
                .summary(summary)
                .gridReport(planning.getGridReport())
                .build();
    }

    /**
     * Диагностика углов поворота: допускается до 90° включительно (ТП 2.1).
     */
    private void checkTurns(LineString line, String edgeId, List<String> warnings) {
        Coordinate[] coordinates = line.getCoordinates();
        for (int i = 1; i < coordinates.length - 1; i++) {
            Coordinate previous = coordinates[i - 1];
            Coordinate vertex = coordinates[i];
            Coordinate next = coordinates[i + 1];
            double inX = vertex.x - previous.x;
            double inY = vertex.y - previous.y;
            double outX = next.x - vertex.x;
            double outY = next.y - vertex.y;
            double angle = Math.toDegrees(Math.atan2(
                    Math.abs(inX * outY - inY * outX), inX * outX + inY * outY));
            if (angle > 90.0 + 1e-6) {
                warnings.add("TURN_ANGLE_EXCEEDS_90: участок " + edgeId
                        + " угол " + Math.round(angle) + "°");
            }
        }
    }

    /**
     * FR-73: врезка = каждый новый линейный участок, заканчивающийся в
     * существующей камере. Считаем все инцидентные рёбра (независимо от
     * ориентации) — единообразно с {@code TerminalRelinker}.
     */
    private int tieInCount(String nodeId, List<ForestEdge> edges) {
        int count = 0;
        for (ForestEdge edge : edges) {
            if (edge.getFromNodeId().equals(nodeId) && edge.getToNodeId().equals(nodeId)) {
                continue;
            }
            if (edge.getFromNodeId().equals(nodeId) || edge.getToNodeId().equals(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private Map<String, String> outputNodeIds(String variantId, ForestTree tree) {
        Map<String, String> ids = new HashMap<>();
        for (ForestNode node : tree.getNodes().values()) {
            ids.put(node.getId(), outputNodeId(variantId, node));
        }
        return ids;
    }

    private String outputNodeId(String variantId, ForestNode node) {
        if (node.isExisting() && node.getExistingObjectId() != null) {
            return node.getExistingObjectId();
        }
        if (node.getType() == NodeType.CONNECTION_POINT) {
            return node.getId();
        }
        return variantId + "_" + node.getId();
    }

    private String techNodeId(String variantId, ForestEdge edge, double distance, Coordinate coordinate,
                              List<OutputTechnicalNode> technicalNodes) {
        String id = variantId + "_tech_" + edge.getId() + "_" + Math.round(distance * 1000.0);
        for (OutputTechnicalNode node : technicalNodes) {
            if (node.getId().equals(id)) {
                return id;
            }
        }
        technicalNodes.add(OutputTechnicalNode.builder()
                .id(id)
                .geometryWgs84(toWgs84Point(coordinate))
                .build());
        return id;
    }

    /**
     * ADR-0073: разбиение ребра на фрагменты вертикального профиля. Вне режима
     * глубины возвращается единственный фрагмент с обычной глубиной.
     */
    private List<EdgePiece> depthPieces(LineString line, ForestEdge edge,
                                        SpecialZoneIndex specialZones, List<String> warnings) {
        List<SpecialSpan> raw = specialZones.rawSpans(line, warnings);
        List<DepthSegment> profile = depthProfileBuilder.build(line.getLength(),
                edge.getDiameterMm(), raw, warnings);
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        List<EdgePiece> pieces = new ArrayList<>();
        for (DepthSegment segment : profile) {
            if (segment.lengthM() <= 1e-9) {
                continue;
            }
            LineString geometry = (LineString) indexed.extractLine(segment.getStartDistanceM(),
                    segment.getEndDistanceM());
            pieces.add(new EdgePiece(geometry, segment.getStartDistanceM(),
                    segment.getEndDistanceM(), segment.getDepthStartM(), segment.getDepthEndM(),
                    segment.getKDepth()));
        }
        if (pieces.isEmpty()) {
            double normal = appProperties.getDepthNormalM();
            pieces.add(new EdgePiece(line, 0.0, line.getLength(), normal, normal, 1.0));
        }
        return pieces;
    }

    /** Фрагмент ребра с линейным вертикальным профилем (ADR-0073). */
    private static final class EdgePiece {
        final LineString geometry;
        final double startDistance;
        final double endDistance;
        final double depthStart;
        final double depthEnd;
        final double kDepth;

        EdgePiece(LineString geometry, double startDistance, double endDistance,
                  double depthStart, double depthEnd, double kDepth) {
            this.geometry = geometry;
            this.startDistance = startDistance;
            this.endDistance = endDistance;
            this.depthStart = depthStart;
            this.depthEnd = depthEnd;
            this.kDepth = kDepth;
        }

        /** Глубина (до верхней границы габарита) на расстоянии от начала фрагмента. */
        double depthAt(double localDistance) {
            double length = endDistance - startDistance;
            if (length <= 1e-9) {
                return depthEnd;
            }
            double ratio = Math.max(0.0, Math.min(1.0, localDistance / length));
            return depthStart + (depthEnd - depthStart) * ratio;
        }
    }

    private long unconnectedPenalty(ForestPlanningResult planning, NetworkDataset dataset) {
        long penalty = 0L;
        for (String id : planning.getUnconnectedConnectionPointIds()) {
            penalty += costModel.unconnectedPenalty(flowOf(id, dataset));
        }
        return penalty;
    }

    private Set<String> numericIds(ForestPlanningResult planning, NetworkDataset dataset) {
        Set<String> unconnected = new HashSet<>(planning.getUnconnectedConnectionPointIds());
        Set<String> numeric = new HashSet<>();
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            if (unconnected.contains(connectionPoint.getId()) && connectionPoint.isNumericId()) {
                numeric.add(connectionPoint.getId());
            }
        }
        return numeric;
    }

    private double flowOf(String connectionPointId, NetworkDataset dataset) {
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            if (connectionPoint.getId().equals(connectionPointId)) {
                return connectionPoint.getFlowTph() == null ? 0.0 : connectionPoint.getFlowTph();
            }
        }
        return 0.0;
    }

    private Point toWgs84Point(Coordinate coordinate) {
        Point point = GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate);
        return (Point) crsTransformer.toWgs84(point);
    }
}
