package ru.lct.heating.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.Polygon;
import ru.lct.heating.domain.GeometrySupport;

/**
 * Разбор узла GeoJSON {@code geometry} в геометрию JTS.
 */
public final class GeoJsonGeometryParser {

    private GeoJsonGeometryParser() {
    }

    public static Geometry parse(JsonNode geometryNode) {
        if (geometryNode == null || geometryNode.isNull()) {
            return null;
        }
        String type = geometryNode.path("type").asText();
        JsonNode coordinates = geometryNode.get("coordinates");
        switch (type) {
            case "Point":
                return GeometrySupport.GEOMETRY_FACTORY.createPoint(coordinate(coordinates));
            case "MultiPoint":
                return GeometrySupport.GEOMETRY_FACTORY.createMultiPointFromCoords(coordinates(coordinates));
            case "LineString":
                return GeometrySupport.GEOMETRY_FACTORY.createLineString(coordinates(coordinates));
            case "MultiLineString":
                return GeometrySupport.GEOMETRY_FACTORY.createMultiLineString(lineStrings(coordinates));
            case "Polygon":
                return polygon(coordinates);
            case "MultiPolygon":
                return GeometrySupport.GEOMETRY_FACTORY.createMultiPolygon(polygons(coordinates));
            default:
                throw new IllegalArgumentException("Неподдерживаемый тип геометрии: " + type);
        }
    }

    private static Coordinate coordinate(JsonNode node) {
        return new Coordinate(node.get(0).asDouble(), node.get(1).asDouble());
    }

    private static Coordinate[] coordinates(JsonNode node) {
        List<Coordinate> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(coordinate(item));
        }
        return result.toArray(new Coordinate[0]);
    }

    private static org.locationtech.jts.geom.LineString[] lineStrings(JsonNode node) {
        List<org.locationtech.jts.geom.LineString> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(GeometrySupport.GEOMETRY_FACTORY.createLineString(coordinates(item)));
        }
        return result.toArray(new org.locationtech.jts.geom.LineString[0]);
    }

    private static Polygon polygon(JsonNode node) {
        LinearRing shell = GeometrySupport.GEOMETRY_FACTORY.createLinearRing(coordinates(node.get(0)));
        if (node.size() == 1) {
            return GeometrySupport.GEOMETRY_FACTORY.createPolygon(shell);
        }
        LinearRing[] holes = new LinearRing[node.size() - 1];
        for (int i = 1; i < node.size(); i++) {
            holes[i - 1] = GeometrySupport.GEOMETRY_FACTORY.createLinearRing(coordinates(node.get(i)));
        }
        return GeometrySupport.GEOMETRY_FACTORY.createPolygon(shell, holes);
    }

    private static Polygon[] polygons(JsonNode node) {
        List<Polygon> result = new ArrayList<>();
        for (JsonNode item : node) {
            result.add(polygon(item));
        }
        return result.toArray(new Polygon[0]);
    }
}
