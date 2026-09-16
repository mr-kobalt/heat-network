# Модель данных (GeoJSON)

Источник: `source/Техническое приложение.docx`, разделы 2 и 10.
Формат — `FeatureCollection`. CRS вход/выход — WGS 84 (EPSG:4326),
расчёты — EPSG:32637.

## 1. Входные объекты

Для каждого объекта обязательны `id` (строка, уникальна в файле) и
`object_type`. Набор атрибутов зависит от типа; лишние атрибуты не требуются.

| `object_type` | Геометрия | Обязательные атрибуты | Назначение |
|---------------|-----------|-----------------------|------------|
| `source` | Point | `id`, `object_type` (+ необязательное `name`) | Источник тепла |
| `heat_network` | LineString | `diameter`, `flow_tph`, `upstream_object_id` | Существующий участок сети |
| `heat_chamber` | Point | `diameter`, `upstream_object_id` | Существующая камера |
| `oks_future` | Polygon / MultiPolygon | `flow_tph`, `heat_load` | Перспективный ОКС |
| `oks_connection_point` | Point | `oks_id` | Точка подключения ОКС (ссылается на `oks_future.id`) |
| `oks_existing` | Polygon / MultiPolygon | — | Существующий ОКС |
| `restriction` | по типу ограничения | `restriction_type` | Пространственное ограничение |

### 1.1. Атрибуты (полная таблица из ТП 2.2)

| Атрибут | Тип | Для каких объектов | Описание |
|---------|-----|--------------------|----------|
| `id` | string | все | Уникальный идентификатор |
| `object_type` | string | все | Тип объекта |
| `diameter` | integer | `heat_network`, `heat_chamber` | Для участка — текущий Ду; для камеры — макс. Ду примыкающих участков, мм |
| `flow_tph` | number | `heat_network`, `oks_future` | Для сети — текущий расход; для ОКС — расход для расчёта, т/ч |
| `heat_load` | number | `oks_future` | Справочная тепловая нагрузка, Гкал/ч |
| `oks_id` | string | `oks_connection_point` | ID объекта `oks_future` |
| `restriction_type` | string | `restriction` | Тип ограничения (см. таблицу 5.1) |
| `upstream_object_id` | string | `heat_network`, `heat_chamber` | ID следующего объекта к источнику (`heat_network`/`heat_chamber`/`source`) |

> **Важно (по протоколу встречи).** Вместо подключения ОКС в наборе даётся
> **конкретная точка подключения**, а геометрия ОКС на маршрут **не влияет**.
> Поэтому:
> - расчёт привязывается к `oks_connection_point`; `oks_future` может
>   отсутствовать или не использоваться для геометрии;
> - `flow_tph` может передаваться на точке подключения (как в образце), а не
>   на `oks_future`; `oks_id` может отсутствовать;
> - объектов — тысячи в масштабе города, реальный объём — до 2–3 ГБ.
>
> Реализация остаётся совместимой с ТП: если атрибуты есть — используются,
> если нет — берутся из точки подключения. См.
> [05-data](../05-data/sample-dataset-analysis.md) и
> [calculation-rules.md](calculation-rules.md).
>
> Невалидная геометрия на входе → диагностическая ошибка (протокол).

### 1.2. Связи

- `oks_connection_point.oks_id` → `oks_future.id`.
- `heat_network.upstream_object_id` / `heat_chamber.upstream_object_id` →
  следующий объект к источнику; цепочка заканчивается на `source`.
- Геометрия существующей сети — `LineString`, камеры и источник — точки.

## 2. Выходные объекты

Все объекты всех вариантов — в одном массиве `features`, различаются
`variant_id`. Точки подключения перспективных ОКС повторно не передаются.
Для каждого типа — только его собственные атрибуты (без `null`-полей чужих типов).

### 2.1. Новый участок тепловой сети

`object_type = heat_network`, геометрия LineString.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string | ID участка |
| `variant_id` | string | ID варианта |
| `start_node_id` | string | Начальный узел (врезка/камера/технический узел/точка подключения ОКС) |
| `end_node_id` | string | Конечный узел (второй конец) |
| `flow_tph` | number | Расход участка, т/ч |
| `diameter` | integer | Ду, мм |
| `length` | number | Длина, м |
| `laying_method` | string | `base` или `special` |
| `depth_start` | number / null | Глубина в начале, м (для 2D — `null`) |
| `depth_end` | number / null | Глубина в конце, м (для 2D — `null`) |
| `cost` | number | Стоимость, руб. |

### 2.2. Точка врезки

`object_type = tie_in`, геометрия Point.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string | ID точки врезки |
| `variant_id` | string | ID варианта |
| `existing_object_id` | string | ID существующего участка или камеры |
| `existing_object_type` | string | `heat_network` или `heat_chamber` |
| `existing_diameter` | integer | Ду существующего объекта (для камеры — входной максимум) |
| `required_diameter` | integer | Требуемый Ду новой сети в точке врезки |
| `cost` | number | Стоимость врезки, руб. |

