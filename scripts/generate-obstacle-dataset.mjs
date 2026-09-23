#!/usr/bin/env node
// Генерация тестового набора с пространственными ограничениями из OpenStreetMap
// (эпик E29). Только реальные OSM-объекты, без синтетики.
//
// Набор = копия базового набора (source/Датасет скорректированный.geojson) плюс
// restriction-объекты, извлечённые из OSM (Overpass API). Геометрия обрезается
// по bbox базового набора, чтобы результат не выходил за границы имеющихся данных.
// Рядом кладётся манифест генерации (.generation.md и .generation.json).
//
// Запуск:
//   node scripts/generate-obstacle-dataset.mjs
//   node scripts/generate-obstacle-dataset.mjs --endpoint <url> --no-clip
//
// Сеть нужна только на этапе генерации; сервис остаётся полностью офлайн (NFR-12).

import { readFile, writeFile, mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';

const ROOT = resolve(new URL('..', import.meta.url).pathname);
const BASE = resolve(ROOT, 'source/Датасет скорректированный.geojson');
const OUT = resolve(ROOT, 'source/Датасет с препятствиями OSM.geojson');

const DEFAULT_ENDPOINTS = [
  'https://overpass.private.coffee/api/interpreter',
  'https://overpass-api.de/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter',
];

// Допустимые для проезда классы дорог (restriction_type=road).
// `service` исключён по замечанию ревью (E29-09).
const DRIVABLE_HIGHWAY = new Set([
  'motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'unclassified',
  'residential', 'living_street', 'road',
  'motorway_link', 'trunk_link', 'primary_link', 'secondary_link', 'tertiary_link',
]);
const RAIL_TYPES = new Set(['rail', 'light_rail', 'subway', 'narrow_gauge', 'monorail']);
const SOCIAL_AMENITIES = new Set(['school', 'hospital', 'kindergarten', 'university', 'college']);
const AREA_TYPES = new Set(['water', 'park', 'social_area', 'prohibited_site']);

function parseArgs(argv) {
  const args = { margin: 0.0, endpoint: null, keepRaw: false, clip: true };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--margin') {
      args.margin = Number(argv[++i]);
    } else if (arg === '--endpoint') {
      args.endpoint = argv[++i];
    } else if (arg === '--keep-raw') {
      args.keepRaw = true;
    } else if (arg === '--no-clip') {
      args.clip = false;
    } else {
      throw new Error(`Неизвестный аргумент: ${arg}`);
    }
  }
  return args;
}

function bboxOf(features) {
  const box = [Infinity, Infinity, -Infinity, -Infinity];
  const visit = (c) => {
    if (typeof c[0] === 'number') {
      box[0] = Math.min(box[0], c[0]);
      box[1] = Math.min(box[1], c[1]);
      box[2] = Math.max(box[2], c[0]);
      box[3] = Math.max(box[3], c[1]);
    } else {
      for (const child of c) visit(child);
    }
  };
  for (const feature of features) visit(feature.geometry.coordinates);
  return box;
}

function overpassQuery(bbox) {
  const [w, s, e, n] = bbox;
  const area = `${s},${w},${n},${e}`;
  return `[out:json][timeout:120];
(
  way["highway"](${area});
  way["railway"](${area});
  way["man_made"="pipeline"](${area});
  way["power"](${area});
  way["natural"="water"](${area});
  way["waterway"](${area});
  way["leisure"="park"](${area});
  way["amenity"](${area});
  way["landuse"="military"](${area});
  way["military"](${area});
);
out geom;`;
}

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

async function fetchOverpass(query, endpoints) {
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    for (const endpoint of endpoints) {
      try {
        process.stderr.write(`[E29] Overpass (попытка ${attempt + 1}): ${endpoint}\n`);
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 150000);
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'User-Agent': 'lct2026-dataset-gen/1.0' },
          body: new URLSearchParams({ data: query }),
          signal: controller.signal,
        }).finally(() => clearTimeout(timer));
        if (!response.ok) {
          lastError = new Error(`${endpoint}: HTTP ${response.status}`);
          process.stderr.write(`[E29] ${lastError.message}\n`);
          continue;
        }
        return { endpoint, body: await response.json() };
      } catch (error) {
        lastError = new Error(`${endpoint}: ${error.message}`);
        process.stderr.write(`[E29] ${lastError.message}\n`);
      }
    }
    if (attempt < 2) await sleep(15000 * (attempt + 1));
  }
  throw lastError ?? new Error('Overpass недоступен');
}

