package ru.lct.heating.domain;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Point;

@Value
@Builder
public class SourceObject {
    String id;
    String name;
    Point geometry;
}
