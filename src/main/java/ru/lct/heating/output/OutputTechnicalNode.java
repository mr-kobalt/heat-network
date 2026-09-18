package ru.lct.heating.output;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
@Builder
public class OutputTechnicalNode {
    String id;
    Point geometryWgs84;
}
