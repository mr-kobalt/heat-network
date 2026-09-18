# Architecture Decision Records (ADR)

Формат: [Michael Nygard](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).
Статусы: `proposed`, `accepted`, `deprecated`, `superseded by ADR-XXXX`.

## Индекс

| ADR | Решение | Статус |
|-----|---------|--------|
| [0001](0001-mandated-stack.md) | Обязательный технологический стек | accepted |
| [0002](0002-build-tool-maven.md) | Maven как инструмент сборки | accepted |
| [0003](0003-postgresql-postgis.md) | PostgreSQL + PostGIS | accepted |
| [0004](0004-jts-geometry.md) | JTS для вычислительной геометрии | accepted |
| [0005](0005-schema-migrations-deferred.md) | Миграции БД отложены | accepted |
| [0006](0006-heuristic-routing.md) | Эвристический конвейер маршрутизации | accepted |
| [0007](0007-depth-as-phase-2.md) | Глубина — отдельная фаза | accepted |
| [0008](0008-streaming-ingest.md) | Потоковый ввод/вывод | accepted |
| [0009](0009-configurable-restrictions.md) | Конфигурируемые правила ограничений | accepted |
| [0010](0010-headless-no-ui.md) | Headless-сервис без UI | accepted |
| [0011](0011-overlap-max-coefficient.md) | Наложение ограничений: максимум коэффициента, один спецучасток | accepted (ждёт подтверждения) |
| [0012](0012-offline-single-instance.md) | Офлайн-режим и единственный экземпляр | accepted |
| [0013](0013-optional-frontend-visualizer.md) | Опциональный фронтенд-визуализатор | accepted |
| [0014](0014-crs-transformation-proj4j.md) | Преобразование координат через Proj4J | accepted |
| [0015](0015-db-schema-sql.md) | Схема БД в M1 через schema.sql | accepted |
| [0016](0016-async-run-model.md) | Асинхронная модель запуска расчёта | accepted |
| [0017](0017-single-db-docker-compose.md) | Единая БД через docker-compose | accepted |

## Шаблон

```markdown
# ADR-XXXX. Краткое название

- Статус: proposed | accepted | deprecated | superseded
- Дата: YYYY-MM-DD
- Контекст: ...

## Решение
...

## Следствия
- Положительные: ...
- Отрицательные: ...
```

## Как добавить

1. Создайте `NNNN-kratkoe-nazvanie.md`.
2. Заполните шаблон, добавьте строку в индекс.
3. Если решение отменяет предыдущее — пометьте старое `superseded by ADR-XXXX`.
4. При изменении ADR добавляйте строку в его «Историю изменений».

## История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-16 | Первоначальная версия | команда |
| 2026-09-16 | Добавлены ADR-0010…0012 по протоколу встречи 16.09.2026 | команда |
| 2026-09-19 | Добавлены ADR-0013…0017 (фронтенд, CRS, schema.sql, async, единая БД) | команда |
