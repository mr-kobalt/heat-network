package ru.lct.heating.depth;

import lombok.Builder;
import lombok.Value;

/**
 * Непрерывный фрагмент вертикального профиля ребра (ADR-0073): расстояния
 * вдоль ребра (м от начала), глубины до верхней границы габарита новой сети на
 * концах и коэффициент {@code Kгл}.
 *
 * <p>Профиль внутри фрагмента линеен: постоянная глубина — равные концы,
 * спуск/подъём — разные. На спуске/подъёме {@code Kгл} — среднее значений на
 * концах (ТП v2 §5).</p>
 */
@Value
@Builder
public class DepthSegment {
    double startDistanceM;
    double endDistanceM;
    double depthStartM;
    double depthEndM;
    double kDepth;

    public double lengthM() {
        return endDistanceM - startDistanceM;
    }

    public double depthAt(double distanceM) {
        double length = lengthM();
        if (length <= 1e-9) {
            return depthEndM;
        }
        double ratio = Math.max(0.0, Math.min(1.0, (distanceM - startDistanceM) / length));
        return depthStartM + (depthEndM - depthStartM) * ratio;
    }
}
