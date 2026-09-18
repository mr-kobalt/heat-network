package ru.lct.heating.routing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.locationtech.jts.geom.Coordinate;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.NetworkDataset;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.OksFutureObject;
import ru.lct.heating.geometry.ObstacleIndex;

/**
 * Планирование подключений: для каждой точки подключения подбирается врезка
 * и маршрут в обход запретных зон (эвристика M1: индивидуальные подключения).
 */
@Component
public class RoutePlanner {

    private static final int CANDIDATES_CONSIDERED = 40;

    private final TieInCandidateProvider candidateProvider;
    private final VisibilityGraphRouter router;

    public RoutePlanner(TieInCandidateProvider candidateProvider, VisibilityGraphRouter router) {
        this.candidateProvider = candidateProvider;
        this.router = router;
    }

    public RoutePlanningResult plan(NetworkDataset dataset, ObstacleIndex obstacleIndex,
                                    List<String> warnings) {
        List<TieInCandidate> candidates = candidateProvider.candidates(dataset);
        Map<String, Double> flows = flowsByConnectionPoint(dataset);
        List<PlannedConnection> connections = new ArrayList<>();
        List<String> unconnected = new ArrayList<>();

        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            Double flow = flows.get(connectionPoint.getId());
            if (flow == null || flow <= 0.0) {
                warnings.add("NO_FLOW: для точки подключения " + connectionPoint.getId()
                        + " не найден расчётный расход");
                unconnected.add(connectionPoint.getId());
                continue;
            }
            Route route = bestRoute(connectionPoint, candidates, obstacleIndex);
            if (route == null) {
                unconnected.add(connectionPoint.getId());
            } else {
                connections.add(PlannedConnection.builder()
                        .connectionPoint(connectionPoint)
                        .flowTph(flow)
                        .route(route)
                        .build());
            }
        }
        return RoutePlanningResult.builder()
                .connections(connections)
                .unconnectedConnectionPointIds(unconnected)
                .build();
    }

    private Route bestRoute(OksConnectionPointObject connectionPoint,
                            List<TieInCandidate> candidates, ObstacleIndex obstacleIndex) {
        Coordinate start = connectionPoint.getGeometry().getCoordinate();
        List<TieInCandidate> nearest = new ArrayList<>(candidates);
        nearest.sort(Comparator.comparingDouble(candidate -> start.distance(candidate.getCoordinate())));

        Route best = null;
        int considered = 0;
        for (TieInCandidate candidate : nearest) {
            if (considered++ >= CANDIDATES_CONSIDERED) {
                break;
            }
            List<Coordinate> path = router.findPath(start, candidate.getCoordinate(), obstacleIndex);
            if (path == null) {
                continue;
            }
            double length = pathLength(path);
            if (best == null || length < best.getLengthM()) {
                best = Route.builder()
                        .coordinates(path)
                        .tieIn(candidate)
                        .lengthM(length)
                        .build();
            }
        }
        return best;
    }

    private Map<String, Double> flowsByConnectionPoint(NetworkDataset dataset) {
        Map<String, Double> byOksId = new HashMap<>();
        for (OksFutureObject oks : dataset.getOksFutures()) {
            if (oks.getFlowTph() != null) {
                byOksId.put(oks.getId(), oks.getFlowTph());
            }
        }
        Map<String, Double> result = new HashMap<>();
        for (OksConnectionPointObject connectionPoint : dataset.getConnectionPoints()) {
            Double flow = connectionPoint.getFlowTph();
            if (flow == null && connectionPoint.getOksId() != null) {
                flow = byOksId.get(connectionPoint.getOksId());
            }
            result.put(connectionPoint.getId(), flow);
        }
        return result;
    }

    private double pathLength(List<Coordinate> path) {
        double total = 0.0;
        for (int i = 1; i < path.size(); i++) {
            total += path.get(i - 1).distance(path.get(i));
        }
        return total;
    }
}
