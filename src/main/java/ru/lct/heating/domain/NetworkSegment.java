package ru.lct.heating.domain;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.LineString;

@Value
@Builder
public class NetworkSegment {
    String id;
    Integer diameterMm;
    Double flowTph;
    String upstreamObjectId;
    LineString geometry;
}
