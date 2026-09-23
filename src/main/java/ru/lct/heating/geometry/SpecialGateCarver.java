package ru.lct.heating.geometry;

import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.linearref.LengthIndexedLine;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.GeometrySupport;

/**
 * Модель строгого обхода спецпроходов (ADR-0045, E25). Полоса минимального
 * расстояния вокруг спецобъекта делается непроходимой, но в ней прорезаются
 * «ворота» — узкие коридоры, перпендикулярные оси объекта. Через них трасса
 * может пересечь объект строго поперёк (угол ≥45° для дорог/трамвая),
 * движение вдоль полосы (параллельно) запрещено.
 *
 * <p>Модель реализуется геометрически и подключается в запретный индекс, поэтому
 * не требует изменений в поиске пути. Опция — {@code forest-special-strict}.</p>
 */
@Component
public class SpecialGateCarver {

    /** Запас, на который «ворота» выходят за пределы полосы с обеих сторон. */
    private static final double GATE_MARGIN_M = 0.5;

    private static final double EPS = 1e-9;

    private final GeometryFactory factory = GeometrySupport.GEOMETRY_FACTORY;
    private final RestrictionAxisBuilder axisBuilder;

    public SpecialGateCarver(RestrictionAxisBuilder axisBuilder) {
        this.axisBuilder = axisBuilder;
    }

    /**
     * @param geometry       геометрия спецобъекта
     * @param bandWidthM     полуширина полосы (мин. расстояние + половина габарита)
     * @param gateStepM      шаг «ворот» вдоль оси
     * @param gateThicknessM ширина «ворот» (коридора пересечения)
     * @return полоса с прорезанными «воротами» (для запретного индекса)
     */
    public Geometry carve(Geometry geometry, double bandWidthM, double gateStepM,
                          double gateThicknessM) {
        if (geometry == null || geometry.isEmpty()) {
            return geometry;
        }
        Geometry band = geometry.buffer(bandWidthM);
        if (band.isEmpty()) {
            return band;
        }
        Geometry axis = axisBuilder.axis(geometry);
        if (axis == null || axis.isEmpty()) {
            return band;
        }
        Geometry gates = gates(axis, bandWidthM, gateStepM, gateThicknessM);
        if (gates == null || gates.isEmpty()) {
            return band;
        }
        Geometry carved = band.difference(gates);
        return carved.isEmpty() ? band : carved;
    }

    /**
     * E25-07: единая полоса для набора спецобъектов — объединение полос с
     * прорезанными «воротами» всех объектов. Там, где полосы накладываются,
     * пересечение возможно, если «ворота» есть хотя бы у одного объекта
     * (иначе наложение блокировало бы проход).
     */
    public Geometry carveUnion(List<Geometry> geometries, List<Double> bandWidths,
                               double gateStepM, double gateThicknessM) {
        List<Geometry> bands = new ArrayList<>();
        List<Geometry> gates = new ArrayList<>();
        for (int i = 0; i < geometries.size(); i++) {
            Geometry geometry = geometries.get(i);
            if (geometry == null || geometry.isEmpty()) {
                continue;
            }
            double bandWidth = bandWidths.get(i);
            Geometry band = geometry.buffer(bandWidth);
            if (band.isEmpty()) {
                continue;
            }
            bands.add(band);
            Geometry axis = axisBuilder.axis(geometry);
            if (axis == null || axis.isEmpty()) {
                continue;
            }
            collectGates(axis, bandWidth, gateStepM, gateThicknessM, gates);
        }
        if (bands.isEmpty()) {
            return null;
        }
        Geometry band = union(bands);
        if (gates.isEmpty()) {
            return band;
        }
        Geometry carved = band.difference(union(gates));
        return carved.isEmpty() ? band : carved;
    }

    private Geometry union(List<Geometry> geometries) {
        return factory.createGeometryCollection(geometries.toArray(new Geometry[0])).union();
    }

    private void collectGates(Geometry axis, double bandWidthM, double gateStepM,
                              double gateThicknessM, List<Geometry> gates) {
        for (int i = 0; i < axis.getNumGeometries(); i++) {
            Geometry component = axis.getGeometryN(i);
            if (component instanceof LineString) {
                collectGates((LineString) component, bandWidthM, gateStepM, gateThicknessM, gates);
            } else if (component instanceof Polygon) {
                collectGates(((Polygon) component).getExteriorRing(), bandWidthM, gateStepM,
                        gateThicknessM, gates);
            }
        }
    }

    private Geometry gates(Geometry axis, double bandWidthM, double gateStepM,
                           double gateThicknessM) {
        List<Geometry> rectangles = new ArrayList<>();
        for (int i = 0; i < axis.getNumGeometries(); i++) {
            Geometry component = axis.getGeometryN(i);
            if (component instanceof LineString) {
                collectGates((LineString) component, bandWidthM, gateStepM, gateThicknessM,
                        rectangles);
            } else if (component instanceof Polygon) {
                collectGates(((Polygon) component).getExteriorRing(), bandWidthM, gateStepM,
                        gateThicknessM, rectangles);
            }
        }
        if (rectangles.isEmpty()) {
            return null;
        }
        Geometry[] array = rectangles.toArray(new Geometry[0]);
        return factory.createGeometryCollection(array).union();
    }

    private void collectGates(LineString line, double bandWidthM, double gateStepM,
                              double gateThicknessM, List<Geometry> rectangles) {
        double length = line.getLength();
        if (length < EPS) {
            return;
        }
        LengthIndexedLine indexed = new LengthIndexedLine(line);
        double step = Math.max(0.5, gateStepM);
        double span = bandWidthM + GATE_MARGIN_M;
        for (double distance = 0.0; distance <= length + EPS; distance += step) {
            double position = Math.min(distance, length);
            Coordinate center = indexed.extractPoint(position);
            double[] tangent = tangentAt(indexed, position, length);
            if (tangent == null) {
                continue;
            }
            double nx = -tangent[1];
            double ny = tangent[0];
            double halfThickness = gateThicknessM / 2.0;
            Coordinate[] ring = new Coordinate[]{
                    offset(center, tangent, -halfThickness, nx, ny, -span),
                    offset(center, tangent, halfThickness, nx, ny, -span),
                    offset(center, tangent, halfThickness, nx, ny, span),
                    offset(center, tangent, -halfThickness, nx, ny, span),
                    offset(center, tangent, -halfThickness, nx, ny, -span)};
            rectangles.add(factory.createPolygon(ring));
        }
    }

    /** Единичное направление оси в точке; на концах — по крайнему сегменту. */
    private double[] tangentAt(LengthIndexedLine indexed, double position, double length) {
        double delta = Math.min(1.0, length / 2.0);
        double from = Math.max(0.0, position - delta);
        double to = Math.min(length, position + delta);
        if (to - from < EPS) {
            return null;
        }
        Coordinate a = indexed.extractPoint(from);
        Coordinate b = indexed.extractPoint(to);
        double dx = b.x - a.x;
        double dy = b.y - a.y;
        double norm = Math.hypot(dx, dy);
        if (norm < EPS) {
            return null;
        }
        return new double[]{dx / norm, dy / norm};
    }

    private Coordinate offset(Coordinate center, double[] tangent, double along,
                              double nx, double ny, double normal) {
        return new Coordinate(center.x + tangent[0] * along + nx * normal,
                center.y + tangent[1] * along + ny * normal);
    }
}
