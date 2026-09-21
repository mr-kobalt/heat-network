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
    /** Алгоритм трассировки по умолчанию (ADR-0027/0034). */
    private String routingAlgorithm = "grid-forest";
    /** Ячейка сетки поиска пути, м (ADR-0034). */
    private double forestGridCellM = 2.0;
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
    private int maxRunHistory = 50;
    private List<String> allowedOrigins = new ArrayList<>(
            List.of("http://localhost:5173", "http://localhost:8081"));
}
