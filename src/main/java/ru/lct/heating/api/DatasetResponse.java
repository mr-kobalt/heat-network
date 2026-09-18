package ru.lct.heating.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class DatasetResponse {
    UUID id;
    Instant createdAt;
    String originalFilename;
    String status;
    JsonNode objectCounts;
    String bbox;
    JsonNode warnings;
}
