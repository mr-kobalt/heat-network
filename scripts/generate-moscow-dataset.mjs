#!/usr/bin/env node
// E8-15a: генерация крупного нагрузочного набора (до ~3 ГБ) из реальной карты
// Москвы (OpenStreetMap). Набор = ОКС (здания) + точки подключения, тепловая сеть
// (OSM-трубопроводы где есть + синтез), камеры, источники (котельные OSM либо
// синтез) и пространственные ограничения (дороги/трамвай/ж-д/вода/парк/соцзоны).
//
// Тег slow / офлайн: сеть нужна только на этапе генерации (как E29/NFR-12).
//
// Запуск:
//   node scripts/generate-moscow-dataset.mjs --max-buildings 5000 \
//        --out data/generated/moscow-small.geojson
//   node scripts/generate-moscow-dataset.mjs --bbox 37.35,55.55,37.90,55.92 \
//        --max-buildings 200000 --network combo --out data/generated/moscow.geojson
//   node scripts/generate-moscow-dataset.mjs --synth-only --max-buildings 50000 \
//        --out data/generated/synth.geojson   # офлайн, без Overpass
//
// Параметры:
//   --bbox w,s,e,n        область (по умолчанию центр Москвы)
//   --tile-deg D          размер тайла Overpass, градусы (по умолчанию 0.05)
//   --max-buildings N     0 = без ограничения (контроль размера)
//   --network combo|osm|synth   источник теплосети (по умолчанию combo)
//   --network-density F   доля дорог, используемых как синтетическая сеть (0..1)
//   --seed N              детерминизм (по умолчанию 42)
//   --out PATH            выходной GeoJSON (по умолчанию data/generated/moscow.geojson)
//   --endpoint URL        Overpass (по умолчанию — список)
//   --synth-only          не обращаться к сети, всё синтезировать
//   --keep-raw            сохранить сырые ответы Overpass в target/dataset-generation

import { createWriteStream } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';

const ROOT = resolve(new URL('..', import.meta.url).pathname);
const DEFAULT_BBOX = [37.35, 55.55, 37.90, 55.92];
const DEFAULT_ENDPOINTS = [
  'https://overpass.private.coffee/api/interpreter',
  'https://overpass-api.de/api/interpreter',
  'https://overpass.kumi.systems/api/interpreter',
];

const DRIVABLE_HIGHWAY = new Set([
  'motorway', 'trunk', 'primary', 'secondary', 'tertiary', 'unclassified',
  'residential', 'living_street', 'road',
  'motorway_link', 'trunk_link', 'primary_link', 'secondary_link', 'tertiary_link',
]);
const RAIL_TYPES = new Set(['rail', 'light_rail', 'subway', 'narrow_gauge', 'monorail']);
const SOCIAL_AMENITIES = new Set(['school', 'hospital', 'kindergarten', 'university', 'college']);
const AREA_TYPES = new Set(['water', 'park', 'social_area', 'prohibited_site']);
const DIAMETERS = [200, 250, 300, 400, 500, 600, 700, 800];
const BOILER_TAGS = ['man_made=chimney', 'power=plant', 'building=industrial'];

function parseArgs(argv) {
  const args = {
    bbox: DEFAULT_BBOX, tileDeg: 0.05, maxBuildings: 0, network: 'combo',
    networkDensity: 0.25, seed: 42, out: 'data/generated/moscow.geojson',
    endpoint: null, synthOnly: false, keepRaw: false,
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--bbox') args.bbox = argv[++i].split(',').map(Number);
    else if (arg === '--tile-deg') args.tileDeg = Number(argv[++i]);
    else if (arg === '--max-buildings') args.maxBuildings = Number(argv[++i]);
    else if (arg === '--network') args.network = argv[++i];
    else if (arg === '--network-density') args.networkDensity = Number(argv[++i]);
    else if (arg === '--seed') args.seed = Number(argv[++i]);
    else if (arg === '--out') args.out = argv[++i];
    else if (arg === '--endpoint') args.endpoint = argv[++i];
    else if (arg === '--synth-only') args.synthOnly = true;
    else if (arg === '--keep-raw') args.keepRaw = true;
    else throw new Error(`Неизвестный аргумент: ${arg}`);
  }
  if (args.bbox.length !== 4 || args.bbox.some((v) => !Number.isFinite(v))) {
    throw new Error('--bbox должен быть w,s,e,n');
  }
  return args;
}