function parseVoltage(value) {
  if (!value) return null;
  const match = String(value).match(/(\d+)/);
  return match ? Number(match[1]) : null;
}

/** Маппинг OSM-тегов в restriction_type (ТП v2, таблица 2). */
function restrictionType(tags) {
  if (tags['man_made'] === 'pipeline' && tags['substance'] === 'gas') return 'gas_pipeline';
  if (tags['power'] && ['line', 'minor_line', 'cable'].includes(tags['power'])) {
    const voltage = parseVoltage(tags['voltage']);
    if (voltage == null || voltage <= 35000) return 'power_cable';
  }
  if (tags['railway'] === 'tram') return 'tram_tracks';
  if (tags['railway'] && RAIL_TYPES.has(tags['railway'])) return 'railway';
  if (tags['natural'] === 'water' || tags['water'] || tags['landuse'] === 'reservoir'
      || ['riverbank', 'dock'].includes(tags['waterway'])
      || ['river', 'stream', 'canal', 'ditch'].includes(tags['waterway'])) return 'water';
  if (tags['leisure'] === 'park') return 'park';
  if (tags['amenity'] && SOCIAL_AMENITIES.has(tags['amenity'])) return 'social_area';
  if (tags['landuse'] === 'military' || tags['military']) return 'prohibited_site';
  if (tags['highway'] && DRIVABLE_HIGHWAY.has(tags['highway'])) return 'road';
  return null;
}

// --- Клип по прямоугольнику в градусах (данные локальны, планарно допустимо) ---

const EPS = 1e-12;

function clipSegment(a, b, box) {
  const [xmin, ymin, xmax, ymax] = box;
  let t0 = 0;
  let t1 = 1;
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const p = [-dx, dx, -dy, dy];
  const q = [a[0] - xmin, xmax - a[0], a[1] - ymin, ymax - a[1]];
  for (let i = 0; i < 4; i++) {
    if (Math.abs(p[i]) < EPS) {
      if (q[i] < 0) return null;
      continue;
    }
    const r = q[i] / p[i];
    if (p[i] < 0) {
      if (r > t1) return null;
      if (r > t0) t0 = r;
    } else {
      if (r < t0) return null;
      if (r < t1) t1 = r;
    }
  }
  return [
    [a[0] + t0 * dx, a[1] + t0 * dy],
    [a[0] + t1 * dx, a[1] + t1 * dy],
  ];
}

function samePoint(a, b) {
  return Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
}

/** Обрезка ломаной по bbox → список частей (массивов координат). */
function clipLine(coordinates, box) {
  const parts = [];
  let current = [];
  const flush = () => {
    if (current.length >= 2) parts.push(current);
    current = [];
  };
  for (let i = 0; i < coordinates.length - 1; i++) {
    const segment = clipSegment(coordinates[i], coordinates[i + 1], box);
    if (!segment) {
      flush();
      continue;
    }
    if (current.length === 0) {
      current.push(segment[0]);
    } else if (!samePoint(current[current.length - 1], segment[0])) {
      flush();
      current.push(segment[0]);
    }
    current.push(segment[1]);
  }
  flush();
  return parts;
}

/** Sutherland–Hodgman: обрезка кольца по выпуклому прямоугольнику. */
function clipRing(ring, box) {
  const [xmin, ymin, xmax, ymax] = box;
  const boundaries = [
    { inside: (p) => p[0] >= xmin, intersect: (a, b) => vertical(a, b, xmin) },
    { inside: (p) => p[0] <= xmax, intersect: (a, b) => vertical(a, b, xmax) },
    { inside: (p) => p[1] >= ymin, intersect: (a, b) => horizontal(a, b, ymin) },
    { inside: (p) => p[1] <= ymax, intersect: (a, b) => horizontal(a, b, ymax) },
  ];
  let output = ring.slice(0, ring.length - 1); // без замыкающей точки
  for (const { inside, intersect } of boundaries) {
    const input = output;
    output = [];
    for (let i = 0; i < input.length; i++) {
      const current = input[i];
      const previous = input[(i + input.length - 1) % input.length];
      const currentInside = inside(current);
      const previousInside = inside(previous);
      if (currentInside) {
        if (!previousInside) output.push(intersect(previous, current));
        output.push(current);
      } else if (previousInside) {
        output.push(intersect(previous, current));
      }
    }
    if (output.length === 0) return [];
  }
  output.push(output[0]);
  return output;
}

