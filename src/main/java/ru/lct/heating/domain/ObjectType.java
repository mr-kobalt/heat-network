package ru.lct.heating.domain;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Типы входных объектов GeoJSON (ТП 2.1).
 */
public enum ObjectType {

    SOURCE("source"),
    HEAT_NETWORK("heat_network"),
    HEAT_CHAMBER("heat_chamber"),
    OKS_FUTURE("oks_future"),
    OKS_CONNECTION_POINT("oks_connection_point"),
    OKS_EXISTING("oks_existing"),
    RESTRICTION("restriction");

    private static final Map<String, ObjectType> BY_WIRE_NAME =
            Arrays.stream(values()).collect(Collectors.toMap(ObjectType::wireName, Function.identity()));

    private final String wireName;

    ObjectType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ObjectType fromWireName(String value) {
        return value == null ? null : BY_WIRE_NAME.get(value);
    }
}
