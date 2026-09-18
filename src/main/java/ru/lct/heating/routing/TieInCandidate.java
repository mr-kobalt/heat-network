package ru.lct.heating.routing;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Coordinate;

/**
 * Кандидат точки врезки в существующую сеть (камера или точка на участке).
 */
@Value
@Builder
public class TieInCandidate {
    String existingObjectId;
    String existingObjectType;
    Integer existingDiameterMm;
    Coordinate coordinate;
}
