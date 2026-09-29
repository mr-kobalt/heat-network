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
    /** ADR-0073: режим расчёта — {@code 2d} или {@code depth}. */
    String mode;
    boolean traced;
    Instant createdAt;
    Instant startedAt;
    Instant finishedAt;
    /** ADR-0057: текущий этап расчёта и прогресс 0…100. */
    String stage;
    Integer progress;
    String error;
    JsonNode summary;
}
