package ru.lct.heating.calculation;

/**
 * Отчёт о ходе расчёта: машинный ключ этапа и прогресс 0…100 (ADR-0057).
 * Реализация не должна бросать исключений — сбой отчёта не прерывает расчёт.
 */
@FunctionalInterface
public interface ProgressReporter {

    ProgressReporter NOOP = (stage, progress) -> { };

    void report(String stage, int progress);
}
