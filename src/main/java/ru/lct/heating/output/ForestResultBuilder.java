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
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
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
    private final AppProperties appProperties;

    public ForestResultBuilder(CrsTransformer crsTransformer, CostModel costModel,
                               SpecialSpanSplitter spanSplitter, AppProperties appProperties) {
        this.crsTransformer = crsTransformer;
        this.costModel = costModel;
        this.spanSplitter = spanSplitter;
        this.appProperties = appProperties;
    }

    public VariantResult build(ForestPlanningResult planning, NetworkDataset dataset,
                               SpecialZoneIndex specialZones, List<String> warnings) {
        return build(planning, dataset, specialZones, warnings, DEFAULT_VARIANT_ID, 1);
    }

    public VariantResult build(ForestPlanningResult planning, NetworkDataset dataset,
                               SpecialZoneIndex specialZones, List<String> warnings,
                               String variantId, int rank) {
        List<OutputSegment> segments = new ArrayList<>();
        List<OutputChamber> chambers = new ArrayList<>();
        List<OutputTechnicalNode> technicalNodes = new ArrayList<>();

        long constructionCost = 0L;
        long chamberConstructionCost = 0L;
        long existingTieInCost = 0L;
        int existingTieInCount = 0;
        double newLength = 0.0;

        for (ForestTree tree : planning.getTrees()) {
            ForestNode connectionNode = tree.requireNode(tree.getTieInNodeId());
            Map<String, Integer> chamberDiameter = new HashMap<>();
            for (ForestEdge edge : tree.getEdges()) {
                chamberDiameter.merge(edge.getFromNodeId(), edge.getDiameterMm(), Math::max);
                chamberDiameter.merge(edge.getToNodeId(), edge.getDiameterMm(), Math::max);
            }
            int connectionDiameter = chamberDiameter.getOrDefault(connectionNode.getId(), 0);
            if (connectionNode.isExisting()) {
                int tieIns = countOutgoing(connectionNode.getId(), tree.getEdges());
                existingTieInCount += tieIns;
                existingTieInCost += (long) tieIns * costModel.existingChamberTieInCost();
            } else {
                long chamberCost = costModel.chamberCost(connectionDiameter);
                chamberConstructionCost += chamberCost;
                chambers.add(OutputChamber.builder()
                        .id(connectionNode.getId())
                        .diameterMm(connectionDiameter)
                        .cost(chamberCost)
                        .geometryWgs84(toWgs84Point(connectionNode.getCoordinate()))
                        .build());
            }

            for (ForestNode node : tree.getNodes().values()) {
                if (node.getType() == NodeType.CHAMBER && !node.isExisting()
                        && !node.getId().equals(connectionNode.getId())) {
                    int diameterMm = chamberDiameter.getOrDefault(node.getId(), 0);
                    long chamberCost = costModel.chamberCost(diameterMm);
                    chamberConstructionCost += chamberCost;
                    chambers.add(OutputChamber.builder()
                            .id(node.getId())
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
                List<SpecialSpan> spans = specialZones.spans(line, warnings);
                List<RouteChunk> chunks = spanSplitter.split(line, spans);
                double distance = 0.0;
                int chunkIndex = 0;
                for (RouteChunk chunk : chunks) {
                    double length = Rounding.roundUp(chunk.getGeometry().getLength(),
                            appProperties.getRoundingToleranceM());
                    long cost = costModel.segmentCost(length, edge.getDiameterMm(), 1.0,
                            chunk.getKSpecial());
                    constructionCost += cost;
                    newLength += length;

                    String startNode = chunkIndex == 0
                            ? edge.getFromNodeId()
                            : techNodeId(edge, distance, chunk.getGeometry().getCoordinateN(0),
                                    technicalNodes);
                    String endNode = chunkIndex == chunks.size() - 1
                            ? edge.getToNodeId()
                            : techNodeId(edge, distance + chunk.getGeometry().getLength(),
                                    chunk.getGeometry().getCoordinateN(chunk.getGeometry().getNumPoints() - 1),
                                    technicalNodes);
                    segments.add(OutputSegment.builder()
                            .id(edge.getId() + "_" + segments.size())
                            .startNodeId(startNode)
                            .endNodeId(endNode)
                            .flowTph(edge.getFlowTph())
                            .diameterMm(edge.getDiameterMm())
                            .lengthM(length)
                            .layingMethod(chunk.layingMethod())
                            .depthStart(null)
                            .depthEnd(null)
                            .cost(cost)
                            .geometryWgs84((LineString) crsTransformer.toWgs84(chunk.getGeometry()))
                            .build());
                    distance += chunk.getGeometry().getLength();
                    chunkIndex++;
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

    private int countOutgoing(String nodeId, List<ForestEdge> edges) {
        int count = 0;
        for (ForestEdge edge : edges) {
            if (edge.getFromNodeId().equals(nodeId) && !edge.getToNodeId().equals(nodeId)) {
                count++;
            }
        }
        return count;
    }

    private String techNodeId(ForestEdge edge, double distance, Coordinate coordinate,
                              List<OutputTechnicalNode> technicalNodes) {
        String id = "tech_" + edge.getId() + "_" + Math.round(distance * 1000.0);
        technicalNodes.add(OutputTechnicalNode.builder()
                .id(id)
                .geometryWgs84(toWgs84Point(coordinate))
                .build());
        return id;
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
