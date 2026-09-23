# Манифест генерации набора с препятствиями (E29)

- **Сгенерирован:** 2026-09-23T09:48:37.215Z
- **Базовый набор:** `source/Датасет скорректированный.geojson` (144 features)
- **Результат:** `source/Датасет с препятствиями OSM.geojson`
- **Источник геометрии:** OpenStreetMap через Overpass API (`https://overpass-api.de/api/interpreter`)
- **Bbox базового набора (WGS84):** 37.626269, 55.690915, 37.658166, 55.705547
- **Bbox Overpass-запроса:** 37.626269, 55.690915, 37.658166, 55.705547 (запас 0°)
- **Фильтр:** `highway=service` исключён (E29-09)
- **Клип по bbox базового набора:** да (обрезано объектов: 51)
- **Только реальные OSM-объекты; синтетика не добавлялась.**

> Сеть использовалась только при генерации. Сервис работает офлайн (NFR-12);
> результат зафиксирован статически в этом файле.

## Маппинг OSM → restriction_type (ТП v2, таблица 2)

| restriction_type | OSM-теги |
|------------------|----------|
| road | highway ∈ {motorway, trunk, primary, secondary, tertiary, unclassified, residential, living_street, road, *_link} (без service); **полигон** шириной lanes × 3.0 м (area=yes — как есть) |
| tram_tracks | railway=tram |
| railway | railway ∈ {rail, light_rail, subway, narrow_gauge, monorail} |
| gas_pipeline | man_made=pipeline + substance=gas |
| power_cable | power ∈ {line, minor_line, cable}, voltage ≤ 35000 или не задано |
| water | natural=water / water=* / landuse=reservoir / waterway ∈ {riverbank, dock, river, stream, canal, ditch} |
| park | leisure=park |
| social_area | amenity ∈ {school, hospital, kindergarten, university, college} |
| prohibited_site | landuse=military / military=* |

## Содержимое (добавленные restriction, после клипа)

| Тип | Назначение | Количество |
|-----|-----------|-----------:|
| park | Парк (park) | 1 |
| social_area | Соц. объект (social_area) | 5 |
| prohibited_site | Запрещённая территория (prohibited_site) | 0 |
| water | Водный объект (water) | 8 |
| railway | Железная дорога (railway) | 43 |
| road | Автодорога (road) | 226 |
| tram_tracks | Трамвайные пути (tram_tracks) | 0 |
| gas_pipeline | Газопровод (gas_pipeline) | 0 |
| power_cable | Силовой кабель ≤35 кВ (power_cable) | 0 |

- **Найдены типы:** park, social_area, water, railway, road
- **Не найдены в OSM (пробел покрытия):** prohibited_site, tram_tracks, gas_pipeline, power_cable


## Overpass-запрос

```
[out:json][timeout:120];
(
  way["highway"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["railway"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["man_made"="pipeline"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["power"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["natural"="water"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["waterway"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["leisure"="park"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["amenity"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["landuse"="military"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
  way["military"](55.690915329577294,37.6262685,55.70554663888263,37.658165733407884);
);
out geom;
```

## Как воспроизвести

```bash
node scripts/generate-obstacle-dataset.mjs
```
