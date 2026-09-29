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
     * E8-15c: пространственная декомпозиция — радиус связности точек
     * подключения (union-find), м. Точки в пределах радиуса образуют кластер;
     * каждый кластер решается на своей локальной сетке.
     */
    private double forestClusterRadiusM = 3000.0;
    /** E8-15c: запас локального bbox кластера (сеть/врезки), м. */
    private double forestClusterMarginM = 2000.0;
    /**
     * E8-15d2c: тайл партиционирования входа, м (0 — партиционирование
     * выключено). Вход режется на пространственные тайлы, каждый считается
     * независимо — рабочий набор ограничен областью.
     */
    private double forestPartitionTileM = 0.0;
    /** E8-15d2c: запас тайла (дублирование пограничных объектов), м. */
    private double forestPartitionMarginM = 0.0;
    /**
     * E8-15d2c2/E8-03b: не подменять канонический выход точки альтернативным при
     * локальной недостижимости (FR-43). Включается на время партиционированного
     * прогона (каждый тайл — локальная сетка).
     */
    private boolean forestPreserveCanonicalExits = false;
    /**
     * E8-15c: включать пространственную декомпозицию. По умолчанию выключено:
     * локальная дискретизация кластера может изменить канонический выход
     * (FR-43/E43), поэтому режим предназначен для очень крупных наборов и
     * требует отдельной валидации. {@code false} — единая сетка по всему входу.
     */
    private boolean forestDecomposition = false;
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
    /**
     * E8-14: бюджет расширений {@code gridPath} при обходе заблокированного
     * стыка камеры/корня. Меньше — быстрее; слишком малое значение может не
     * найти дальний обход.
     */
    private int forestChamberGridExpansions = 4000;
    /**
     * E8-14: бюджет вершин visibility-графа при обходе стыка камеры
     * (отличается от {@code forest-exit-visibility-max-nodes}).
     */
    private int forestChamberVisibilityMaxNodes = 100;
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
     * E50-07 (ADR-0065): visibility-фолбэк для терминального ребра, когда сетка не
     * прошивает узкий свободный коридор у своего ОКС. Точный поиск по вершинам
     * запретов с касанием границы (без дискретизации). По умолчанию включено.
     */
    private boolean forestExitVisibilityFallback = true;
    /** E50-07: минимальное превышение длины ствола над прямой для попытки фолбэка, м. */
    private double forestExitVisibilityMinDetourM = 2.0;
    /**
     * E50-07: максимальное превышение длины ствола над прямой, м. Больший
     * перепробег — не дискретизация сетки (фолбэк бесполезен), попытка не делается.
     */
    private double forestExitVisibilityMaxDetourM = 10.0;
    /**
     * E50-07: детерминированный предел числа попыток фолбэка на прогон (защита
     * времени на city-scale, NFR-08). Кандидаты отбираются в устойчивом порядке.
     */
    private int forestExitVisibilityMaxAttempts = 200;
    /** E50-07: предел числа вершин графа видимости в фолбэке терминала. */
    private int forestExitVisibilityMaxNodes = 200;
    /**
     * ADR-0066 (E50-08): регуляризация выхода терминала — visibility-спрямление
     * подхода к `target`, привязка ветвящейся камеры к `target`/перпендикуляру
     * выхода и устранение микрозвеньев у точки подключения. По умолчанию
     * включено (A/B: осн./OSM `S` не хуже, OSM поворот у точки 4 устранён).
     */
    private boolean forestExitRegularization = true;
    /**
     * ADR-0066: порог привязки ветвящейся камеры (или T-точки relink) к
     * канонической точке выхода `target`, м.
     */
    private double forestExitSnapM = 1.0;
    /**
     * ADR-0066: порог устранения микрозвеньев ствола терминала (вершины ближе
     * этого расстояния к соседней или к `target` склеиваются), м.
     */
    private double forestExitMicroM = 0.5;
    /**
     * ADR-0062: глобальный оптимизатор Ду — подбор диаметров на всё дерево
     * (расход, невозрастание к точке подключения, предел плети, стоимость
     * участков и камер) вместо покомандного {@code MaxLengthEnforcer}. Включён
     * по умолчанию; запускается финальным проходом только там, где предельная
     * длина связывает (иначе результат совпадает с {@code MaxLengthEnforcer}).
     * Выключается {@code HEATING_FOREST_DIAMETER_OPTIMIZER=false}; при
     * превышении бюджета — фолбэк на {@code MaxLengthEnforcer}.
     */
    private boolean forestDiameterOptimizer = true;
    /** ADR-0062: бюджет состояний DP глобального оптимизатора Ду. */
    private int forestDiameterOptimizerMaxStates = 500_000;
    /**
     * E26/ADR-0046: правило 10 м (использовать существующую камеру в радиусе
     * {@code chamber-tie-in-radius-m}) и учёт существующих примыканий в контроле
     * степени ≤4. Включено по умолчанию (обязательная часть ТП v2 §2.4).
     */
    private boolean forestChamberTieInRules = true;
    /**
     * ADR-0073 (ТП v2 §5): режим с учётом глубины. Обычная глубина до верхней
     * границы расчётного габарита новой сети, м.
     */
    private double depthNormalM = 3.0;
    /** ADR-0073: минимальная глубина (до верхней границы габарита), м. */
    private double depthMinM = 0.7;
    /** ADR-0073: максимальный уклон профиля, м/м (≤ 0,10). */
    private double depthMaxSlope = 0.10;
    /**
     * ADR-0073: вертикальный зазор между габаритом новой сети и коммуникацией
     * (допущение: ТП v2 чисел не задаёт), м.
     */
    private double depthVerticalClearanceM = 0.2;
    /**
     * ADR-0073: критерий «близких» препятствий (допущение вместо NQ-05): ближе
     * этого зазора профиль между препятствиями не возвращается на 3,0 м, м.
     */
    private double depthCloseCrossingM = 5.0;
    /** ADR-0036: предел пикселей растровой диагностики сетки при трассировке. */
    private int traceGridMaxPixels = 2048 * 2048;
    private int maxRunHistory = 50;
    private List<String> allowedOrigins = new ArrayList<>(
            List.of("http://localhost:5173", "http://localhost:8081"));
}
