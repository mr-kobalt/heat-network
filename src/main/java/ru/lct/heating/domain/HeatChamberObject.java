package ru.lct.heating.domain;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
@Builder
public class HeatChamberObject {
    String id;
    Integer diameterMm;
    String upstreamObjectId;
    Point geometry;
}
