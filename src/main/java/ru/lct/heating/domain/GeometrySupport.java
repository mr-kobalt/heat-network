package ru.lct.heating.domain;

import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;

/**
 * Общая фабрика геометрий. SRID проставляется преобразователем CRS.
 */
public final class GeometrySupport {

    public static final GeometryFactory GEOMETRY_FACTORY = new GeometryFactory(new PrecisionModel());

    private GeometrySupport() {
    }
}
