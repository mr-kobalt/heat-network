package ru.lct.heating.calculation;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Режим расчёта (ADR-0073): обязательный 2D или дополнительный с учётом
 * глубины (ТП v2 §5). Режимы формируют отдельные наборы вариантов и не
 * объединяются в одно ранжирование (FR-90).
 */
public enum CalculationMode {

    TWO_D("2d"),
    DEPTH("depth");

    private final String parameter;

    CalculationMode(String parameter) {
        this.parameter = parameter;
    }

    public String parameter() {
        return parameter;
    }

    public boolean isDepth() {
        return this == DEPTH;
    }

    /** Разбор query-параметра {@code mode}; пусто — 2D; неизвестное значение — 400. */
    public static CalculationMode fromParameter(String value) {
        if (value == null || value.isBlank()) {
            return TWO_D;
        }
        for (CalculationMode mode : values()) {
            if (mode.parameter.equalsIgnoreCase(value.trim())) {
                return mode;
            }
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Неизвестный режим расчёта: " + value + " (допустимо: 2d, depth)");
    }
}
