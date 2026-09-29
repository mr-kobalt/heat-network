package ru.lct.heating.output;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
@Builder
public class OutputSegment {
    String id;
    String startNodeId;
    String endNodeId;
    double flowTph;
    int diameterMm;
    double lengthM;
    String layingMethod;
    Double depthStart;
    Double depthEnd;
    long cost;
    LineString geometryWgs84;
}
