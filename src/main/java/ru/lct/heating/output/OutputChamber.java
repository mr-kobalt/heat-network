package ru.lct.heating.output;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
@Builder
public class OutputChamber {
    String id;
    int diameterMm;
    long cost;
    Point geometryWgs84;
}
