# Бенчмарки и история тестов

Исторические прогоны, baseline-числа и эксперименты, вынесенные из
[testing-strategy.md](testing-strategy.md) для читаемости. Актуальная
стратегия и обязательные проверки — в testing-strategy.md.

## 1. Реализовано в M1

- Unit-тесты: `DiameterCatalogTest`, `CostModelTest`, `CrsTransformerTest`,
  `IngestServiceTest`, `NetworkGraphBuilderTest`, `VisibilityGraphRouterTest`.
- Сквозной `CalculationPipelineTest` (переведён на
  `source/Датасет скорректированный.geojson`): конвейер отдаёт валидный GeoJSON
  и связную (частичную) сеть. Старый `data_example.geojson` — исторический.
- Веб-слой: `InfoControllerTest` (`@WebMvcTest`).
- Фронтенд: `types.test.ts`, `paint.test.ts` и `style-validation.test.ts`
  (проверка корректности paint-выражений слоёв по официальному
  `@maplibre/maplibre-gl-style-spec`).
- Проверено вручную через API: upload → run (async) → result на PostgreSQL.

## 2. Реализовано в M1–M2 (до ревизии v2)

- Спецпроходы/габариты/округление: `SpecialZoneIndexTest`,
  `SpecialZoneIndexBuilderTest`, `SpecialSpanSplitterTest`,
  `RestrictionAxisBuilderTest`, `EnvelopeCatalogTest`, `RoundingTest`.
- Лес/гидравлика: `ForestPlannerTest`, `LineStringSimplifierTest`,
  `RouteCrossingResolverTest`, `MaxLengthEnforcerTest`.
- Варианты/вывод: `VariantGeneratorTest`, `GeoJsonResultWriterTest`.
- Удалены при ревизии v2: `ReconstructionPlannerTest`, `BendClassifierTest`.

## 3. Ревизия по ТП v2 (эпик E13) — реализовано

- Удалены тесты реконструкции и удорожания углов (`ReconstructionPlannerTest`,
  `BendClassifierTest`).
- `MaxLengthEnforcerTest` переписан: предельная длина по путям, общий участок в
  каждом пути, ветви не суммируются, Ду не убывает к присоединению.
- Добавлены: `ObstacleIndexBuilderTest` (финальный коридор в `oks`);
  `ForestPlannerTest` — выбор существующей камеры; `GeoJsonResultWriterTest` —
  numeric ID и отсутствие `tie_in`/реконструкции;
  `IngestServiceTest` — сохранение типа ID.
- `CalculationPipelineTest` переведён на `source/Датасет скорректированный.geojson`
  и запускается в **production-подобной** конфигурации (габариты/полуширина,
  `turn-penalty-m`): все 17 точек подключаются, проверяется соответствие
  `start_node_id`/`end_node_id` концам `LineString` (FR-83).
- Итого `mvn test` — 56 тестов без внешней БД.

## 4. Быстрые тесты и slow-набор (ADR-0027)

- `mvn test` по умолчанию **быстрый** (секунды): сквозной
  `CalculationPipelineTest` выполняется на маленьком фикстуре
  `src/test/resources/datasets/pipeline-small.geojson` с алгоритмом по
  умолчанию `grid-forest`.
- Полный прогон на `source/Датасет скорректированный.geojson` вынесен в
  `CalculationPipelineSlowTest` с тегом `slow`; surefire исключает его по
  умолчанию (`surefire.excludedGroups=slow`).
- Запуск полного: `mvn test -Dsurefire.excludedGroups= -Dgroups=slow`.
- Плагинный слой проверяется без датасета: `TracingAlgorithmRegistryTest`
  (дефолт/`require`/дубликат), `AlgorithmControllerTest` (`@WebMvcTest`).
- **Свип мета-параметров** `AlgorithmParameterSweepTest` (тег slow): полный
  Spring-контекст + PostGIS (Testcontainers, без Docker — пропуск), реальный
  набор; полный перебор `forest-grid-cell-m` × `forest-cost-iterations` ×
  `forest-max-turn-deg` × `forest-grid-storage`. Отчёт:
  `target/algorithm-sweep/report.{md,csv,json}` + таблица в консоль.
  Запуск: `mvn test -Dtest=AlgorithmParameterSweepTest
  -Dsurefire.excludedGroups= -Dgroups=slow`.
