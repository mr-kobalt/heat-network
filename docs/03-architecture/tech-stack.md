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

Управляется `devenv.nix` / `devenv.yaml` / `devenv.lock`:

- `languages.java` с `pkgs.jdk11`;
- `languages.maven`;
- `services.postgres` с PostGIS, БД `heating`;
- пакеты `git`, `jq`, `curl`, `unzip`;
- скрипты `build`, `test`, `run`, `verify`;
- `devenv test` — быстрый smoke (Java 11, Maven, PostgreSQL env).

Команды:

```bash
devenv up          # поднять PostgreSQL (сервис)
devenv shell       # оболочка с toolchain
build | test | run | verify
```

## 5. Профили Spring

| Профиль | Назначение |
|---------|-----------|
| по умолчанию (`application.yml`) | локально; datasource из `PGHOST/PGPORT/PGUSER` |
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

## 8. История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-16 | Первоначальная версия | команда |
| 2026-09-16 | Обновлено по протоколу встречи 16.09.2026: Spring Data, офлайн, единственный экземпляр, без UI, PostGIS подтверждён, скрипты деплоя | команда |
