package ru.lct.heating.routing;

import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RoutePlanningResult {
    List<PlannedConnection> connections;
    List<String> unconnectedConnectionPointIds;
}
