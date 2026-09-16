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

> Текущий статус: **фундамент проекта**. Есть каркас приложения, среда
> разработки и документация; бизнес-логика впереди (см.
> [дорожную карту](docs/04-delivery/roadmap.md)).

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

Требуется [devenv](https://devenv.sh) и Nix.

```bash
devenv up            # поднять PostgreSQL (в отдельном терминале)
devenv shell         # оболочка с Java 11 и Maven

build                # сборка
test                 # тесты
run                  # запуск сервиса (http://localhost:8080)
verify               # полная проверка
```

Проверка окружения:

```bash
devenv test
```

Запуск через Docker:

```bash
docker compose up --build
```

- API: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Health: http://localhost:8080/actuator/health
- Info: http://localhost:8080/api/v1/info

## Структура репозитория

```
src/main/java/ru/lct/heating/   # код (пакеты по фичам, см. overview.md)
src/main/resources/             # application*.yml
src/test/                       # тесты
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
