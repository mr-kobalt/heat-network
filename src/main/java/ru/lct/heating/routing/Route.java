package ru.lct.heating.routing;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

@Value
@Builder
public class Route {
    List<Coordinate> coordinates;
    TieInCandidate tieIn;
    double lengthM;
}
