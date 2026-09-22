# Heating Routing Service

Сервис моделирования трасс подключения перспективных объектов капитального
строительства (ОКС) к существующей тепловой сети. Кейс «Лидеры цифровой
трансформации» 2026.

Сервис обрабатывает один совмещённый GeoJSON, автоматически выбирает точки
врезки, строит новую сеть (с общими участками для нескольких ОКС), считает
расходы и условные диаметры, определяет реконструкцию существующей сети,
рассчитывает стоимость, формирует до трёх вариантов и выгружает результат
в GeoJSON.

Сервис headless (без UI) и работает офлайн; оценивается корректность GeoJSON
и расчёта, а не визуализация (см. [протокол встречи](source/) и
[docs/index.md](docs/index.md)).

> Текущий статус: **M1 — рабочий прототип**. Реализованы потоковый ввод
> GeoJSON, диагностика, граф существующей сети, конфигурируемые ограничения,
> частичная маршрутизация, подбор Ду и стоимость, выгрузка GeoJSON, REST API
> и PostgreSQL. Добавлен опциональный фронтенд-визуализатор. Дальше — M2
> (совместные стволы, реконструкция, варианты). См.
> [дорожную карту](docs/04-delivery/roadmap.md).

## Документация

Начните с [docs/index.md](docs/index.md). Кратко:
- [Устав](docs/01-project/charter.md) — зачем и что делаем.
- [Требования](docs/01-project/requirements.md) — FR/NFR.
- [Правила расчёта](docs/02-domain/calculation-rules.md) — таблицы и формулы.
- [Архитектура](docs/03-architecture/overview.md) и [алгоритм](docs/03-architecture/algorithm.md).
- [Дорожная карта](docs/04-delivery/roadmap.md) и [backlog](docs/04-delivery/backlog.md).

## Стек

Java 11 · Spring Boot 2.6.3 · Maven · PostgreSQL 18 + PostGIS ·
springdoc-openapi-ui 1.7.0 · docker-compose · JTS.

## Быстрый старт

Требуется [devenv](https://devenv.sh), Nix и docker/podman (для БД).

```bash
devenv shell         # оболочка с Java 11, Maven, Node/pnpm

db-up                # PostgreSQL + PostGIS из docker-compose
build                # сборка
test                 # тесты
run                  # запуск сервиса (http://localhost:8080)
verify               # полная проверка
```

Проверка окружения:

```bash
devenv test
```

Запуск через Docker (без локального тулчейна):

```bash
docker compose up --build
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Info: http://localhost:8080/api/v1/info

### Локальный запуск приложения

БД поднимается только через docker-compose (ADR-0017) на TCP `localhost:5432`
(БД/пользователь `heating`); `devenv` БД не запускает.

```bash
db-up                       # docker compose up -d db
run                         # mvn spring-boot:run из оболочки devenv
```

## Фронтенд-визуализатор (опционально)

Отдельное SPA для проверки результата: карта, параметры объектов, сравнение
вариантов. Не входит в оцениваемую поставку (ADR-0013).

```bash
pnpm --dir frontend install
pnpm --dir frontend dev      # http://localhost:5173 (proxy /api → :8080)
pnpm --dir frontend build
```

Через Docker (весь стек app + db + визуализатор):

```bash
fe-up        # docker compose --profile frontend up --build -d
stop         # остановить контейнеры (без удаления)
down         # остановить и удалить стек
```

Визуализатор: http://localhost:8081, сервис: http://localhost:8080.

Визуализатор умеет открыть GeoJSON результата напрямую (без backend) и
запустить расчёт через API. При расчёте через сервис запуск помечается
`?trace=true`, и визуализатор показывает промежуточные этапы алгоритма
(`grid-forest`) вкладками: «Вход → Сеть → Ограничения → Выходы → Сетка →
Деревья (#проход) → Refine → Relink → Итог» (ADR-0036/0037); проходы поиска
доступны из заголовка «Деревья» и возвращаются как отдельные варианты. Данные
этапов — `GET /api/v1/runs/{id}/stages[/{stageId}]`. Офлайн-подложка PMTiles —
см. `frontend/public/basemap/README.md`.

## Структура репозитория

```
src/main/java/ru/lct/heating/   # код (пакеты по фичам, см. overview.md)
src/main/resources/             # application*.yml, schema.sql
src/test/                       # тесты
frontend/                       # опциональный визуализатор (React/MapLibre)
docs/                           # проектная документация
devenv.nix                      # среда разработки
pom.xml                         # сборка Maven
Dockerfile / docker-compose.yml # контейнеризация
```

## Исходные материалы

См. папку [`source/`](source/):

- `2. ДИТ.pdf` — описание кейса.
- `Техническое приложение.docx` — правила и форматы.
- `data_example.geojson` — образец набора (неполный, см.
  [анализ](docs/05-data/sample-dataset-analysis.md)).
- `Протокол встречи 16.09.2026.md` — ответы организатора на вопросы участников.

## История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-16 | Первоначальная версия | команда |
| 2026-09-16 | Добавлен протокол встречи в исходные материалы | команда |
| 2026-09-19 | Обновлён статус M1, добавлены разделы запуска приложения и фронтенд-визуализатора | команда |
| 2026-09-19 | Единая БД через docker-compose; devenv без Postgres (ADR-0017) | команда |
| 2026-09-19 | Добавлен скрипт `fe-up` для запуска всего стека с визуализатором | команда |
| 2026-09-19 | Надёжная остановка: скрипт `stop`, `down` через stop + `down --remove-orphans` (podman-compose) | команда |
| 2026-09-22 | ADR-0036: opt-in поэтапная трассировка (`?trace=true`, API `/runs/{id}/stages`) и вкладки этапов в визуализаторе | команда |
| 2026-09-22 | ADR-0037: буферы по мин. Ду, выход ОКС по внешнему контуру своей компоненты (узкий промежуток, выбор по достижимости), кандидаты врезки 1 м, границы сетки по всему входу, варианты по проходам | команда |