function vertical(a, b, x) {
  const t = (x - a[0]) / (b[0] - a[0]);
  return [x, a[1] + t * (b[1] - a[1])];
}

function horizontal(a, b, y) {
  const t = (y - a[1]) / (b[1] - a[1]);
  return [a[0] + t * (b[0] - a[0]), y];
}

function clipPolygon(coordinates, box) {
  const exterior = clipRing(coordinates[0], box);
  if (exterior.length < 4) return null;
  const holes = coordinates.slice(1)
      .map((ring) => clipRing(ring, box))
      .filter((ring) => ring.length >= 4);
  return { type: 'Polygon', coordinates: [exterior, ...holes] };
}

/** Клип геометрии по bbox; возвращает геометрию или null. */
function clipGeometry(geometry, box) {
  if (geometry.type === 'LineString') {
    const parts = clipLine(geometry.coordinates, box);
    if (parts.length === 0) return null;
    return parts.length === 1 ? { type: 'LineString', coordinates: parts[0] }
      : { type: 'MultiLineString', coordinates: parts };
  }
  if (geometry.type === 'MultiLineString') {
    const parts = geometry.coordinates.flatMap((line) => clipLine(line, box));
    if (parts.length === 0) return null;
    return parts.length === 1 ? { type: 'LineString', coordinates: parts[0] }
      : { type: 'MultiLineString', coordinates: parts };
  }
  if (geometry.type === 'Polygon') {
    return clipPolygon(geometry.coordinates, box);
  }
  if (geometry.type === 'MultiPolygon') {
    const polygons = geometry.coordinates
        .map((polygon) => clipPolygon(polygon, box))
        .filter((polygon) => polygon !== null);
    if (polygons.length === 0) return null;
    return polygons.length === 1 ? polygons[0] : { type: 'MultiPolygon', coordinates: polygons.map((p) => p.coordinates) };
  }
  return null;
}

const LANE_WIDTH_M = 3.0;
const MAX_ROAD_WIDTH_M = 6.0;

/** Полуширина дороги: lanes × 3.0 м, но не шире 6 м (по умолчанию одна полоса). */
function roadHalfWidth(tags) {
  const lanes = Number.parseInt(tags['lanes'], 10);
  const count = Number.isFinite(lanes) && lanes > 0 ? lanes : 1;
  const width = Math.min(count * LANE_WIDTH_M, MAX_ROAD_WIDTH_M);
  return width / 2.0;
}

/**
 * Буфер ломаной (полигон ширины 2·halfWidth) с простыми стыками по усреднённой
 * нормали. Достаточно для тестового набора; валидность при необходимости
 * восстанавливает JTS в сервисе.
 */
function bufferRing(coordinates, halfWidth) {
  // Смещение считается в локальной метрической системе (метры), затем
  // возвращается в градусы — иначе halfWidth в метрах применялся к градусам.
  const lon0 = coordinates[0][0];
  const lat0 = coordinates[0][1];
  const mLon = 111320.0 * Math.cos((lat0 * Math.PI) / 180.0);
  const mLat = 110540.0;
  const xy = coordinates.map(([lon, lat]) => [(lon - lon0) * mLon, (lat - lat0) * mLat]);
  const n = xy.length;
  const left = [];
  const right = [];
  for (let i = 0; i < n; i++) {
    let nx;
    let ny;
    let miter = 1.0;
    if (i === 0) {
      [nx, ny] = normal(xy[0], xy[1]);
    } else if (i === n - 1) {
      [nx, ny] = normal(xy[n - 2], xy[n - 1]);
    } else {
      const [n1x, n1y] = normal(xy[i - 1], xy[i]);
      const [n2x, n2y] = normal(xy[i], xy[i + 1]);
      let ax = n1x + n2x;
      let ay = n1y + n2y;
      const norm = Math.hypot(ax, ay);
      if (norm < 1e-9) {
        ax = n1x;
        ay = n1y;
      } else {
        ax /= norm;
        ay /= norm;
      }
      const dot = Math.abs(ax * n1x + ay * n1y);
      miter = dot > 1e-6 ? Math.min(3.0, 1.0 / dot) : 1.0;
      nx = ax;
      ny = ay;
    }
    const offset = halfWidth * miter;
    left.push([xy[i][0] + nx * offset, xy[i][1] + ny * offset]);
    right.push([xy[i][0] - nx * offset, xy[i][1] - ny * offset]);
  }
  const ring = left.concat(right.reverse());
  ring.push(ring[0]);
  return ring.map(([x, y]) => [lon0 + x / mLon, lat0 + y / mLat]);
}