// --- Детерминированный PRNG (mulberry32) ---
function rng(seed) {
  let state = seed >>> 0;
  return () => {
    state |= 0;
    state = (state + 0x6d2b79f5) | 0;
    let t = Math.imul(state ^ (state >>> 15), 1 | state);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

// --- Клип по прямоугольнику (из E29) ---
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
  return [[a[0] + t0 * dx, a[1] + t0 * dy], [a[0] + t1 * dx, a[1] + t1 * dy]];
}
const samePoint = (a, b) => Math.abs(a[0] - b[0]) < 1e-9 && Math.abs(a[1] - b[1]) < 1e-9;
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
    if (current.length === 0) current.push(segment[0]);
    else if (!samePoint(current[current.length - 1], segment[0])) {
      flush();
      current.push(segment[0]);
    }
    current.push(segment[1]);
  }
  flush();
  return parts;
}
function vertical(a, b, x) {
  const t = (x - a[0]) / (b[0] - a[0]);
  return [x, a[1] + t * (b[1] - a[1])];
}
function horizontal(a, b, y) {
  const t = (y - a[1]) / (b[1] - a[1]);
  return [a[0] + t * (b[0] - a[0]), y];
}
function clipRing(ring, box) {
  const [xmin, ymin, xmax, ymax] = box;
  const boundaries = [
    { inside: (p) => p[0] >= xmin, intersect: (a, b) => vertical(a, b, xmin) },
    { inside: (p) => p[0] <= xmax, intersect: (a, b) => vertical(a, b, xmax) },
    { inside: (p) => p[1] >= ymin, intersect: (a, b) => horizontal(a, b, ymin) },
    { inside: (p) => p[1] <= ymax, intersect: (a, b) => horizontal(a, b, ymax) },
  ];
  let output = ring.slice(0, ring.length - 1);
  for (const { inside, intersect } of boundaries) {
    const input = output;
    output = [];
    for (let i = 0; i < input.length; i++) {
      const current = input[i];
      const previous = input[(i + input.length - 1) % input.length];
      const ci = inside(current);
      const pi = inside(previous);
      if (ci) {
        if (!pi) output.push(intersect(previous, current));
        output.push(current);
      } else if (pi) {
        output.push(intersect(previous, current));
      }
    }
    if (output.length === 0) return [];
  }
  output.push(output[0]);
  return output;
}
function clipPolygon(coordinates, box) {
  const exterior = clipRing(coordinates[0], box);
  if (exterior.length < 4) return null;
  const holes = coordinates.slice(1).map((ring) => clipRing(ring, box))
      .filter((ring) => ring.length >= 4);
  return { type: 'Polygon', coordinates: [exterior, ...holes] };
}
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
  if (geometry.type === 'Polygon') return clipPolygon(geometry.coordinates, box);
  if (geometry.type === 'MultiPolygon') {
    const polygons = geometry.coordinates.map((p) => clipPolygon(p, box)).filter(Boolean);
    if (polygons.length === 0) return null;
    return polygons.length === 1 ? polygons[0]
      : { type: 'MultiPolygon', coordinates: polygons.map((p) => p.coordinates) };
  }
  return null;
}

