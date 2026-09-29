# Лицензионная чистота компонентов

Обзор сторонних компонентов решения, их лицензий и совместимости с
проприетарной поставкой. Цель — подтвердить отсутствие обязательств, мешающих
передаче решения организаторам, и зафиксировать обязательные атрибуции.

Компонентов под **GPL/AGPL/LGPL (строгий копилефт)** не обнаружено.
Есть несколько **weak-copyleft** (EPL/MPL) — они используются как отдельные
библиотеки без модификации, что допускает проприетарное распространение при
сохранении уведомлений. Для двойных лицензий выбран permissive-вариант.

## 1. Backend (runtime)

| Компонент | Версия | Лицензия | Примечание |
|-----------|--------|----------|------------|
| Spring Boot / Spring Framework | 2.6.3 | Apache-2.0 | обязательный стек |
| springdoc-openapi / swagger-ui, swagger-core | 1.7.0 | Apache-2.0 | Swagger UI |
| JTS Topology Suite (`jts-core`) | 1.19.0 | **EPL-2.0 OR EDL-1.0** | выбираем **EDL-1.0** (BSD-подобная) |
| Proj4J | 1.3.0 | Apache-2.0 | CRS 4326↔32637 |
| PostgreSQL JDBC Driver | 42.3.1 | BSD-2-Clause | |
| SnakeYAML | 1.30 | Apache-2.0 | |
| Logback (logging) | 1.2.10 | **EPL-1.0 OR LGPL-2.1** | выбираем **EPL-1.0** |
| Java Native Access (JNA) | 5.8.0 | Apache-2.0 OR LGPL-2.1 | выбираем **Apache-2.0** |
| Jakarta Annotations/Persistence/Transaction API | 1.x/2.x | EPL-2.0 OR GPL2+CPE | выбираем **EPL-2.0** |
| Project Lombok | 1.18.22 | MIT | compile-time |

## 2. Frontend (опциональный визуализатор, вне оцениваемой поставки)

| Компонент | Версия | Лицензия |
|-----------|--------|----------|
| React / React DOM | 18.3 | MIT |
| Mantine | 7.15 | MIT |
| MapLibre GL JS | 4.7 | BSD-3-Clause |
| PMTiles | 3.2 | BSD-3-Clause |
| @turf/buffer, @turf/difference, @turf/helpers | 7.4 | MIT (внутри `jsts` — EDL-1.0 OR EPL-1.0) |
| Mermaid | 12.0 | MIT (внутри `elkjs` — **EPL-2.0**) |
| DOMPurify (через mermaid) | — | MPL-2.0 OR Apache-2.0 (выбираем Apache-2.0) |
| react-markdown, remark-gfm, rehype-slug | 9.x/4.x/6.x | MIT |
| github-slugger | 2.0 | ISC |
| Zustand | 5.0 | MIT |
| TanStack Query | 5.59 | MIT |
| Tabler Icons (`@tabler/icons-react`) | 3.48 | MIT |
| @fontsource/inter (шрифт Inter) | 5.3 | **OFL-1.1** |
| @protomaps/basemaps | 5.7 | BSD-3-Clause (dev) |
| Vite / Vitest / jsdom / Testing Library | — | MIT |
| TypeScript | 5.6 | Apache-2.0 |

## 3. Данные и шрифты

- **Подложка карты**: экстракт Protomaps, данные **OpenStreetMap** — лицензия
  **ODbL 1.0**; атрибуция «© OpenStreetMap contributors, © Protomaps»
  показывается на карте и в стиле (`build-style.mjs`).
- **Глифы карты**: Noto Sans — OFL-1.1.
- **Шрифт презентации Montserrat** (`presentation-assets/fonts/`, тема
  организатора) — OFL-1.1, текст в `presentation-assets/fonts/OFL.txt`.
- Инструменты сборки презентации (`pandoc`, Chromium, `python3`) — только
  сборочные, в поставку не входят.
- Исходные датасеты конкурса — по условиям организатора (не распространяются
  как открытые данные; см. `source/`).

## 4. Тестовые зависимости (не в поставке)

JUnit 5 (EPL-2.0), Testcontainers (MIT), Mockito (MIT), AssertJ (Apache-2.0),
Spring Boot Test (Apache-2.0), PostgreSQL/PostGIS образ (PostgreSQL License/
BSD). На дистрибутив не влияют.

## 5. Weak-copyleft: оценка

| Компонент | Лицензия | Риск | Меры |
|-----------|----------|------|------|
| JTS (`jts-core`) | EPL-2.0 / EDL-1.0 | низкий | выбираем EDL-1.0; исходный код не изменён |
| @turf/jsts, jsts | EDL-1.0 / EPL | низкий | как отдельная библиотека |
| elkjs (через Mermaid) | EPL-2.0 | низкий | не входит в ядро поставки (опциональный визуализатор), не изменён |
| DOMPurify | MPL-2.0 / Apache-2.0 | низкий | выбираем Apache-2.0 |

Все weak-copyleft компоненты используются как неизменённые библиотеки и не
затрагивают исходный код сервиса. Обязательства — сохранение уведомлений
(раздел «Лицензии» в `THIRD_PARTY_NOTICES.md`).

Двойное лицензирование с LGPL/GPL-веткой (Logback, JNA, Jakarta API) не
создаёт обязательств: выбраны EPL-1.0/EPL-2.0/Apache-2.0 варианты, LGPL/GPL не
применяются. Перечень подтверждён сгенерированным отчётом
(`target/licenses/backend.txt`, 120 зависимостей).

## 6. Проверка и воспроизведение

```bash
make licenses          # backend: license-maven-plugin; frontend: pnpm licenses list
```

Артефакты — в `target/licenses/` (`backend.txt`, `frontend.txt`). Сводные
уведомления — [THIRD_PARTY_NOTICES.md](../../THIRD_PARTY_NOTICES.md).

Проверено: `pnpm --dir frontend licenses list` — 470+ пакетов, из них
MIT/ISC/BSD/Apache/0BSD/Unlicense/CC0; строгий копилефт отсутствует.

## 7. История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-29 | Первоначальная версия | команда |
