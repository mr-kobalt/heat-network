package ru.lct.heating.config;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "heating.app")
public class AppProperties {

    private String storageRoot = "./data";
    private int workerThreads = 1;
    private int defaultDiameterMm = 400;
    private double roundingToleranceM = 0.01;
    /** Радиус кластеризации точек подключения (ADR-0019). */
    private double clusterRadiusM = 500.0;
    /** Сколько ближайших кандидатов врезки рассматривается при выборе. */
    private int tieInCandidates = 6;
    /** Шаг сэмплирования существующей сети кандидатами врезки, м (ADR-0037). */
    private double tieInSampleStepM = 1.0;
    /** Радиус исключения кандидатов врезки вблизи существующих камер, м (ADR-0037). */
    private double tieInChamberExclusionM = 1.0;
    /** Радиус предпочтения существующей камеры при врезке (FR-22, FR-71). */
    private double chamberTieInRadiusM = 10.0;
    /** Штраф за поворот при маршрутизации, м (FR-28, FR-57: минимизация углов). */
    private double turnPenaltyM = 0.0;
    /**
     * Политика финального вывода к точке подключения ОКС (ADR-0024).
     * По умолчанию — ближайший допустимый перпендикуляр к внешней границе.
     */
    private OksApproachPolicy oksApproachPolicy = OksApproachPolicy.PERPENDICULAR_NEAREST;
    /** Запас точки стыковки за границей буфера ОКС, м (ADR-0024). */
    private double oksExitClearanceM = 1.0;
    /**
     * ADR-0040: фильтрация выходов-кандидатов ОКС — отбрасывать хвосты,
     * проходящие через соседние компоненты своего ОКС, повторно входящие в
     * корпус и длиннее {@link #oksExitMaxTailM}.
     */
    private boolean oksExitFilter = true;
    /** ADR-0040: предельная длина хвоста выхода из ОКС, м. */
    private double oksExitMaxTailM = 15.0;
    /**
     * E36: насколько сдвинуть точку выхода ОКС вдоль перпендикуляра за буфер,
     * когда впереди широкий зазор (>{@code maxPairWidth}) или препятствий нет, м.
     * По умолчанию 0: сдвиг даёт место маршруту, но на плотных наборах вносит
     * пересечения (FR-29) — включается опционально.
     */
    private double oksExitExtraBufferM = 0.0;
    /** Алгоритм трассировки по умолчанию (ADR-0027/0034). */
    private String routingAlgorithm = "grid-forest";
    /** Ячейка сетки поиска пути, м (ADR-0034). */
    private double forestGridCellM = 1.0;
    /** Форма сетки поиска пути: square | hex (ADR-0041). Дефолт — hex. */
    private String forestGridShape = "hex";
    /** Предел числа клеток сетки поиска. */
    private long forestGridMaxCells = 16_000_000L;
    /** Число проходов поиска (1 по длине + уточнения по стоимости, ADR-0034). */
    private int forestCostIterations = 2;
    /** Хранилище состояния сетки: auto | memory | postgis (ADR-0033 фаза B). */
    private String forestGridStorage = "auto";
    /** Сторона страницы спилла, клеток (256 -> 256x256). */
    private int forestGridPageCells = 256;
    /** Бюджет кэша страниц спилла, МБ. */
    private int forestGridCacheMb = 512;
    /** Максимальный угол поворота трассы, град (FR-34; по умолчанию жёстко 90). */
    private double forestMaxTurnDeg = 90.0;
    /** Режим ограничения поворота: hard | warn. */
    private String forestTurnEnforcement = "hard";
    /**
     * Локальный ремонт недопустимых поворотов в `refine` (число проходов,
     * ADR-0034): вместо отката всего ребра возвращает сеточную ломаную только
     * в окрестности нарушения.
     */
    private int forestRefineLocalPasses = 2;
    /** Локальный заход на выход по сетке (8 соседей), чтобы стык был ≤90°. */
    private boolean forestExitGridDogleg = true;
    /** ADR-0035: переприсоединение терминалов на уровне дерева (relink). */
    private boolean forestReattachPass = true;
    /** ADR-0035: число проходов переприсоединения. */
    private int forestReattachIterations = 2;
    /**
     * ADR-0044: перестройка дерева — переносить не только терминалы, но и
     * промежуточные узлы с поддеревом. Дефолт — включено.
     */
    private boolean forestRelinkNodes = true;
    /** ADR-0044: радиус поиска кандидатов при переносе узла, м. */
    private double forestRelinkNodesRadiusM = 100.0;
    /** FR-26: предельная степень узла-камеры (число примыканий). */
    private int forestMaxChamberDegree = 4;
    /**
     * ADR-0039: разрешить relink менять точку выхода, выбранную growth, выбирая
     * среди выходов-кандидатов точки ({@code OksApproachResolver.candidatesFor}).
     * По умолчанию {@code false} — relink оптимизирует только точку врезки, а
     * выход (target/tail) фиксирован за growth; переназначение выхода включается
     * флагом.
     */
    private boolean forestRelinkExitRelocation = false;
    /** ADR-0039: предел числа выходов-кандидатов на точку при relink. */
    private int forestRelinkExitCandidatesMax = 6;
    /**
     * R1: предел числа T-точек на ребро при enumerating кандидатов T-врезки в
     * relink. Геометрия рёбер на входе relink — сырая сеточная «лестница»
     * (вершина на клетку), поэтому список точек квадратично велик; прореживание
     * снижает перебор без изменения финальной геометрии (её формирует refine).
     * Дефолт {@code 64} (≈2× быстрее relink); {@code 0} — без ограничения
     * (прежнее поведение, опция).
     */
    private int forestRelinkTpointMax = 64;
    /**
     * R2: досрочно прерывать оценку хода relink, когда частичная стоимость+длина
     * уже не могут оказаться лучше текущего оптимума. Оптимум не меняется,
     * перебор сокращается. По умолчанию включено.
     */
    private boolean forestRelinkCostBound = true;
    /**
     * R3: kNN-отбор кандидатов relink (узлов и рёбер) через пространственный
     * индекс. Дефолт {@code 16} — на проверочных наборах даёт идентичный {@code S}
     * при relink ≈3× быстрее; {@code 0} — без ограничения (полный перебор).
     */
    private int forestRelinkCandidateK = 16;
    /** R3: дополнительный верхний предел расстояния до кандидата, м (0 — без предела). */
    private double forestRelinkCandidateRadiusM = 0.0;
    /**
     * R5a: учитывать {@code Kспец} в оценке хода relink (стоимость спецпроходов).
     * По умолчанию включено; {@code false} — прежняя прокси-оценка (K=1) для A/B.
     */
    private boolean forestRelinkSpecialCost = true;
    /**
     * R5b: учитывать предельную длину при подборе Ду в оценке хода relink
     * (приближённо, по максимальному downstream-пути). По умолчанию включено;
     * {@code false} — прежний подбор только по расходу (A/B).
     */
    private boolean forestRelinkLengthCost = true;
    /** ADR-0035: учитывать точку подключения на границе ОКС (covers вместо contains). */
    private boolean oksOwningIncludeBoundary = true;
    /** ADR-0037: границы сетки включают bbox всех входных объектов. */
    private boolean forestGridIncludeInputBounds = true;
    /**
     * E25/ADR-0045: строгий обход спецпроходов — полоса минимального расстояния
     * вокруг спецобъекта непроходима, пересечение только через «ворота»
     * (перпендикулярно оси). Включено по умолчанию (обязательная часть ТП v2 §4;
     * A/B на наборе E29 — без неподключённых).
     */
    private boolean forestSpecialStrict = true;
    /** E25: шаг «ворот» строгого обхода вдоль оси спецобъекта, м. */
    private double specialGateStepM = 5.0;
    /** E25: ширина «ворот» (коридора пересечения) строгого обхода, м. */
    private double specialGateThicknessM = 1.0;
    /**
     * E25-07: число повторных прогонов со вдвое меньшим шагом «ворот» при
     * неподключённых точках (адаптив достижимости, ТП §2.5).
     */
    private int forestGateRetries = 2;
    /**
     * E35: оптимизация точки врезки корня нового дерева — перпендикулярная
     * проекция на сеть (короче/дешевле). Применяется, только если выключена
     * {@link #forestChamberOptimization} (иначе корень оптимизируется внутри неё,
     * ADR-0051). Включено по умолчанию.
     */
    private boolean forestRootOptimization = true;
    /**
     * E35/ADR-0051: оптимизация положения новых камер — сдвиг к геометрической
     * медиане соседних узлов с локальной перепрокладкой стыков. Включает и
     * корень (привязка к существующей сети сохраняется). Включено по умолчанию.
     */
    private boolean forestChamberOptimization = true;
    /** ADR-0051: радиус поиска клеток-кандидатов вокруг медианы, клеток. */
    private double forestChamberSearchRadiusCells = 3.0;
    /** ADR-0051: предел числа кандидатов позиции на камеру. */
    private int forestChamberMaxCandidates = 25;
    /** ADR-0051: число проходов оптимизации камер. */
    private int forestChamberPasses = 1;
    /**
     * E50-05: объединение близких новых камер (Steiner-vertex merge). Соседние
     * новые камеры сливаются, если итоговая степень ≤ {@code forest-max-chamber-degree}
     * и {@code S} улучшается. По умолчанию включено (даёт выигрыш по {@code S}).
     */
    private boolean forestChamberMerge = true;
    /**
     * Оптимизация E50-05: чинить углы для каждого кандидата merge
     * ({@code true}, default) или отвергать кандидата при нарушении контракта
     * углов без ремонта ({@code false}, быстрее).
     */
    private boolean forestChamberMergeRepair = true;
    /** E50-05: число проходов объединения камер. */
    private int forestChamberMergePasses = 1;
    /**
     * Минимальный угол между любой парой инцидентных рёбер в тепловой камере,
     * град. Маршрутные повороты и технические узлы ограничены
     * {@code forest-max-turn-deg} (≤90°), камеры — этим углом (≥30°).
     */
    private double forestChamberMinAngleDeg = 30.0;
    /**
     * E25-05b: учитывать фактический Ду — пересобирать запретный индекс по
     * максимальному Ду варианта и повторять расчёт (итеративно). По умолчанию
     * выключено (дороже); включает точное соблюдение отступов по Ду (FR-59).
     */
    private boolean forestDiameterAwareBuffers = false;
    /** E25-05b: предел итераций пересборки индекса по фактическому Ду. */
    private int forestDiameterAwareIterations = 2;
    /**
     * E26/ADR-0046: правило 10 м (использовать существующую камеру в радиусе
     * {@code chamber-tie-in-radius-m}) и учёт существующих примыканий в контроле
     * степени ≤4. Включено по умолчанию (обязательная часть ТП v2 §2.4).
     */
    private boolean forestChamberTieInRules = true;
    /** ADR-0036: предел пикселей растровой диагностики сетки при трассировке. */
    private int traceGridMaxPixels = 2048 * 2048;
    private int maxRunHistory = 50;
    private List<String> allowedOrigins = new ArrayList<>(
            List.of("http://localhost:5173", "http://localhost:8081"));
}