### 2.3. Реконструируемая часть существующей сети

`object_type = heat_network_reconstruction`, геометрия LineString
(фактическая часть, где нужно увеличить Ду).

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string | ID объекта реконструкции |
| `variant_id` | string | ID варианта |
| `existing_object_id` | string | ID входного объекта `heat_network` |
| `existing_flow_tph` | number | Текущий расход, т/ч |
| `added_flow_tph` | number | Добавленный расход, т/ч |
| `calculated_flow_tph` | number | Итоговый расход, т/ч |
| `existing_diameter` | integer | Текущий Ду, мм |
| `required_diameter` | integer | Требуемый Ду, мм |
| `length` | number | Длина реконструируемой части, м |
| `cost` | number | Стоимость, руб. |

### 2.4. Новая тепловая камера

`object_type = heat_chamber`, геометрия Point.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string | ID камеры |
| `variant_id` | string | ID варианта |
| `diameter` | integer | Макс. Ду примыкающих участков |
| `cost` | number | Стоимость строительства, руб. |

### 2.5. Реконструируемая существующая камера

`object_type = heat_chamber_reconstruction`, геометрия Point.
Передаётся только для используемой для врезки камеры, если нужна реконструкция.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string | ID объекта реконструкции |
| `variant_id` | string | ID варианта |
| `existing_object_id` | string | ID входного `heat_chamber` |
| `existing_diameter` | integer | Входной Ду камеры, мм |
| `required_diameter` | integer | Макс. Ду примыкающих участков, мм |
| `cost` | number | Стоимость реконструкции, руб. |

### 2.6. Технический узел

`object_type = technical_node`, геометрия Point.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string | ID узла |
| `variant_id` | string | ID варианта |

### 2.7. Сводка по варианту

`object_type = variant_summary`, `geometry = null`. Ровно одна запись на вариант.

| Атрибут | Тип | Описание |
|---------|-----|----------|
| `id` | string | ID сводной записи |
| `variant_id` | string | ID варианта |
| `rank` | integer | 1 — лучший |
| `construction_cost` | number | Стоимость новых линейных участков |
| `chamber_construction_cost` | number | Стоимость новых камер |
| `tie_in_cost` | number | Суммарная стоимость врезок |
| `reconstruction_cost` | number | Стоимость реконструкции участков |
| `chamber_reconstruction_cost` | number | Стоимость реконструкции камер |
| `unconnected_penalty` | number | Штраф за неподключённые ОКС |
| `calculated_cost` | number | Итоговая стоимость (`C`) |
| `new_network_length` | number | Суммарная длина новых участков, м |
| `reconstruction_length` | number | Суммарная длина реконструкции, м |
| `length` | number | Общая длина работ (`L`), м |
| `score` | number | Показатель `S` |
| `unconnected_oks_ids` | array[string] | ID неподключённых ОКС |

## 3. Пример (сокращённый)

См. ТП 10.8. Иллюстрирует связь `start_node_id`/`end_node_id` между
участками, врезкой, камерой и техническим узлом.

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": {"type": "LineString", "coordinates": [[37.60, 55.75], [37.601, 55.751]]},
      "properties": {
        "id": "new_1", "object_type": "heat_network", "variant_id": "1",
        "start_node_id": "tie_1", "end_node_id": "node_1",
        "flow_tph": 80.0, "diameter": 200, "length": 145.2,
        "laying_method": "special", "depth_start": 3.0, "depth_end": 3.0,
        "cost": 27942307
      }
    },
    {
      "type": "Feature",
      "geometry": null,
      "properties": {
        "id": "summary_1", "object_type": "variant_summary", "variant_id": "1",
        "rank": 1, "calculated_cost": 51094590, "length": 220.2,
        "score": 2.091, "unconnected_oks_ids": []
      }
    }
  ]
}
```

## 4. Правила формирования вывода

- Один файл `FeatureCollection`, все варианты вместе.
- Для каждого варианта — ровно одна `variant_summary` с `geometry = null`.
- Свойства только своего типа (никаких `null`-заглушек чужих полей).
- Узлы маршрута связываются через `start_node_id`/`end_node_id`.
- В обязательной 2D-задаче `depth_start`/`depth_end` = `null`.
- Расстояния округляются в большую сторону при незначительном отклонении
  (например, 4,999 → 5 м) (протокол).
- Возможность расширения атрибутивного состава вывода на первом этапе —
  открытый вопрос NQ-02 (см. [open-questions.md](../01-project/open-questions.md)).

## 5. История изменений

| Дата | Изменение | Автор |
|------|-----------|-------|
| 2026-09-16 | Первоначальная версия (ТП 2, 10) | команда |
| 2026-09-16 | Обновлено по протоколу встречи 16.09.2026: точка подключения вместо геометрии ОКС, масштаб/объём, невалидная геометрия, округление расстояний, совместимость с ТП | команда |
