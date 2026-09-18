package ru.lct.heating.ingest;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class IngestWarning {
    String code;
    String objectId;
    String message;
}
