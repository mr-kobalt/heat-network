# Технологический стек

## 1. Обязательные компоненты (из кейса)

| Слой | Технология | Версия | Источник |
|------|-----------|--------|----------|
| ОС | Ubuntu Server | 22 | PDF 3.2 |
| Runtime | OpenJDK (Java) | 11 | PDF 3.2 |
| Framework | Spring Boot (`spring-boot-starter-parent`) | 2.6.3 | PDF 3.2 |
| Доступ к данным | Spring Data (JPA) | из состава SB 2.6.3 | протокол |
| Сборка | Apache Maven | 3.9.x (devenv) | ADR-0002 |
| БД | PostgreSQL | ≤ 18 | PDF 3.2 |
| Расширение БД | PostGIS | совместимое с PG | ADR-0003 |
| API-документация | springdoc-openapi-ui (Swagger UI) | 1.7.0 | PDF 3.2 |
| Контейнеризация | docker-compose | 1.29.2 | PDF 3.2 |

## 2. Основные зависимости приложения

| Зависимость | Назначение |
|-------------|-----------|
| `spring-boot-starter-web` | REST API |
| `spring-boot-starter-validation` | Валидация входных DTO |
| `spring-boot-starter-actuator` | Health/info |
| `spring-boot-starter-data-jpa` | Доступ к PostgreSQL |
| `postgresql` (runtime) | Драйвер |
| `org.locationtech.jts:jts-core:1.19.0` | Вычислительная геометрия |
| `springdoc-openapi-ui:1.7.0` | Swagger UI |
| `lombok` | Сокращение boilerplate |

## 3. Тестирование

| Инструмент | Назначение |
|-----------|-----------|
| JUnit 5 (`spring-boot-starter-test`) | Unit/интеграционные тесты |
| MockMvc | Тесты веб-слоя |
| Testcontainers 1.16.3 (`postgresql`) | Интеграция с реальным PostgreSQL/PostGIS |

## 4. Среда разработки (devenv)

devenv отвечает **только за тулчейн** (Java 11, Maven, Node/pnpm);
PostgreSQL/PostGIS поднимается единственным способом — контейнером `db`
из docker-compose (ADR-0017).

Управляется `devenv.nix` / `devenv.yaml` / `devenv.lock`:

- `languages.java` с `pkgs.jdk11`;
- `languages.maven`;
- `languages.javascript` (Node 22 + pnpm) для визуализатора;
- пакеты `git`, `jq`, `curl`, `unzip`;
- скрипты `build`, `test`, `verify`, `run`, `fe-dev`, `fe-build`,
  `db-up`, `db-down`, `db-logs`, `up`, `down`;
- `devenv test` — быстрый smoke (Java 11, Maven, Node).

Команды:

```bash
devenv shell       # оболочка с toolchain
db-up              # PostgreSQL + PostGIS из docker-compose
build | test | verify | run
fe-dev | fe-build
```

## 5. Профили Spring

| Профиль | Назначение |
|---------|-----------|
| по умолчанию (`application.yml`) | локально; datasource — TCP `localhost:5432`, БД/пользователь `heating` (как у compose `db`) |
| `docker` (`application-docker.yml`) | в docker-compose; `db:5432` |

## 6. Известные ограничения версий

- Flyway из состава SB 2.6.3 (v8) несовместим с PostgreSQL 18 — миграции
  отложены (ADR-0005).
- Новые версии некоторых библиотек требуют Java 17 — перед обновлением
  проверять совместимость с Java 11.
- `docker-compose` 1.29.2 — устаревший формат; `docker-compose.yml` использует
  `version: "3.8"` для совместимости v1 и v2.

## 7. Уточнения по протоколу встречи

- Всё решение — только Java 11 (Spring Boot + Spring Data). Kotlin, другая
  версия Spring, C++ и вынос ядра в другой язык не принимаются.
- UI не требуется (headless); визуализация не оценивается; Swagger желателен.
- PostGIS допустим.
- Расчёт офлайн, без внешних сервисов и агентов с токенами.
- Сервис в единственном экземпляре; горизонтальное масштабирование опционально.
- Репозиторий с исходниками и скриптами деплоя (Maven/Gradle) передаётся
  организаторам для развёртывания на их мощностях.

## 8. М1: реализованные зависимости и стек визуализатора

Backend:

- `org.locationtech.proj4j:proj4j` — CRS 4326↔32637 (ADR-0014);
- `schema.sql` + JPA (`ddl-auto=none`) вместо Flyway (ADR-0015);
- `ThreadPoolTaskExecutor` для асинхронных запусков (ADR-0016).

Визуализатор (`frontend/`, опционально, ADR-0013):

| Слой | Технология |
|------|-----------|
| Фреймворк | React 18 + TypeScript + Vite |
| UI | Mantine v7 |
| Карта | MapLibre GL JS + `pmtiles` (офлайн-подложка) |
| Состояние | Zustand; TanStack Query |
| Тесты | Vitest + Testing Library |
| Контейнер | nginx (профиль compose `frontend`) |

Node.js 22 + pnpm 11 добавляются через devenv.

## 9. История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-16 | Первоначальная версия | команда |
| 2026-09-16 | Обновлено по протоколу встречи 16.09.2026: Spring Data, офлайн, единственный экземпляр, без UI, PostGIS подтверждён, скрипты деплоя | команда |
| 2026-09-19 | Добавлены Proj4J и стек визуализатора (React/MapLibre/Mantine), Node 22 + pnpm | команда |
| 2026-09-19 | Единая БД через docker-compose; devenv без Postgres; FQIN для образов (ADR-0017) | команда |
