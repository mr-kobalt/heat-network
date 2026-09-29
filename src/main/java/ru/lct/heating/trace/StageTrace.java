package ru.lct.heating.trace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Аккумулятор промежуточных данных алгоритма для визуализации (ADR-0036).
 * Заполняется только при включённой трассировке; {@link #disabled()} —
 * безопасный no-op, не влияющий на результат и производительность расчёта.
 */
public final class StageTrace {

    public static final String NETWORK = "network";
    /** Объединённый этап: запретные буферы (`obstacle`) и спецзоны (`special_zone`). */
    public static final String RESTRICTIONS = "restrictions";
    public static final String EXITS = "exits";
    /** Кандидаты врезки в существующую сеть. */
    public static final String TIES = "ties";
    public static final String GRID = "grid";
    public static final String TREES = "trees";
    public static final String RELINK = "relink";
    /** Контракция сквозных (degree-2) узлов и ориентация от корня (FR-30). */
    public static final String CONTRACT = "contract";
    public static final String REFINE = "refine";
    /** Оптимизация положения новых камер/корня (ADR-0051). */
    public static final String CHAMBERS = "chambers";

    private final boolean enabled;
    private final Map<String, List<StageFeature>> stages = new LinkedHashMap<>();
    /** Для каждой постадийной базы — номера проходов, для которых есть снимок. */
    private final Map<String, List<Integer>> passStages = new LinkedHashMap<>();
    private GridMaskPayload grid;
    private int bestPass = 1;
    private int passes = 1;
    /** ADR-0057: отчёт о проходе поиска (номер, всего) — работает и без трассировки. */
    private BiConsumer<Integer, Integer> passProgress;

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
        addPassStage(TREES, pass, features);
    }

    /**
     * Постадийный снимок одного прохода поиска (ADR-0036): файл будет записан
     * как {@code <baseId>-<pass>.geojson}, а в манифесте стадия получит список
     * проходов. Так шаги после «Деревьев» показывают выбранное дерево.
     */
    public void addPassStage(String baseId, int pass, List<StageFeature> features) {
        if (!enabled) {
            return;
        }
        addStage(baseId + "-" + pass, features);
        List<Integer> passes = passStages.computeIfAbsent(baseId, key -> new ArrayList<>());
        if (!passes.contains(pass)) {
            passes.add(pass);
        }
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

    /** ADR-0057: слушатель прогресса по проходам поиска (вызывается даже при disabled). */
    public void setPassProgress(BiConsumer<Integer, Integer> passProgress) {
        this.passProgress = passProgress;
    }

    /** Сообщить о начале прохода {@code pass} из {@code total} (ADR-0057). */
    public void reportPass(int pass, int total) {
        if (passProgress != null) {
            passProgress.accept(pass, total);
        }
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

    /** База стадии → номера проходов, для которых записаны снимки. */
    public Map<String, List<Integer>> passStages() {
        return Collections.unmodifiableMap(passStages);
    }

    /** Идентификатор относится к постадийной стадии ({@code relink-2} и т.п.). */
    public boolean isPassStageKey(String id) {
        for (String base : passStages.keySet()) {
            if (id.startsWith(base + "-")) {
                return true;
            }
        }
        return false;
    }

    public int bestPass() {
        return bestPass;
    }

    public int passes() {
        return passes;
    }
}
