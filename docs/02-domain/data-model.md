# Модель данных (GeoJSON)

Источник: `source/Техническое  приложение ЛЦТ v2.docx`, разделы 1 и 7, с
уточнениями `source/Разъяснения по вопросам ЛЦТ.docx`. Формат —
`FeatureCollection`. CRS вход/выход — WGS 84 (EPSG:4326), расчёты — EPSG:32637.

## 1. Входные объекты

Все объекты имеют `id` (строка **или** число) и `object_type`. Идентификатор
используется как значение связи и не интерпретируется по формату.

| `object_type` | Геометрия | Обязательные атрибуты | Назначение |
|---------------|-----------|-----------------------|------------|
| `source` | Point | `id`, `object_type` (+ необязательное `name`) | Источник тепла |
| `heat_network` | LineString | `id`, `object_type`, `diameter` | Существующий участок сети |
| `heat_chamber` | Point | `id`, `object_type` | Существующая тепловая камера |
| `oks_connection_point` | Point | `id`, `object_type`, `flow_tph` | Точка подключения ОКС, т/ч |
| `restriction` | LineString / MultiLineString / Polygon / MultiPolygon | `id`, `object_type`, `restriction_type` | Пространственное ограничение |

- Каждая `oks_connection_point` — самостоятельная цель подключения со своим
  `flow_tph`; связь с полигоном ОКС отдельным ID не задаётся.
- Полигоны ОКС передаются как `restriction` с `restriction_type = oks`.
- Обязательные к поддержке `restriction_type` — из таблицы 2 ТП; прочие
  допустимы как расширение.
- Необязательные/устаревшие поля (`oks_future`, `oks_existing`,
  `upstream_object_id`, `flow_tph` у `heat_network`, `diameter` у
  `heat_chamber`) игнорируются и не считаются ошибкой.

### 1.1. Связи

- Отдельные ссылочные поля между входными объектами не требуются.
- Место присоединения выбирается геометрически на `heat_network`/`heat_chamber`.

## 2. Выходные объекты

Один `FeatureCollection` на режим; варианты различаются `variant_id`. Для
каждого типа — только его собственные атрибуты. Дополнительные `properties`
допускаются и при проверке игнорируются.

Типы вывода: `heat_network`, `heat_chamber`, `technical_node`, `variant_summary`.
Объектов `tie_in` и `*_reconstruction` в выводе **нет** (ТП v2).

### 2.1. Новый участок тепловой сети

`object_type = heat_network`, геометрия LineString.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string/number | ID участка |
| `object_type` | string | `heat_network` |
| `variant_id` | string/number | ID варианта |
| `start_node_id` | string/number | Узел в начале (совпадает с началом LineString) |
| `end_node_id` | string/number | Узел в конце (совпадает с концом LineString) |
| `flow_tph` | number | Расход участка, т/ч |
| `diameter` | integer | Ду, мм |
| `length` | number | Длина по горизонтальной проекции, м |
| `laying_method` | string | `base` или `special` |
| `depth_start` | number/null | Глубина в начале; в 2D — `null` |
| `depth_end` | number/null | Глубина в конце; в 2D — `null` |
| `cost` | number | Стоимость участка, руб. |

Допустимые узлы для `start_node_id`/`end_node_id`: `oks_connection_point`,
существующая или новая `heat_chamber`, `technical_node`. Направление записи
координат не задаёт направление теплоносителя.

### 2.2. Новая тепловая камера

`object_type = heat_chamber`, геометрия Point.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string/number | ID камеры |
| `object_type` | string | `heat_chamber` |
| `variant_id` | string/number | ID варианта |
| `diameter` | integer | Наибольший Ду примыкающих участков |
| `cost` | number | Стоимость новой камеры (включает присоединение) |

Экземпляры существующих камер как отдельные объекты не выводятся; они
используются только как узлы ссылок в `start_node_id`/`end_node_id`.

### 2.3. Технический узел

`object_type = technical_node`, геометрия Point. Дополнительных обязательных
атрибутов нет; отдельной стоимости не имеет.

### 2.4. Сводка по варианту

