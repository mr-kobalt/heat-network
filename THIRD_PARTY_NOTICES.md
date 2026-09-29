# Third-Party Notices

Сторонние компоненты, используемые сервисом (backend) и опциональным
визуализатором (`frontend/`). Собственный код проекта — проприетарный.
Полный анализ и обоснование совместимости — в
[docs/06-submission/licenses.md](docs/06-submission/licenses.md).

## Backend

- **Spring Boot / Spring Framework** — Apache-2.0 —
  https://www.apache.org/licenses/LICENSE-2.0
- **springdoc-openapi / Swagger UI / swagger-core** — Apache-2.0 —
  https://www.apache.org/licenses/LICENSE-2.0
- **JTS Topology Suite (`jts-core`)** — Eclipse Public License 2.0 (EPL-2.0)
  либо Eclipse Distribution License 1.0 (EDL-1.0); используется вариант
  EDL-1.0 (BSD-подобная) — https://www.eclipse.org/legal/epl-2.0/
- **Proj4J** — Apache-2.0 — https://www.apache.org/licenses/LICENSE-2.0
- **PostgreSQL JDBC Driver** — BSD-2-Clause —
  https://jdbc.postgresql.org/about/license.html
- **SnakeYAML** — Apache-2.0 — https://www.apache.org/licenses/LICENSE-2.0
- **Logback** — EPL-1.0 or LGPL-2.1; используется вариант EPL-1.0 —
  https://www.eclipse.org/legal/epl-v10.html
- **Java Native Access (JNA)** — Apache-2.0 or LGPL-2.1; используется
  Apache-2.0 — https://www.apache.org/licenses/LICENSE-2.0
- **Jakarta Annotations / Persistence / Transaction API** — EPL-2.0 or
  GPL-2.0-with-Classpath-Exception; используется EPL-2.0 —
  https://www.eclipse.org/legal/epl-2.0/
- **Project Lombok** — MIT — https://projectlombok.org/LICENSE

## Frontend

- **React, React DOM, Mantine, Zustand, TanStack Query, Tabler Icons,
  react-markdown, remark-gfm, rehype-slug, Mermaid, @turf/** — MIT —
  https://opensource.org/license/mit
- **MapLibre GL JS, PMTiles, @protomaps/basemaps** — BSD-3-Clause —
  https://opensource.org/license/bsd-3-clause
- **DOMPurify** — MPL-2.0 or Apache-2.0 (используется Apache-2.0) —
  https://www.apache.org/licenses/LICENSE-2.0
- **jsts / @turf/jsts** — EDL-1.0 or EPL-1.0 (используется EDL-1.0)
- **elkjs (через Mermaid)** — EPL-2.0 — https://www.eclipse.org/legal/epl-2.0/
- **github-slugger** — ISC — https://opensource.org/license/isc-license-txt
- **@fontsource/inter (шрифт Inter)** — SIL Open Font License 1.1 —
  https://openfontlicense.org
- **Montserrat (презентация, `docs/06-submission/presentation-assets/fonts`)** —
  SIL Open Font License 1.1 — https://openfontlicense.org
  (текст лицензии — `presentation-assets/fonts/OFL.txt`)
- **TypeScript** — Apache-2.0 — https://www.apache.org/licenses/LICENSE-2.0

## Данные карты

- **OpenStreetMap** — Open Database License (ODbL 1.0) —
  https://www.openstreetmap.org/copyright
- **Protomaps** (тайлы/спрайты/глифы) — https://protomaps.com
- Атрибуция «© OpenStreetMap contributors, © Protomaps» отображается на карте
  и задана в стиле подложки (`frontend/scripts/build-style.mjs`).
- **Глифы Noto Sans** — SIL Open Font License 1.1.

## Weak-copyleft

JTS, jsts, elkjs, Logback, Jakarta API (EPL/EDL) и DOMPurify (MPL/Apache)
используются как неизменённые библиотеки; выбранные permissive/EPL-варианты
лицензий и сохранение уведомлений (этот файл) выполняют их условия. Строгий
копилефт (GPL/AGPL/LGPL) не применяется.

## Инструменты сборки (не в поставке)

`pandoc`, `Chromium` и `python3` используются только при генерации презентации
и документации; в распространяемую поставку не входят.

## Воспроизведение

```bash
make licenses
# → target/licenses/backend.txt, target/licenses/frontend.txt
```

## История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-29 | Первоначальная версия | команда |
