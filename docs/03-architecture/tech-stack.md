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
| API-документация | springdoc-openapi-ui (Swagger UI) | 1.7.0 | PDF 3.2; описания — ADR-0075 |
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

## 4. Сборка, запуск и среда разработки

**Единый интерфейс команд — корневой `Makefile`** (`make help`). Он не требует
nix/devenv: достаточно GNU make, JDK 11 (Maven подтягивается через
**Maven Wrapper** `./mvnw`) и docker/podman для БД/стека. Ключевые цели:
`compile`, `build`, `test`, `test-slow`, `verify`, `run`, `dev`, `db-*`,
`up`/`fe-up`/`stop`/`down`/`ps`, `fe-*`, `compose-config`, `deploy`.

**devenv** (опционально) отвечает за тулчейн и даёт тонкие обёртки над `make`:
`languages.java` (`pkgs.jdk11`), `languages.maven`, `languages.javascript`
(Node 22 + pnpm), пакеты `git`, `jq`, `curl`, `unzip`, `gnumake`,
`poppler-utils` (`pdftotext` — чтение PDF), `pandoc` (`.docx`), `python3`.
PostgreSQL/PostGIS поднимается только контейнером `db` из docker-compose
(ADR-0017). `devenv test` — быстрый smoke (Java 11, Maven, Node, Make,
`pdftotext`).

Команды:

```bash
make help                          # список команд
make db-up && make run             # БД + сервис
make dev                           # БД + приложение + визуализатор
make fe-up                         # весь стек (app + db + frontend) в Docker
devenv shell                       # то же окружение через Nix (опционально)
```

**CI** — GitHub Actions (`.github/workflows/ci.yml`): backend (`./mvnw -B verify`,
JDK 11), frontend (pnpm test/build, Node 22) и проверка `docker compose config`.

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
| Markdown | `react-markdown` + `remark-gfm`; `rehype-slug`/`github-slugger` для якорей и ToC (раздел «Документация», ADR-0072) |
| API-документация | встроенный Swagger UI backend-сервиса (раздел «API», ADR-0072) |
| Тесты | Vitest + Testing Library |
| Контейнер | nginx (профиль compose `frontend`); контекст сборки — корень репозитория (`docs/`, ADR-0072) |

Node.js 22 + pnpm 11 добавляются через devenv.

## 9. История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-16 | Первоначальная версия | команда |
| 2026-09-16 | Обновлено по протоколу встречи 16.09.2026: Spring Data, офлайн, единственный экземпляр, без UI, PostGIS подтверждён, скрипты деплоя | команда |
| 2026-09-19 | Добавлены Proj4J и стек визуализатора (React/MapLibre/Mantine), Node 22 + pnpm | команда |
| 2026-09-19 | Единая БД через docker-compose; devenv без Postgres; FQIN для образов (ADR-0017) | команда |
| 2026-09-19 | Добавлены скрипты `fe-up`/`fe-logs` (весь стек с визуализатором) | команда |
| 2026-09-19 | Скрипт `stop`; `down` через stop + `down --remove-orphans` (podman-compose) | команда |
| 2026-09-29 | Визуализатор: `react-markdown`/`remark-gfm` (раздел «Документация»), встроенный Swagger UI, контекст сборки — корень репозитория (ADR-0072) | команда |
| 2026-09-29 | Документация: дерево каталогов и ToC, якоря через `rehype-slug`/`github-slugger` (ADR-0072) | команда |
| 2026-09-29 | M5: единый `Makefile`, Maven Wrapper, CI; devenv-скрипты — обёртки над `make`; добавлены `poppler-utils`/`pandoc`/`python3`/`make` | команда |
| 2026-09-29 | SnakeYAML 1.29 → 1.30 (ADR-0074): дефект `StreamReader` при разборе `application.yml` с UTF-8 | команда |
| 2026-09-29 | OpenAPI/Swagger: аннотации, единая модель ошибок, `build-info` (ADR-0075) | команда |