`object_type = variant_summary`, `geometry = null`. Ровно одна запись на вариант.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string/number | ID сводной записи |
| `object_type` | string | `variant_summary` |
| `variant_id` | string/number | ID варианта |
| `rank` | integer | 1 — лучший |
| `construction_cost` | number | Стоимость строительства (участки + камеры + врезки) |
| `chamber_construction_cost` | number | Стоимость новых камер (входит в `construction_cost`) |
| `existing_chamber_tie_in_count` | integer | Число врезок в существующие камеры |
| `existing_chamber_tie_in_cost` | number | Стоимость врезок в существующие камеры |
| `unconnected_penalty` | number | Штраф за неподключённые точки |
| `calculated_cost` | number | `construction_cost + unconnected_penalty` |
| `new_network_length` | number | Суммарная длина новых участков, м |
| `score` | number | Показатель `S` |
| `unconnected_oks_ids` | array[string/number] | ID неподключённых точек (тип сохранён) |

## 3. Пример (сокращённый, ТП 7.3)

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "properties": {
        "id": "v1_net_1", "object_type": "heat_network", "variant_id": "v1",
        "start_node_id": "input_chamber_1", "end_node_id": "input_oks_1",
        "flow_tph": 20.0, "diameter": 100, "length": 100.0,
        "laying_method": "base", "depth_start": null, "depth_end": null,
        "cost": 8974800
      },
      "geometry": {"type": "LineString", "coordinates": [[37.6, 55.75], [37.599967825, 55.750898263]]}
    },
    {
      "type": "Feature",
      "properties": {
        "id": "v1_summary", "object_type": "variant_summary", "variant_id": "v1",
        "rank": 1, "construction_cost": 13974800, "chamber_construction_cost": 0,
        "existing_chamber_tie_in_count": 1, "existing_chamber_tie_in_cost": 5000000,
        "unconnected_penalty": 0, "calculated_cost": 13974800,
        "new_network_length": 100.0, "score": 0.6913, "unconnected_oks_ids": []
      },
      "geometry": null
    }
  ]
}
```

## 3.1. Промежуточные этапы (ADR-0036, диагностика)

Формат визуализации, **не** часть конкурсной поставки; активируется
`POST /runs?trace=true` и складывается в `data/runs/<id>/stages/`.

- `manifest.json` — упорядоченный список этапов:
  `{ id, title, kind, format, available }`; вкладка `trees` дополнительно
  содержит `passes` (номера проходов) и `bestPass`.
- `<этап>.geojson` — `FeatureCollection` в WGS84: `network`, `obstacles`,
  `special`, `exits`, `trees-<проход>`, `refine`, `relink`. Каждый объект имеет
  `object_type` (`network_segment`, `heat_chamber`, `source`, `obstacle`,
  `special_zone`, `exit_target`, `exit_tail`, `tree_cell`, `forest_edge`,
  `forest_node`) и вспомогательные атрибуты (`id`, `diameter_mm`, `flow_tph`,
  `k_special`, `blocked`, `pass` и т. п.).
- `grid.json` — растровая маска сетки: `originX/Y`, `cellM`, `width/height`,
  `imageWidth/imageHeight`, `imageCellM`, `downscaled`, base64-битсеты
  `blocked`/`reachable` (строка 0 — север, младший бит вперёд),
  `boundsWgs84` (углы TL, TR, BR, BL), `sources`, `terminalCells`.

## 4. Правила формирования вывода

- Один файл `FeatureCollection` на режим, все варианты вместе.
- Для каждого варианта — ровно одна `variant_summary` с `geometry = null`.
- Свойства только своего типа.
- `start_node_id`/`end_node_id` совпадают с геометрическими концами LineString.
- В обязательной 2D-задаче `depth_start`/`depth_end` = `null`.
- Расстояния округляются в большую сторону при незначительном отклонении
  (4,999 → 5 м) (допущение).
- Тип идентификаторов в `unconnected_oks_ids` сохраняется как во входных данных.

## 5. История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-16 | Первоначальная версия (ТП 2, 10) | команда |
| 2026-09-16 | Уточнения по протоколу встречи | команда |
| 2026-09-19 | Ревизия по ТП v2: базовый вход без `oks_future`/`oks_existing`, ОКС как `restriction`; ID string/number; вывод только 4 типов; новая сводка `existing_chamber_tie_in_*`; без `tie_in` и реконструкции; доп. свойства допустимы | команда |
| 2026-09-22 | ADR-0036: добавлен формат промежуточных этапов (manifest, stage GeoJSON, растровая маска сетки) | команда |
