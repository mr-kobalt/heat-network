package ru.lct.heating.output;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.lct.heating.cost.CostModel;
import ru.lct.heating.domain.GeometrySupport;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.OksFutureObject;
import ru.lct.heating.hydraulics.DiameterCatalog;
import ru.lct.heating.ingest.CrsTransformer;
import ru.lct.heating.routing.PlannedConnection;
import ru.lct.heating.routing.RoutePlanningResult;

/**
 * Формирование результата варианта: разбиение маршрутов на участки,
 * подбор Ду, расчёт стоимости и сводки (ТП 8, 9, 10).
 */
@Component
public class ResultBuilder {

    public static final String DEFAULT_VARIANT_ID = "1";

    private final CrsTransformer crsTransformer;
    private final DiameterCatalog diameters;
    private final CostModel costModel;

    public ResultBuilder(CrsTransformer crsTransformer, DiameterCatalog diameters, CostModel costModel) {
        this.crsTransformer = crsTransformer;
        this.diameters = diameters;
        this.costModel = costModel;
    }

    public VariantResult build(RoutePlanningResult planning, NetworkDataset dataset) {
        List<OutputSegment> segments = new ArrayList<>();
        List<OutputTieIn> tieIns = new ArrayList<>();
        List<OutputChamber> chambers = new ArrayList<>();
        List<OutputTechnicalNode> technicalNodes = new ArrayList<>();

        long constructionCost = 0L;
        long chamberConstructionCost = 0L;
        double newLength = 0.0;

        for (PlannedConnection connection : planning.getConnections()) {
            OksConnectionPointObject connectionPoint = connection.getConnectionPoint();
            int diameterMm = diameters.select(connection.getFlowTph()).getDn();
            String tieInId = "tie_" + connectionPoint.getId();

            tieIns.add(OutputTieIn.builder()
                    .id(tieInId)
                    .existingObjectId(connection.getRoute().getTieIn().getExistingObjectId())
                    .existingObjectType(connection.getRoute().getTieIn().getExistingObjectType())
                    .existingDiameterMm(connection.getRoute().getTieIn().getExistingDiameterMm())
                    .requiredDiameterMm(diameterMm)
                    .cost(CostModel.TIE_IN_COST)
                    .geometryWgs84(toWgs84Point(connection.getRoute().getTieIn().getCoordinate()))
                    .build());

            boolean existingChamber = "heat_chamber".equals(
                    connection.getRoute().getTieIn().getExistingObjectType());
            if (!existingChamber) {
                long chamberCost = costModel.chamberCost(diameterMm);
                chamberConstructionCost += chamberCost;
                chambers.add(OutputChamber.builder()
                        .id("ch_" + connectionPoint.getId())
                        .diameterMm(diameterMm)
                        .cost(chamberCost)
                        .geometryWgs84(toWgs84Point(connection.getRoute().getTieIn().getCoordinate()))
                        .build());
            }

            List<Coordinate> coordinates = connection.getRoute().getCoordinates();
            for (int index = 0; index < coordinates.size() - 1; index++) {
                Coordinate start = coordinates.get(index);
                Coordinate end = coordinates.get(index + 1);
                double length = start.distance(end);
                LineString segment = GeometrySupport.GEOMETRY_FACTORY
                        .createLineString(new Coordinate[]{start, end});
                long cost = costModel.segmentCost(length, diameterMm, 1.0, 1.0);
                constructionCost += cost;
                newLength += length;
                segments.add(OutputSegment.builder()
                        .id("new_" + connectionPoint.getId() + "_" + index)
                        .startNodeId(nodeId(connectionPoint.getId(), coordinates, index))
                        .endNodeId(nodeId(connectionPoint.getId(), coordinates, index + 1))
                        .flowTph(connection.getFlowTph())
                        .diameterMm(diameterMm)
                        .lengthM(length)
                        .layingMethod("base")
                        .depthStart(null)
                        .depthEnd(null)
                        .cost(cost)
                        .geometryWgs84((LineString) crsTransformer.toWgs84(segment))
                        .build());
            }

            for (int index = 1; index < coordinates.size() - 1; index++) {
                technicalNodes.add(OutputTechnicalNode.builder()
                        .id(nodeId(connectionPoint.getId(), coordinates, index))
                        .geometryWgs84(toWgs84Point(coordinates.get(index)))
                        .build());
            }
        }

        long tieInCost = (long) tieIns.size() * CostModel.TIE_IN_COST;
        long unconnectedPenalty = unconnectedPenalty(planning, dataset);
        long calculatedCost = constructionCost + chamberConstructionCost + tieInCost
                + unconnectedPenalty;
        double score = costModel.score(calculatedCost, newLength);

        VariantSummary summary = VariantSummary.builder()
                .variantId(DEFAULT_VARIANT_ID)
                .rank(1)
                .constructionCost(constructionCost)
                .chamberConstructionCost(chamberConstructionCost)
                .tieInCost(tieInCost)
                .reconstructionCost(0L)
                .chamberReconstructionCost(0L)
                .unconnectedPenalty(unconnectedPenalty)
                .calculatedCost(calculatedCost)
                .newNetworkLengthM(newLength)
                .reconstructionLengthM(0.0)
                .lengthM(newLength)
                .score(score)
                .unconnectedOksIds(planning.getUnconnectedConnectionPointIds())
                .build();

        return VariantResult.builder()
                .variantId(DEFAULT_VARIANT_ID)
                .segments(segments)
                .tieIns(tieIns)
                .chambers(chambers)
                .technicalNodes(technicalNodes)
                .summary(summary)
                .build();
    }

    private String nodeId(String connectionPointId, List<Coordinate> coordinates, int index) {
        if (index == 0) {
            return "tie_" + connectionPointId;
        }
        if (index == coordinates.size() - 1) {
            return connectionPointId;
        }
        return "node_" + connectionPointId + "_" + index;
    }

    private long unconnectedPenalty(RoutePlanningResult planning, NetworkDataset dataset) {
        long penalty = 0L;
        for (String id : planning.getUnconnectedConnectionPointIds()) {
            penalty += costModel.unconnectedPenalty(flowOf(id, dataset));
        }
        return penalty;
    }

    private double flowOf(String connectionPointId, NetworkDataset dataset) {
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            if (connectionPoint.getId().equals(connectionPointId)) {
                if (connectionPoint.getFlowTph() != null) {
                    return connectionPoint.getFlowTph();
                }
                if (connectionPoint.getOksId() != null) {
                    for (OksFutureObject oks : dataset.getOksFutures()) {
                        if (oks.getId().equals(connectionPoint.getOksId()) && oks.getFlowTph() != null) {
                            return oks.getFlowTph();
                        }
                    }
                }
            }
        }
        return 0.0;
    }

    private Point toWgs84Point(Coordinate coordinate) {
        Point point = GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate);
        return (Point) crsTransformer.toWgs84(point);
    }
}
