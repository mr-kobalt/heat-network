package ru.lct.heating.api;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RunResponse {
    UUID id;
    UUID datasetId;
    String status;
    String algorithm;
    boolean traced;
    Instant createdAt;
    Instant startedAt;
    Instant finishedAt;
    String error;
    JsonNode summary;
}
