package ru.lct.heating.output;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
@Builder
public class OutputTieIn {
    String id;
    String existingObjectId;
    String existingObjectType;
    Integer existingDiameterMm;
    int requiredDiameterMm;
    long cost;
    Point geometryWgs84;
}