function normal(a, b) {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const norm = Math.hypot(dx, dy);
  if (norm < 1e-12) {
    return [0, 0];
  }
  return [-dy / norm, dx / norm];
}

function toGeometry(way, type) {
  const coordinates = way.geometry.map((p) => [p.lon, p.lat]);
  const closed = coordinates.length >= 4
      && samePoint(coordinates[0], coordinates[coordinates.length - 1]);
  if (type === 'road') {
    // E31: дороги — полигоны (OSM-площадь или буфер по lanes × 3.0 м).
    if (closed && way.tags['area'] === 'yes') {
      return { type: 'Polygon', coordinates: [coordinates] };
    }
    return { type: 'Polygon', coordinates: [bufferRing(coordinates, roadHalfWidth(way.tags))] };
  }
  if (AREA_TYPES.has(type) && closed) {
    return { type: 'Polygon', coordinates: [coordinates] };
  }
  return { type: 'LineString', coordinates };
}

function buildFeatures(osm, box, clip) {
  const features = [];
  const counts = {};
  const skipped = {};
  let clipped = 0;
  for (const element of osm.elements ?? []) {
    if (element.type !== 'way' || !element.geometry || !element.tags) continue;
    const type = restrictionType(element.tags);
    if (!type) continue;
    let geometry = toGeometry(element, type);
    if (clip) {
      const clippedGeometry = clipGeometry(geometry, box);
      if (!clippedGeometry) {
        skipped[type] = (skipped[type] ?? 0) + 1;
        continue;
      }
      if (JSON.stringify(clippedGeometry) !== JSON.stringify(geometry)) clipped++;
      geometry = clippedGeometry;
    }
    counts[type] = (counts[type] ?? 0) + 1;
    features.push({
      type: 'Feature',
      properties: {
        id: `osm_${element.id}`,
        object_type: 'restriction',
        restriction_type: type,
        generated: true,
        source: 'openstreetmap_overpass',
        osm_id: element.id,
        osm_tags: element.tags,
      },
      geometry,
    });
  }
  return { features, counts, skipped, clipped };
}

/** Санитарная проверка сгенерированных геометрий (ловит ошибки единиц измерения). */
function validateGenerated(features, bbox) {
  const tol = 1e-3;
  const bboxArea = Math.max(1e-9, (bbox[2] - bbox[0]) * (bbox[3] - bbox[1]));
  let roads = 0;
  for (const feature of features) {
    const coords = feature.geometry.coordinates;
    const box = [Infinity, Infinity, -Infinity, -Infinity];
    const visit = (node) => {
      if (typeof node[0] === 'number') {
        if (!Number.isFinite(node[0]) || !Number.isFinite(node[1])) {
          throw new Error(`Недопустимая координата в ${feature.properties.id}`);
        }
        box[0] = Math.min(box[0], node[0]);
        box[1] = Math.min(box[1], node[1]);
        box[2] = Math.max(box[2], node[0]);
        box[3] = Math.max(box[3], node[1]);
      } else {
        for (const child of node) visit(child);
      }
    };
    visit(coords);
    if (box[0] < bbox[0] - tol || box[1] < bbox[1] - tol
        || box[2] > bbox[2] + tol || box[3] > bbox[3] + tol) {
      throw new Error(`Геометрия ${feature.properties.id} выходит за bbox`);
    }
    if (feature.properties.restriction_type === 'road') {
      roads++;
      const area = (box[2] - box[0]) * (box[3] - box[1]);
      if (box[2] - box[0] > 0.05 || box[3] - box[1] > 0.05 || area > 0.05 * bboxArea) {
        throw new Error(`Дорожный полигон ${feature.properties.id} аномально велик`);
      }
    }
  }
  process.stderr.write(`[E29] Валидация: features=${features.length} roads=${roads} OK\n`);
}

