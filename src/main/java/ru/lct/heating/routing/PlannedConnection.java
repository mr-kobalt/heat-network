package ru.lct.heating.routing;

import lombok.Builder;
import lombok.Value;
import ru.lct.heating.domain.OksConnectionPointObject;

@Value
@Builder
public class PlannedConnection {
    OksConnectionPointObject connectionPoint;
    double flowTph;
    Route route;
}
