package ru.lct.heating.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Аккумулятор промежуточных данных алгоритма для визуализации (ADR-0036).
 * Заполняется только при включённой трассировке; {@link #disabled()} —
 * безопасный no-op, не влияющий на результат и производительность расчёта.
 */
public final class StageTrace {

    public static final String NETWORK = "network";
    public static final String OBSTACLES = "obstacles";
    public static final String SPECIAL = "special";
    public static final String EXITS = "exits";
    public static final String GRID = "grid";
    public static final String REFINE = "refine";
    public static final String RELINK = "relink";

    private final boolean enabled;
    private final Map<String, List<StageFeature>> stages = new LinkedHashMap<>();
    private final List<Integer> treePasses = new ArrayList<>();
    private GridMaskPayload grid;
    private int bestPass = 1;
    private int passes = 1;

    private StageTrace(boolean enabled) {
        this.enabled = enabled;
    }

    public static StageTrace disabled() {
        return new StageTrace(false);
    }

    public static StageTrace enabled() {
        return new StageTrace(true);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void addStage(String id, List<StageFeature> features) {
        if (!enabled) {
            return;
        }
        stages.put(id, features == null ? List.of() : features);
    }

    /** Сырое (до сглаживания) дерево одного прохода поиска. */
    public void addTreePass(int pass, List<StageFeature> features) {
        if (!enabled) {
            return;
        }
        addStage("trees-" + pass, features);
        treePasses.add(pass);
    }

    public void addGrid(GridMaskPayload payload) {
        if (enabled) {
            this.grid = payload;
        }
    }

    public void setBestPass(int bestPass) {
        this.bestPass = bestPass;
    }

    public void setPasses(int passes) {
        this.passes = passes;
    }

    public boolean hasStage(String id) {
        return stages.containsKey(id);
    }

    public List<StageFeature> features(String id) {
        return stages.getOrDefault(id, List.of());
    }

    public Map<String, List<StageFeature>> stages() {
        return Collections.unmodifiableMap(stages);
    }

    public GridMaskPayload grid() {
        return grid;
    }

    public List<Integer> treePasses() {
        return Collections.unmodifiableList(treePasses);
    }

    public int bestPass() {
        return bestPass;
    }

    public int passes() {
        return passes;
    }
}
