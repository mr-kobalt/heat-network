package ru.lct.heating.depth;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;
import ru.lct.heating.config.AppProperties;
import ru.lct.heating.geometry.SpecialSpan;
import ru.lct.heating.hydraulics.EnvelopeCatalog;

/**
 * Построение вертикального профиля ребра для режима с учётом глубины
 * (ADR-0073, ТП v2 §5).
 *
 * <p>Обычная глубина — {@code depth-normal-m} (3,0 м). Где пересекаемая
 * коммуникация с заданным вертикальным габаритом конфликтует с обычной
 * отметкой, профиль уходит выше (до {@code depth-min-m}) или ниже неё, с
 * уклоном не более {@code depth-max-slope}. Близкие препятствия не возвращают
 * профиль на обычную глубину (допущение вместо NQ-05).</p>
 */
@Component
public class DepthProfileBuilder {

    private static final double EPS = 1e-6;

    private final AppProperties appProperties;
    private final EnvelopeCatalog envelopes;

    public DepthProfileBuilder(AppProperties appProperties, EnvelopeCatalog envelopes) {
        this.appProperties = appProperties;
        this.envelopes = envelopes;
    }

    /**
     * @param edgeLengthM длина ребра по горизонтальной проекции, м
     * @param diameterMm   Ду нового участка (для высоты габарита пары)
     * @param spans        спецпроходы ребра с вертикальными габаритами
     *                     ({@link SpecialSpan}; без габарита игнорируются)
     * @return профиль, покрывающий [0, edgeLengthM] непрерывно
     */
    public List<DepthSegment> build(double edgeLengthM, int diameterMm,
                                    List<SpecialSpan> spans, List<String> warnings) {
        double normal = appProperties.getDepthNormalM();
        double min = appProperties.getDepthMinM();
        double slope = appProperties.getDepthMaxSlope();
        double clearance = appProperties.getDepthVerticalClearanceM();
        double closeM = appProperties.getDepthCloseCrossingM();
        double newHeight = envelopes.heightM(diameterMm);

        List<Constraint> constraints = new ArrayList<>();
        for (SpecialSpan span : spans) {
            if (span.getVerticalTopDepthM() == null || span.getVerticalHeightM() == null) {
                continue;
            }
            double top = span.getVerticalTopDepthM();
            double bottom = top + span.getVerticalHeightM();
            double target = chooseTarget(normal, newHeight, clearance, top, bottom, min);
            if (target < 0.0) {
                warnings.add("DEPTH_OBSTACLE_UNRESOLVED: " + span.getRestrictionType()
                        + " (габарит " + top + "…" + bottom + " м)");
                continue;
            }
            if (Math.abs(target - normal) < EPS) {
                continue;
            }
            constraints.add(new Constraint(span.getStartDistanceM(), span.getEndDistanceM(),
                    target));
        }

        if (edgeLengthM <= EPS) {
            return List.of(constant(0.0, edgeLengthM, normal));
        }
        if (constraints.isEmpty()) {
            return List.of(constant(0.0, edgeLengthM, normal));
        }

        List<Constraint> merged = merge(constraints, normal, slope, closeM);
        List<DepthSegment> segments = new ArrayList<>();
        double cursor = 0.0;
        double currentDepth = normal;
        for (Constraint constraint : merged) {
            double run = run(constraint, normal, slope);
            double depart = Math.max(0.0, constraint.startM - run);
            double arrive = Math.min(edgeLengthM, constraint.endM + run);
            if (depart > cursor + EPS) {
                segments.add(constant(cursor, depart, currentDepth));
            }
            if (constraint.startM - depart > EPS) {
                if (constraint.startM - run < -EPS) {
                    warnings.add("DEPTH_SLOPE_EXCEEDED: не хватает длины на спуск/подъём");
                }
                segments.add(ramp(depart, constraint.startM, normal, constraint.target));
            }
            if (constraint.endM - constraint.startM > EPS) {
                segments.add(constant(constraint.startM, constraint.endM, constraint.target));
            }
            if (arrive - constraint.endM > EPS) {
                segments.add(ramp(constraint.endM, arrive, constraint.target, normal));
            }
            cursor = arrive;
            currentDepth = normal;
        }
        if (cursor < edgeLengthM - EPS) {
            segments.add(constant(cursor, edgeLengthM, normal));
        }
        return coalesce(segments);
    }