- **Свип v2** `AlgorithmParameterSweepV2Test` (тег slow, ADR-0035): угол
  фиксирован 90°, двухстадийный — поведенческие флаги присоединения
  (`multi-entry`, `cell-search`, `reattach`, `refine-passes`, `exit-dogleg`,
  `boundary`, 96) + подсвип `entry-cells`, затем сетка (`cell × iterations ×
  storage`). Метрики включают `edgePoint6` и `warn90`. Отчёты:
  `target/algorithm-sweep-2/{stage1,entrycells,stage2}/report.{md,csv,json}`.
  Итог: `multi-entry=true` + `reattach=true` → `S` 12.810, точка 6 ≈33.6 м.
- `PostgisCellStoreTest` (slow, БД) проверяет спилл и **свежесть** TEMP-таблицы
  при переиспользовании соединения из пула (второй стор не видит страницы
  первого).
- **Дефолты трассировки** фиксируются `AppPropertiesDefaultsTest` (fast):
  `grid-forest`, cell 1.0, shape `hex`, cost-iterations 2, turn 90,
  `storage=auto`, переприсоединение, `forest-relink-exit-relocation=false`
  (relink не меняет выход growth), `oks-exit-filter=true`, граница ОКС.
  Привязка настроек к `application.yml`/env — `AppPropertiesEnvBindingTest`
  (fast): каждое конфигурируемое поле имеет ключ в yml, env-имена —
  `HEATING_<ПОЛЕ>`.
  Регресс на реальном наборе — `CalculationPipelineSlowTest
  .producesRun28BaselineWithDefaultParameters` (baseline после ADR-0044,
  `forest-relink-nodes=true`: `score` ≈ 13.3512, длина ≈ 1893.48 м,
  `calculatedCost` = 273956373, `chamberConstructionCost` = 55000000,
  `unconnected` = 0, `TURN_ANGLE_EXCEEDS_90` ≤ 6, не более 15 вершин на участок,
  ребро к точке 6 < 60 м; гекс-сетка, cell 1 м; relink не проверяет углы
  маршрута, refine чинит их в узлах; степень узла ≤4 (FR-26); нет тупиковых
  технических узлов).
- **Перестройка дерева** `RelinkNodesExperimentTest` (slow, ADR-0044): сравнение
  `forest-relink-nodes` off/on, отчёт `target/relink-nodes/report.md`
  (off: `S` ≈ 14.0512; on: `S` ≈ 13.351, длина ≈ 1893.5 м, стоимость ≈ 274.0 млн).
- **Опция `relocation=true`** — отдельный slow-тест
  `CalculationPipelineSlowTest.relinkExitRelocation_consolidatesPoints368`
  (при `forest-relink-nodes=false`): точки 3, 6, 8 сходятся на одной камере
  (проверка `assertConnectionPointsShareChamber`).
- **A/B формы сетки** `GridShapeExperimentTest` (тег slow, ADR-0041): квадрат
  против гекса на реальном наборе, отчёт `target/grid-shape/report.md` (`S`,
  длина, стоимость, камеры, углы, пересечения, консолидация 3/6/8, время).
- Вывод алгоритма `grid-forest` (единый лес, ADR-0034) дополнительно проверяется на
  фикстуре: ссылки узлов = концы геометрии, точки подключения — листья,
  ветвления только в камерах, нет камер с id точки подключения, участки не
  входят в `oks`-полигоны вне финального вывода, `unconnected` согласованы.
  Юнит-тесты `GridForestTracingAlgorithmTest`: точка в ОКС сохраняет вершину
  выхода (`target`), близкие точки вдали от сети делят одно дерево
  (T-присоединение), угол поворота не превышает 90°.

## История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-29 | Раздел выделен из testing-strategy.md (исторические прогоны и baseline) | команда |