// --- Дороги: буфер по lanes (из E29) ---
const LANE_WIDTH_M = 3.0;
const MAX_ROAD_WIDTH_M = 6.0;
function roadHalfWidth(tags) {
  const lanes = Number.parseInt(tags['lanes'], 10);
  const count = Number.isFinite(lanes) && lanes > 0 ? lanes : 1;
  return Math.min(count * LANE_WIDTH_M, MAX_ROAD_WIDTH_M) / 2.0;
}
function normal(a, b) {
  const dx = b[0] - a[0];
  const dy = b[1] - a[1];
  const norm = Math.hypot(dx, dy);
  return norm < 1e-12 ? [0, 0] : [-dy / norm, dx / norm];
}
function bufferRing(coordinates, halfWidth) {
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
    if (i === 0) [nx, ny] = normal(xy[0], xy[1]);
    else if (i === n - 1) [nx, ny] = normal(xy[n - 2], xy[n - 1]);
    else {
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

function restrictionType(tags) {
  if (tags['man_made'] === 'pipeline' && tags['substance'] === 'gas') return 'gas_pipeline';
  if (tags['power'] && ['line', 'minor_line', 'cable'].includes(tags['power'])) {
    const voltage = tags['voltage'] ? Number(String(tags['voltage']).match(/(\d+)/)?.[1]) : null;
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

function toRestrictionGeometry(way, type) {
  const coordinates = way.geometry.map((p) => [p.lon, p.lat]);
  const closed = coordinates.length >= 4 && samePoint(coordinates[0], coordinates[coordinates.length - 1]);
  if (type === 'road') {
    if (closed && way.tags['area'] === 'yes') return { type: 'Polygon', coordinates: [coordinates] };
    return { type: 'Polygon', coordinates: [bufferRing(coordinates, roadHalfWidth(way.tags))] };
  }
  if (AREA_TYPES.has(type) && closed) return { type: 'Polygon', coordinates: [coordinates] };
  return { type: 'LineString', coordinates };
}

function overpassQuery(box) {
  const [w, s, e, n] = box;
  const area = `${s},${w},${n},${e}`;
  return `[out:json][timeout:180];
(
  way["building"](${area});
  way["highway"](${area});
  way["railway"](${area});
  way["natural"="water"](${area});
  way["waterway"](${area});
  way["leisure"="park"](${area});
  way["amenity"](${area});
  way["landuse"="military"](${area});
  way["military"](${area});
  way["man_made"="pipeline"](${area});
  way["power"](${area});
  node["man_made"="chimney"](${area});
  node["power"="plant"](${area});
);
out geom;`;
}

const sleep = (ms) => new Promise((done) => setTimeout(done, ms));

async function fetchOverpass(query, endpoints) {
  let lastError = null;
  for (let attempt = 0; attempt < 3; attempt++) {
    for (const endpoint of endpoints) {
      try {
        process.stderr.write(`[E8-15] Overpass (попытка ${attempt + 1}): ${endpoint}\n`);
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 180000);
        const response = await fetch(endpoint, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded',
            'User-Agent': 'lct2026-loadtest-gen/1.0',
          },
          body: new URLSearchParams({ data: query }),
          signal: controller.signal,
        }).finally(() => clearTimeout(timer));
        if (!response.ok) {
          lastError = new Error(`${endpoint}: HTTP ${response.status}`);
          process.stderr.write(`[E8-15] ${lastError.message}\n`);
          continue;
        }
        return { endpoint, body: await response.json() };
      } catch (error) {
        lastError = new Error(`${endpoint}: ${error.message}`);
        process.stderr.write(`[E8-15] ${lastError.message}\n`);
      }
    }
    if (attempt < 2) await sleep(15000 * (attempt + 1));
  }
  throw lastError ?? new Error('Overpass недоступен');
}

/** Потоковая запись FeatureCollection: не держит набор в памяти. */
class StreamingWriter {
  constructor(path) {
    this.stream = createWriteStream(path, 'utf8');
    this.bytes = 0;
    this.first = true;
    this.stream.write('{"type":"FeatureCollection","name":"moscow_loadtest",'
        + '"crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:OGC:1.3:CRS84"}},'
        + '"features":[');
  }

  write(feature) {
    const text = (this.first ? '' : ',') + JSON.stringify(feature);
    this.first = false;
    this.bytes += Buffer.byteLength(text);
    if (!this.stream.write(text)) {
      // обратное давление не критично для генератора; ждём событие drain
      return new Promise((done) => this.stream.once('drain', done));
    }
    return undefined;
  }

  async close(features) {
    const tail = '],"_meta":{"features":' + features + '}}';
    this.bytes += Buffer.byteLength(tail);
    this.stream.write(tail);
    await new Promise((done, fail) => {
      this.stream.end();
      this.stream.on('finish', done);
      this.stream.on('error', fail);
    });
  }
}

function buildingFlow(areaDeg) {
  // Площадь в м² ≈ areaDeg × (111320·cos) × 110540; расход — эвристика.
  const areaM2 = areaDeg * 111320 * 110540;
  return Math.max(5.0, Math.min(300.0, Math.round(areaM2 / 40.0)));
}

function centroid(ring) {
  let sx = 0;
  let sy = 0;
  for (let i = 0; i < ring.length - 1; i++) {
    sx += ring[i][0];
    sy += ring[i][1];
  }
  const n = Math.max(1, ring.length - 1);
  return [sx / n, sy / n];
}

function ringArea(ring) {
  let area = 0;
  for (let i = 0; i < ring.length - 1; i++) {
    area += ring[i][0] * ring[i + 1][1] - ring[i + 1][0] * ring[i][1];
  }
  return Math.abs(area) / 2;
}

function feature(id, objectType, geometry, extra = {}) {
  return {
    type: 'Feature',
    properties: { id, object_type: objectType, ...extra },
    geometry,
  };
}

/** Обработка OSM-элементов → набор фич (без хранения в памяти). */
async function processOsm(elements, bbox, writer, state, args, rand) {
  for (const element of elements) {
    if (!element.geometry) continue;
    if (element.type === 'node') {
      if (!insideBox(element, bbox)) continue;
      const tags = element.tags ?? {};
      if (tags['man_made'] === 'chimney' || tags['power'] === 'plant') {
        if (state.sources < 100) {
          await writer.write(feature(`osm_src_${element.id}`, 'source',
              { type: 'Point', coordinates: [element.lon, element.lat] },
              { name: tags.name ?? `Boiler ${element.id}` }));
          state.sources++;
        }
      }
      continue;
    }
    if (element.type !== 'way' || !element.tags) continue;
    if (state.seen.has(element.id)) continue;
    state.seen.add(element.id);
    const tags = element.tags;

    // Здания → ОКС + точка подключения.
    if (tags['building']) {
      if (args.maxBuildings > 0 && state.buildings >= args.maxBuildings) continue;
      const coordinates = element.geometry.map((p) => [p.lon, p.lat]);
      const closed = coordinates.length >= 4
          && samePoint(coordinates[0], coordinates[coordinates.length - 1]);
      if (!closed || ringArea(coordinates) < 1e-9) continue;
      const clipped = clipPolygon(coordinates, bbox);
      if (clipped) {
        await writer.write(feature(`osm_oks_${element.id}`, 'restriction',
            clipped, { restriction_type: 'oks' }));
        const c = centroid(clipped.coordinates[0]);
        await writer.write(feature(`osm_cp_${element.id}`, 'oks_connection_point',
            { type: 'Point', coordinates: c },
            { flow_tph: buildingFlow(ringArea(clipped.coordinates[0])) }));
        state.buildings++;
      }
      continue;
    }

    // Теплосеть OSM (где есть).
    if (tags['man_made'] === 'pipeline') {
      const coordinates = element.geometry.map((p) => [p.lon, p.lat]);
      const clipped = clipGeometry({ type: 'LineString', coordinates }, bbox);
      if (clipped) {
        const diameter = Number(tags['diameter']) || DIAMETERS[Math.floor(rand() * DIAMETERS.length)];
        state.networkFeatures.push({ id: `osm_hw_${element.id}`, diameter, geometry: clipped });
        state.osmPipeline++;
      }
      continue;
    }

    // Ограничения.
    const type = restrictionType(tags);
    if (type) {
      const geometry = toRestrictionGeometry(element, type);
      const clipped = clipGeometry(geometry, bbox);
      if (clipped) {
        await writer.write(feature(`osm_r_${element.id}`, 'restriction', clipped,
            { restriction_type: type }));
        state.restrictions[type] = (state.restrictions[type] ?? 0) + 1;
      }
    }
    // Дороги сохраняем для синтетической сети.
    if (tags['highway'] && DRIVABLE_HIGHWAY.has(tags['highway'])) {
      state.roads.push(element.geometry.map((p) => [p.lon, p.lat]));
    }
  }
}

function insideBox(element, box) {
  return element.lon >= box[0] - 0.01 && element.lon <= box[2] + 0.01
      && element.lat >= box[1] - 0.01 && element.lat <= box[3] + 0.01;
}

/** Синтетическая сеть из дорог: участки + камеры + источники. */
async function writeSyntheticNetwork(writer, state, args, rand) {
  const roads = state.roads;
  if (roads.length === 0) return;
  const step = Math.max(2, Math.round(4 / Math.max(0.05, args.networkDensity)));
  let index = 0;
  const nodes = new Map();
  for (let i = 0; i < roads.length; i += step) {
    const coords = roads[i];
    if (coords.length < 2) continue;
    const diameter = DIAMETERS[Math.floor(rand() * DIAMETERS.length)];
    const geometry = { type: 'LineString', coordinates: coords };
    await writer.write(feature(`syn_hw_${index++}`, 'heat_network', geometry, { diameter }));
    state.network++;
    // Камера в середине примерно 1/3 участков.
    if (rand() < 0.35) {
      const c = coords[Math.floor(coords.length / 2)];
      const key = `${c[0].toFixed(5)}:${c[1].toFixed(5)}`;
      if (!nodes.has(key)) {
        nodes.set(key, true);
        await writer.write(feature(`syn_ch_${nodes.size}`, 'heat_chamber',
            { type: 'Point', coordinates: c }, { diameter }));
        state.chambers++;
      }
    }
  }
  // Источники: котельные OSM уже добавлены; если их нет — синтез вдоль сети.
  if (state.sources === 0 && roads.length > 0) {
    for (let k = 0; k < Math.min(5, roads.length); k++) {
      const coords = roads[Math.floor(rand() * roads.length)];
      const c = coords[0];
      await writer.write(feature(`syn_src_${k}`, 'source',
          { type: 'Point', coordinates: c }, { name: `Synthetic boiler ${k}` }));
      state.sources++;
    }
  }
}

/** Полностью синтетический офлайн-набор (для CI/теста харнесса). */
async function writeSyntheticWorld(writer, bbox, args, rand) {
  const [w, s, e, n] = bbox;
  const step = 0.004;
  const netStep = 8;
  const state = { buildings: 0, network: 0, chambers: 0, sources: 0 };
  const cols = Math.max(1, Math.floor((e - w) / step));
  const rows = Math.max(1, Math.floor((n - s) / step));
  let roadCount = 0;
  // Сетка «дорог» (ограничения), шаг вдвое реже — снижает число «ворот».
  for (let r = 0; r <= rows; r += 2) {
    const y = s + r * step;
    await writer.write(feature(`syn_road_h_${r}`, 'restriction',
        { type: 'LineString', coordinates: [[w, y], [e, y]] }, { restriction_type: 'road' }));
    roadCount++;
  }
  for (let c = 0; c <= cols; c += 2) {
    const x = w + c * step;
    await writer.write(feature(`syn_road_v_${c}`, 'restriction',
        { type: 'LineString', coordinates: [[x, s], [x, n]] }, { restriction_type: 'road' }));
    roadCount++;
  }
  // Разреженная теплосеть: редкие магистрали H/V — дерево остаётся малым
  // (иначе сеть «везде» даёт гигантский лес и упор в heap по картам дерева).
  let netIndex = 0;
  for (let r = 0; r <= rows; r += netStep) {
    const y = s + r * step;
    await writer.write(feature(`syn_hw_h_${r}`, 'heat_network',
        { type: 'LineString', coordinates: [[w, y], [e, y]] },
        { diameter: DIAMETERS[netIndex % DIAMETERS.length] }));
    netIndex++;
  }
  for (let c = 0; c <= cols; c += netStep) {
    const x = w + c * step;
    await writer.write(feature(`syn_hw_v_${c}`, 'heat_network',
        { type: 'LineString', coordinates: [[x, s], [x, n]] },
        { diameter: DIAMETERS[netIndex % DIAMETERS.length] }));
    netIndex++;
  }
  state.network = netIndex;
  // Здания (квадраты) и точки подключения — по всей области.
  let count = 0;
  for (let r = 0; r < rows; r++) {
    for (let c = 0; c < cols; c++) {
      if (args.maxBuildings > 0 && count >= args.maxBuildings) break;
      if (rand() < 0.5) continue;
      const x = w + c * step + step * 0.2;
      const y = s + r * step + step * 0.2;
      const size = step * 0.4;
      const ring = [[x, y], [x + size, y], [x + size, y + size], [x, y + size], [x, y]];
      await writer.write(feature(`syn_oks_${count}`, 'restriction',
          { type: 'Polygon', coordinates: [ring] }, { restriction_type: 'oks' }));
      await writer.write(feature(`syn_cp_${count}`, 'oks_connection_point',
          { type: 'Point', coordinates: [x + size / 2, y + size / 2] },
          { flow_tph: 5 + Math.round(rand() * 40) }));
      count++;
    }
  }
  state.buildings = count;
  // Камеры и источники вдоль сети.
  let chamberIndex = 0;
  for (let c = 0; c <= cols; c += netStep) {
    await writer.write(feature(`syn_ch_${chamberIndex++}`, 'heat_chamber',
        { type: 'Point', coordinates: [w + c * step, s] },
        { diameter: DIAMETERS[chamberIndex % DIAMETERS.length] }));
  }
  state.chambers = chamberIndex;
  await writer.write(feature('syn_src_0', 'source',
      { type: 'Point', coordinates: [w, s] }, { name: 'Synthetic source' }));
  state.sources = 1;
  state.restrictions = { road: roadCount };
  return state;
}

function tileBoxes(bbox, tileDeg) {
  const [w, s, e, n] = bbox;
  const boxes = [];
  for (let y = s; y < n; y += tileDeg) {
    for (let x = w; x < e; x += tileDeg) {
      boxes.push([x, y, Math.min(x + tileDeg, e), Math.min(y + tileDeg, n)]);
    }
  }
  return boxes;
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const rand = rng(args.seed);
  const outPath = resolve(ROOT, args.out);
  await mkdir(dirname(outPath), { recursive: true });
  const writer = new StreamingWriter(outPath);
  const state = {
    seen: new Set(), buildings: 0, network: 0, chambers: 0, sources: 0,
    restrictions: {}, roads: [], networkFeatures: [], osmPipeline: 0,
  };

  const started = Date.now();
  if (args.synthOnly) {
    const synth = await writeSyntheticWorld(writer, args.bbox, args, rand);
    Object.assign(state, synth);
    process.stderr.write('[E8-15] synth-only режим (без Overpass)\n');
  } else {
    const boxes = tileBoxes(args.bbox, args.tileDeg);
    const endpoints = args.endpoint ? [args.endpoint] : DEFAULT_ENDPOINTS;
    process.stderr.write(`[E8-15] тайлов: ${boxes.length}\n`);
    let tile = 0;
    for (const box of boxes) {
      tile++;
      if (args.maxBuildings > 0 && state.buildings >= args.maxBuildings) break;
      const query = overpassQuery(box);
      const { endpoint, body } = await fetchOverpass(query, endpoints);
      process.stderr.write(`[E8-15] тайл ${tile}/${boxes.length}: ${body.elements?.length ?? 0} эл. (${endpoint})\n`);
      if (args.keepRaw) {
        const rawDir = resolve(ROOT, 'target/dataset-generation');
        await mkdir(rawDir, { recursive: true });
        await writeFile(`${rawDir}/moscow-tile-${tile}.json`, JSON.stringify(body));
      }
      await processOsm(body.elements ?? [], args.bbox, writer, state, args, rand);
    }
    // OSM-трубопроводы как теплосеть.
    for (const net of state.networkFeatures) {
      await writer.write(feature(net.id, 'heat_network', net.geometry, { diameter: net.diameter }));
      state.network++;
    }
    if (args.network !== 'osm') {
      await writeSyntheticNetwork(writer, state, args, rand);
    } else if (state.network === 0) {
      process.stderr.write('[E8-15] В OSM не найдено трубопроводов; сеть пуста\n');
    }
  }

  const total = Object.values(state.restrictions).reduce((a, b) => a + b, 0);
  const totalFeatures = state.buildings * 2 + total + state.network + state.chambers
      + state.sources;
  await writer.close(totalFeatures);
  const manifest = {
    generated: new Date().toISOString(),
    bbox: args.bbox,
    seed: args.seed,
    synthOnly: args.synthOnly,
    networkMode: args.network,
    out: args.out,
    bytes: writer.bytes,
    buildings: state.buildings,
    network: state.network,
    chambers: state.chambers,
    sources: state.sources,
    osmPipeline: state.osmPipeline,
    restrictions: state.restrictions,
    restrictionsTotal: total,
    elapsedMs: Date.now() - started,
  };
  await writeFile(outPath.replace(/\.geojson$/, '.generation.json'),
      JSON.stringify(manifest, null, 2), 'utf8');
  process.stderr.write(`[E8-15] Готово: ${(writer.bytes / 1048576).toFixed(1)} МБ, `
      + `buildings=${state.buildings}, network=${state.network}, chambers=${state.chambers}, `
      + `sources=${state.sources}, restrictions=${JSON.stringify(state.restrictions)}\n`);
}

main().catch((error) => {
  process.stderr.write(`[E8-15] Ошибка: ${error.stack ?? error.message}\n`);
  process.exit(1);
});
