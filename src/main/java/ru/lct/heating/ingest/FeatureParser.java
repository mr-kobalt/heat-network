package ru.lct.heating.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.springframework.stereotype.Component;
import ru.lct.heating.domain.HeatChamberObject;
import ru.lct.heating.domain.NetworkSegment;
import ru.lct.heating.domain.ObjectType;
import ru.lct.heating.domain.OksConnectionPointObject;
import ru.lct.heating.domain.OksExistingObject;
import ru.lct.heating.domain.OksFutureObject;
import ru.lct.heating.domain.RestrictionObject;
import ru.lct.heating.domain.SourceObject;

/**
 * Преобразование одного GeoJSON-объекта в доменную модель с валидацией
 * и диагностикой. Координаты сразу переводятся в EPSG:32637.
 */
@Component
public class FeatureParser {

    private final CrsTransformer crsTransformer;

    public FeatureParser(CrsTransformer crsTransformer) {
        this.crsTransformer = crsTransformer;
    }

    public Optional<ParsedFeature> parse(JsonNode feature, List<IngestWarning> warnings) {
        JsonNode properties = feature.path("properties");
        String wireType = text(properties, "object_type");
        ObjectType type = ObjectType.fromWireName(wireType);
        if (type == null) {
            warnings.add(IngestWarning.builder()
                    .code("UNKNOWN_OBJECT_TYPE")
                    .message("Неизвестный object_type: " + wireType)
                    .build());
            return Optional.empty();
        }

        String id = text(properties, "id");
        Geometry geometry;
        try {
            geometry = GeoJsonGeometryParser.parse(feature.get("geometry"));
        } catch (RuntimeException exception) {
            warnings.add(IngestWarning.builder()
                    .code("INVALID_GEOMETRY")
                    .objectId(id)
                    .message("Не удалось разобрать геометрию: " + exception.getMessage())
                    .build());
            return Optional.empty();
        }
        if (geometry == null || geometry.isEmpty()) {
            warnings.add(IngestWarning.builder()
                    .code("MISSING_GEOMETRY")
                    .objectId(id)
                    .message("Отсутствует или пуста геометрия")
                    .build());
            return Optional.empty();
        }
        geometry = crsTransformer.toUtm(geometry);

        switch (type) {
            case SOURCE:
                if (!(geometry instanceof Point)) {
                    return wrongGeometry(warnings, id, type, geometry);
                }
                return parsed(type, id, SourceObject.builder()
                        .id(id)
                        .name(text(properties, "name"))
                        .geometry((Point) geometry)
                        .build());
            case HEAT_NETWORK:
                if (!(geometry instanceof LineString)) {
                    return wrongGeometry(warnings, id, type, geometry);
                }
                return parsed(type, id, NetworkSegment.builder()
                        .id(id)
                        .diameterMm(integer(properties, "diameter"))
                        .flowTph(number(properties, "flow_tph"))
                        .upstreamObjectId(text(properties, "upstream_object_id"))
                        .geometry((LineString) geometry)
                        .build());
            case HEAT_CHAMBER:
                if (!(geometry instanceof Point)) {
                    return wrongGeometry(warnings, id, type, geometry);
                }
                return parsed(type, id, HeatChamberObject.builder()
                        .id(id)
                        .diameterMm(integer(properties, "diameter"))
                        .upstreamObjectId(text(properties, "upstream_object_id"))
                        .geometry((Point) geometry)
                        .build());
            case OKS_FUTURE:
                return parsed(type, id, OksFutureObject.builder()
                        .id(id)
                        .flowTph(number(properties, "flow_tph"))
                        .heatLoad(number(properties, "heat_load"))
                        .geometry(geometry)
                        .build());
            case OKS_CONNECTION_POINT:
                if (!(geometry instanceof Point)) {
                    return wrongGeometry(warnings, id, type, geometry);
                }
                return parsed(type, id, OksConnectionPointObject.builder()
                        .id(id)
                        .oksId(text(properties, "oks_id"))
                        .flowTph(number(properties, "flow_tph"))
                        .geometry((Point) geometry)
                        .build());
            case OKS_EXISTING:
                return parsed(type, id, OksExistingObject.builder()
                        .id(id)
                        .geometry(geometry)
                        .build());
            case RESTRICTION:
                String restrictionType = text(properties, "restriction_type");
                if (restrictionType == null) {
                    warnings.add(IngestWarning.builder()
                            .code("MISSING_RESTRICTION_TYPE")
                            .objectId(id)
                            .message("У ограничения отсутствует restriction_type")
                            .build());
                    return Optional.empty();
                }
                return parsed(type, id, RestrictionObject.builder()
                        .id(id)
                        .restrictionType(restrictionType)
                        .geometry(geometry)
                        .build());
            default:
                return Optional.empty();
        }
    }

    private Optional<ParsedFeature> parsed(ObjectType type, String id, Object object) {
        return Optional.of(new ParsedFeature(type, id, object));
    }

    private Optional<ParsedFeature> wrongGeometry(List<IngestWarning> warnings, String id,
                                                  ObjectType type, Geometry geometry) {
        warnings.add(IngestWarning.builder()
                .code("WRONG_GEOMETRY_TYPE")
                .objectId(id)
                .message("Для " + type.wireName() + " ожидалась другая геометрия, получено: "
                        + geometry.getGeometryType())
                .build());
        return Optional.empty();
    }

    private static String text(JsonNode properties, String field) {
        JsonNode node = properties.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    private static Integer integer(JsonNode properties, String field) {
        JsonNode node = properties.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isNumber() ? node.asInt() : parseInteger(node.asText());
    }

    private static Integer parseInteger(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static Double number(JsonNode properties, String field) {
        JsonNode node = properties.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isNumber() ? node.asDouble() : parseDouble(node.asText());
    }

    private static Double parseDouble(String value) {
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException exception) {
            return null;
        }
    }
}
