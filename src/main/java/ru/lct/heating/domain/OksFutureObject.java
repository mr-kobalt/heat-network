package ru.lct.heating.domain;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Geometry;

@Value
@Builder
public class OksFutureObject {
    String id;
    Double flowTph;
    Double heatLoad;
    Geometry geometry;
}