    /**
     * Целевая глубина (до верхней границы габарита) для обхода коммуникации:
     * выше (предпочтительно, {@code Kгл = 1}) либо ниже габарита; {@code -1},
     * если обойти невозможно.
     */
    private double chooseTarget(double normal, double newHeight, double clearance, double top,
                                double bottom, double min) {
        double above = top - newHeight - clearance;
        if (above >= min - EPS) {
            return above;
        }
        double below = bottom + clearance;
        if (below >= min - EPS) {
            return below;
        }
        return -1.0;
    }

    private double run(Constraint constraint, double normal, double slope) {
        if (slope <= EPS) {
            return 0.0;
        }
        return Math.abs(normal - constraint.target) / slope;
    }

    private List<Constraint> merge(List<Constraint> input, double normal, double slope,
                                   double closeM) {
        List<Constraint> sorted = new ArrayList<>(input);
        sorted.sort(Comparator.comparingDouble(constraint -> constraint.startM));
        boolean changed = true;
        while (changed) {
            changed = false;
            List<Constraint> merged = new ArrayList<>();
            for (Constraint constraint : sorted) {
                if (merged.isEmpty()) {
                    merged.add(constraint);
                    continue;
                }
                Constraint last = merged.get(merged.size() - 1);
                double lastArrive = last.endM + run(last, normal, slope);
                double depart = constraint.startM - run(constraint, normal, slope);
                if (depart <= lastArrive + closeM + EPS) {
                    double target = Math.abs(constraint.target - normal)
                            >= Math.abs(last.target - normal) ? constraint.target : last.target;
                    merged.set(merged.size() - 1, new Constraint(
                            Math.min(last.startM, constraint.startM),
                            Math.max(last.endM, constraint.endM), target));
                    changed = true;
                } else {
                    merged.add(constraint);
                }
            }
            sorted = merged;
        }
        return sorted;
    }

    private DepthSegment constant(double start, double end, double depth) {
        double k = kDepth(depth);
        return DepthSegment.builder()
                .startDistanceM(start).endDistanceM(end)
                .depthStartM(depth).depthEndM(depth).kDepth(k)
                .build();
    }

    private DepthSegment ramp(double start, double end, double from, double to) {
        double k = (kDepth(from) + kDepth(to)) / 2.0;
        return DepthSegment.builder()
                .startDistanceM(start).endDistanceM(end)
                .depthStartM(from).depthEndM(to).kDepth(k)
                .build();
    }

    /** {@code Kгл = 1} при {@code h ≤ normal}, иначе {@code 1 + 0,10·(h − normal)}. */
    private double kDepth(double depth) {
        double normal = appProperties.getDepthNormalM();
        return depth <= normal + EPS ? 1.0 : 1.0 + 0.10 * (depth - normal);
    }

    /** Склейка смежных участков с непрерывной глубиной и одинаковым {@code Kгл}. */
    private List<DepthSegment> coalesce(List<DepthSegment> input) {
        List<DepthSegment> result = new ArrayList<>();
        for (DepthSegment segment : input) {
            if (segment.lengthM() <= EPS) {
                continue;
            }
            if (!result.isEmpty()) {
                DepthSegment last = result.get(result.size() - 1);
                boolean lastConstant = Math.abs(last.getDepthStartM() - last.getDepthEndM()) <= EPS;
                boolean constant = Math.abs(segment.getDepthStartM() - segment.getDepthEndM()) <= EPS;
                if (lastConstant && constant
                        && Math.abs(last.getEndDistanceM() - segment.getStartDistanceM()) <= EPS
                        && Math.abs(last.getDepthEndM() - segment.getDepthStartM()) <= EPS) {
                    result.set(result.size() - 1, DepthSegment.builder()
                            .startDistanceM(last.getStartDistanceM())
                            .endDistanceM(segment.getEndDistanceM())
                            .depthStartM(last.getDepthStartM())
                            .depthEndM(segment.getDepthEndM())
                            .kDepth(last.getKDepth())
                            .build());
                    continue;
                }
            }
            result.add(segment);
        }
        return result;
    }

    /** Вертикальный конфликт: интервал вдоль ребра и целевая глубина обхода. */
    private static final class Constraint {
        final double startM;
        final double endM;
        final double target;

        Constraint(double startM, double endM, double target) {
            this.startM = startM;
            this.endM = endM;
            this.target = target;
        }
    }
}
