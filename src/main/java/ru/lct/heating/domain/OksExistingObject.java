package ru.lct.heating.domain;

import lombok.Builder;
import lombok.Value;
import org.locationtech.jts.geom.Geometry;

@Value
@Builder
public class OksExistingObject {
    String id;
    Geometry geometry;
}
