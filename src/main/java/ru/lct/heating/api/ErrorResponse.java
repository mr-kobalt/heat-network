package ru.lct.heating.api;

import java.time.Instant;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class ErrorResponse {
    Instant timestamp;
    int status;
    String error;
    String message;
}
