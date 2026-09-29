# ADR-0074. Удаление устаревших подсистем

- Статус: accepted
- Дата: 2026-09-29

## Контекст

К финальной сдаче (M5) в коде накопились мёртвые и устаревшие ветки:

- алгоритм `mst` (`MstTracingAlgorithm`) скрыт из `/api/v1/algorithms` и
  отклоняется реестром, но остаётся единственным потребителем `ForestPlanner`,
  `RouteCrossingResolver`, `VisibilityGraphRouter` и политики вывода к ОКС
  ADR-0024 (`OksApproachPolicy`, `oks-approach-policy`, `oks-exit-clearance-m`);
- пространственная декомпозиция `forest-decomposition` (ADR-0068) помечена
  `superseded` в пользу партиционирования входа (ADR-0071), но код и хелперы
  сохранены ради совместимости;
- устаревшие типы `oks_future`/`oks_existing` (ТП v2 их не использует)
  разбирались в доменные объекты и хранились в `NetworkDataset` всегда пустыми.

Всё это увеличивает объём, запутывает чтение и не участвует в обязательном
расчёте.

## Решение

Удалить перечисленные подсистемы:

1. **`mst`-цепочка** — `MstTracingAlgorithm`, `ForestPlanner`,
   `RouteCrossingResolver`, `VisibilityGraphRouter`, ветки ADR-0024 в
   `OksApproachResolver` (`resolve`/`resolveCandidates`/`approachCandidates` и
   вспомогательные методы), enum `OksApproachPolicy` и поля `AppProperties`
   (`clusterRadiusM`, `tieInCandidates`, `turnPenaltyM`, `oksApproachPolicy`,
   `oksExitClearanceM`) вместе с ключами `application.yml`. Живой вывод к ОКС —
   по ADR-0023/0032 и ADR-0065…0067.
   **ADR-0024 → superseded by ADR-0032.**
2. **`forest-decomposition`** — флаг `forest-decomposition`, радиус
   `forest-cluster-radius-m` и cluster-only хелперы; остаётся партиционирование
   входа (ADR-0071) и запас тайла `forest-cluster-margin-m`.
3. **`oks_future`/`oks_existing`** — доменные объекты и поля `NetworkDataset`;
   типы по-прежнему **молча игнорируются** при разборе (FR-17), без создания
   объектов и без предупреждений.

Дополнительно: зависимость SnakeYAML поднята 1.29 → 1.30 — в 1.29 присутствует
дефект `StreamReader.peek` при разборе `application.yml` с многобайтным UTF-8
(падение зависит от выравнивания буфера).

## Следствия

- Положительные: меньше мёртвого кода; обязательная 2D-часть и baseline не
  меняются (проверено: осн. `S` 13.29925214746317, OSM 13.679614324110897 и
  13.682035191484873).
- Отрицательные: удалены тесты `OksApproachResolverTest`,
  `RouteCrossingResolverTest`, `VisibilityGraphRouterTest` (проверяли мёртвую
  ветку); снижено покрытие legacy-кода, который не участвует в расчёте.
- Выбор алгоритма по API (FR-86/FR-87) сохраняется: остаётся `grid-forest`.

## История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-29 | Первоначальная версия | команда |
