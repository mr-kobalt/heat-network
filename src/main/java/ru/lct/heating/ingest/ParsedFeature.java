package ru.lct.heating.ingest;

import lombok.Value;
import ru.lct.heating.domain.ObjectType;

@Value
public class ParsedFeature {
    ObjectType type;
    String id;
    Object object;
}
