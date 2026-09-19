package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.GeometrySupport;

/**
 * Построение оси препятствия (протокол): для линейных ограничений ось — сама
 * линия, для полигональных — внешняя граница. Используется для расчёта угла
 * пересечения от оси (FR-53).
 */
@Component
public class RestrictionAxisBuilder {

    private final GeometryFactory factory = GeometrySupport.GEOMETRY_FACTORY;

    public Geometry axis(Geometry geometry) {
        if (geometry == null) {
            return null;
        }
        List<Geometry> axes = new ArrayList<>();
        collect(geometry, axes);
        if (axes.isEmpty()) {
            return null;
        }
        Geometry[] array = axes.toArray(new Geometry[0]);
        return factory.createGeometryCollection(array).union();
    }

    private void collect(Geometry geometry, List<Geometry> axes) {
        if (geometry instanceof Polygon) {
            axes.add(((Polygon) geometry).getExteriorRing());
        } else if (geometry instanceof LineString) {
            axes.add(geometry);
        } else {
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                collect(geometry.getGeometryN(i), axes);
            }
        }
    }
}
