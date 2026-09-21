package ru.lct.heating.routing.algorithm;

import lombok.Builder;
import lombok.Value;

/**
 * Описание доступного алгоритма трассировки для API (ADR-0027).
 */
@Value
@Builder
public class AlgorithmInfo {
    String id;
    String description;
    boolean defaultAlgorithm;
}