function renderManifest({ basePath, outPath, bbox, queryBbox, margin, clip, endpoint, query, counts, skipped, clipped, baseCount, date }) {
  const tableTypes = ['park', 'social_area', 'prohibited_site', 'water', 'railway',
    'road', 'tram_tracks', 'gas_pipeline', 'power_cable'];
  const found = tableTypes.filter((type) => counts[type]);
  const missing = tableTypes.filter((type) => !counts[type]);
  const labels = {
    park: 'Парк (park)', social_area: 'Соц. объект (social_area)',
    prohibited_site: 'Запрещённая территория (prohibited_site)', water: 'Водный объект (water)',
    railway: 'Железная дорога (railway)', road: 'Автодорога (road)',
    tram_tracks: 'Трамвайные пути (tram_tracks)', gas_pipeline: 'Газопровод (gas_pipeline)',
    power_cable: 'Силовой кабель ≤35 кВ (power_cable)',
  };
  const rows = tableTypes
      .map((type) => `| ${type} | ${labels[type]} | ${counts[type] ?? 0} |`)
      .join('\n');
  return `# Манифест генерации набора с препятствиями (E29)

- **Сгенерирован:** ${date}
- **Базовый набор:** \`${basePath}\` (${baseCount} features)
- **Результат:** \`${outPath}\`
- **Источник геометрии:** OpenStreetMap через Overpass API (\`${endpoint}\`)
- **Bbox базового набора (WGS84):** ${bbox.map((v) => v.toFixed(6)).join(', ')}
- **Bbox Overpass-запроса:** ${queryBbox.map((v) => v.toFixed(6)).join(', ')} (запас ${margin}°)
- **Фильтр:** \`highway=service\` исключён (E29-09)
- **Клип по bbox базового набора:** ${clip ? 'да' : 'нет'} (обрезано объектов: ${clipped})
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
${rows}

- **Найдены типы:** ${found.join(', ') || '—'}
- **Не найдены в OSM (пробел покрытия):** ${missing.join(', ') || '—'}
${Object.keys(skipped).length ? `- **Пропущено (вне bbox / вырожденная геометрия):** ${JSON.stringify(skipped)}` : ''}

## Overpass-запрос

\`\`\`
${query}
\`\`\`

## Как воспроизвести

\`\`\`bash
node scripts/generate-obstacle-dataset.mjs
\`\`\`
`;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const base = JSON.parse(await readFile(BASE, 'utf8'));
  const bbox = bboxOf(base.features);
  const queryBbox = [
    bbox[0] - args.margin, bbox[1] - args.margin,
    bbox[2] + args.margin, bbox[3] + args.margin,
  ];
  const query = overpassQuery(queryBbox);
  const endpoints = args.endpoint ? [args.endpoint] : DEFAULT_ENDPOINTS;
  const { endpoint, body: osm } = await fetchOverpass(query, endpoints);
  const { features, counts, skipped, clipped } = buildFeatures(osm, bbox, args.clip);
  validateGenerated(features, bbox);

  const merged = {
    type: 'FeatureCollection',
    name: 'all_objects_with_osm_obstacles',
    crs: base.crs ?? { type: 'name', properties: { name: 'urn:ogc:def:crs:OGC:1.3:CRS84' } },
    features: [...base.features, ...features],
  };
  await writeFile(OUT, JSON.stringify(merged), 'utf8');

  const date = new Date().toISOString();
  const manifest = renderManifest({
    basePath: BASE.replace(`${ROOT}/`, ''), outPath: OUT.replace(`${ROOT}/`, ''),
    bbox, queryBbox, margin: args.margin, clip: args.clip, endpoint, query, counts, skipped,
    clipped, baseCount: base.features.length, date,
  });
  await writeFile(OUT.replace(/\.geojson$/, '.generation.md'), manifest, 'utf8');
  await writeFile(OUT.replace(/\.geojson$/, '.generation.json'), JSON.stringify({
    date, source: endpoint, bbox, queryBbox, margin: args.margin, clip: args.clip,
    base: BASE, output: OUT, counts, skipped, clipped, query,
  }, null, 2), 'utf8');

  if (args.keepRaw) {
    const rawDir = resolve(ROOT, 'target/dataset-generation');
    await mkdir(rawDir, { recursive: true });
    await writeFile(`${rawDir}/overpass-${date.replace(/[:.]/g, '-')}.json`,
        JSON.stringify(osm), 'utf8');
  }

  process.stderr.write(`[E29] features добавлено: ${features.length}; обрезано: ${clipped}; типы: ${JSON.stringify(counts)}\n`);
  if (Object.keys(skipped).length) {
    process.stderr.write(`[E29] пропущено: ${JSON.stringify(skipped)}\n`);
  }
}

main().catch((error) => {
  process.stderr.write(`[E29] Ошибка: ${error.stack ?? error.message}\n`);
  process.exit(1);
});
