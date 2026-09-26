import type { Map as MapLibreMap } from 'maplibre-gl';
import type { GeoFeature } from '../types';
import { restrictionFillColor } from './paint';
import { utmToWgs84, wgs84ToUtm } from './utm';

const SOURCE = 'selected-shape';
const BASE_LAYER = 'layer-selected-line-base';
const DOTS_SOURCE = 'selected-dots';
const DOTS_LAYER = 'layer-selected-dots';
const RING_SOURCE = 'selected-ring';
const RING_LAYER = 'layer-selected-ring';
const FILL_LAYER = 'layer-selected-fill';
const FILL_OUTLINE_LAYER = 'layer-selected-fill-outline';
const SELECTED_COLOR = '#2563eb';

/** Шаг точек линии при сэмплировании, м. */
const SAMPLING_M = 1;
/** Расстояние между марширующими точками, м. */
const DOT_STEP_M = 6;
/** Длительность прохода одной точки на шаг, мс. */
const DOT_CYCLE_MS = 1000;
/** Минимальный интервал обновления источника точек, мс. */
const DOT_UPDATE_MS = 60;

/** Кольцо из точек вокруг точечного объекта (ADR-0058). */
const RING_COUNT = 10;
const RING_MIN_M = 12;
const RING_MAX_M = 20;
const RING_CYCLE_MS = 1400;
const RING_UPDATE_MS = 50;

let rafId = 0;
let streamActive = false;
let dotSamples: Array<[number, number]> = [];
let ringCenter: [number, number] | null = null;

/** Подсветка выбранного объекта: марширующие точки / пульсирующее кольцо. */
export function applySelection(map: MapLibreMap, feature: GeoFeature | null): void {
  stopAnimation();
  removeSelection(map);
  const geometry = feature?.geometry;
  if (!feature || !geometry) {
    return;
  }
  setSource(map, feature);
  if (geometry.type === 'LineString' || geometry.type === 'MultiLineString') {
    dotSamples = buildSamples(geometry.coordinates);
    addLineBase(map);
    addDots(map);
    streamActive = true;
    startDotsAnimation(map);
  } else if (geometry.type === 'Point' || geometry.type === 'MultiPoint') {
    ringCenter = firstPoint(geometry.coordinates);
    if (ringCenter) {
      addRing(map);
      streamActive = true;
      startRingAnimation(map);
    }
  } else {
    addFill(map);
  }
}

export function clearSelection(map: MapLibreMap): void {
  stopAnimation();
  removeSelection(map);
}

function setSource(map: MapLibreMap, feature: GeoFeature): void {
  const data = { type: 'FeatureCollection', features: [feature] };
  const existing = map.getSource(SOURCE);
  if (existing) {
    (existing as unknown as { setData: (value: unknown) => void }).setData(data);
  } else {
    map.addSource(SOURCE, { type: 'geojson', data: data as never });
  }
}

function addLineBase(map: MapLibreMap): void {
  if (map.getLayer(BASE_LAYER)) {
    return;
  }
  map.addLayer({
    id: BASE_LAYER,
    type: 'line',
    source: SOURCE,
    layout: { 'line-cap': 'round', 'line-join': 'round' },
    paint: {
      'line-color': SELECTED_COLOR,
      'line-width': 4,
      'line-opacity': 0.35,
    },
  } as never);
}

function addDots(map: MapLibreMap): void {
  const empty = { type: 'FeatureCollection', features: [] as unknown[] };
  if (!map.getSource(DOTS_SOURCE)) {
    map.addSource(DOTS_SOURCE, { type: 'geojson', data: empty as never });
  }
  if (!map.getLayer(DOTS_LAYER)) {
    map.addLayer({
      id: DOTS_LAYER,
      type: 'circle',
      source: DOTS_SOURCE,
      paint: {
        'circle-color': SELECTED_COLOR,
        'circle-radius': 3.5,
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 1,
      },
    } as never);
  }
}

function addRing(map: MapLibreMap): void {
  const empty = { type: 'FeatureCollection', features: [] as unknown[] };
  if (!map.getSource(RING_SOURCE)) {
    map.addSource(RING_SOURCE, { type: 'geojson', data: empty as never });
  }
  if (!map.getLayer(RING_LAYER)) {
    map.addLayer({
      id: RING_LAYER,
      type: 'circle',
      source: RING_SOURCE,
      paint: {
        'circle-color': SELECTED_COLOR,
        'circle-radius': 3.5,
        'circle-stroke-color': '#ffffff',
        'circle-stroke-width': 1,
        'circle-opacity': 0.9,
      },
    } as never);
  }
}

function addFill(map: MapLibreMap): void {
  // Ограничения выделяем как при наведении: заливка + сплошной контур.
  if (!map.getLayer(FILL_LAYER)) {
    map.addLayer({
      id: FILL_LAYER,
      type: 'fill',
      source: SOURCE,
      paint: {
        'fill-color': restrictionFillColor() as never,
        'fill-opacity': 0.45,
      },
    } as never);
  }
  if (!map.getLayer(FILL_OUTLINE_LAYER)) {
    map.addLayer({
      id: FILL_OUTLINE_LAYER,
      type: 'line',
      source: SOURCE,
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: {
        'line-color': restrictionFillColor() as never,
        'line-width': 4,
      },
    } as never);
  }
}

/** Марширующие точки вдоль выбранной линии: сдвиг фазы → движение к концу. */
function startDotsAnimation(map: MapLibreMap): void {
  const start = performance.now();
  let lastUpdate = 0;
  const tick = (now: number) => {
    if (!streamActive || !map.getLayer(DOTS_LAYER)) {
      return;
    }
    if (now - lastUpdate >= DOT_UPDATE_MS) {
      lastUpdate = now;
      const phase = ((now - start) / DOT_CYCLE_MS) % 1;
      const offset = Math.round(phase * DOT_STEP_M);
      const features: Array<Record<string, unknown>> = [];
      for (let index = offset; index < dotSamples.length; index += DOT_STEP_M) {
        features.push({
          type: 'Feature',
          geometry: { type: 'Point', coordinates: dotSamples[index] },
          properties: {},
        });
      }
      setData(map, DOTS_SOURCE, features);
    }
    rafId = window.requestAnimationFrame(tick);
  };
  rafId = window.requestAnimationFrame(tick);
}

/** Кольцо из точек вокруг точечного объекта, пульсирующее по радиусу и яркости. */
function startRingAnimation(map: MapLibreMap): void {
  const start = performance.now();
  let lastUpdate = 0;
  const tick = (now: number) => {
    if (!streamActive || !map.getLayer(RING_LAYER) || !ringCenter) {
      return;
    }
    if (now - lastUpdate >= RING_UPDATE_MS) {
      lastUpdate = now;
      const wave = 0.5 + 0.5 * Math.sin((((now - start) / RING_CYCLE_MS) * 2 * Math.PI));
      const radius = RING_MIN_M + (RING_MAX_M - RING_MIN_M) * wave;
      const features = ringPoints(ringCenter, radius, RING_COUNT);
      setData(map, RING_SOURCE, features);
      map.setPaintProperty(RING_LAYER, 'circle-opacity', (0.45 + 0.5 * wave) as never);
    }
    rafId = window.requestAnimationFrame(tick);
  };
  rafId = window.requestAnimationFrame(tick);
}

function ringPoints(
  center: [number, number],
  radiusM: number,
  count: number,
): Array<Record<string, unknown>> {
  const [lng, lat] = center;
  const dLat = radiusM / 111320;
  const dLng = radiusM / (111320 * Math.cos((lat * Math.PI) / 180));
  const features: Array<Record<string, unknown>> = [];
  for (let i = 0; i < count; i++) {
    const angle = (2 * Math.PI * i) / count;
    features.push({
      type: 'Feature',
      geometry: {
        type: 'Point',
        coordinates: [lng + dLng * Math.cos(angle), lat + dLat * Math.sin(angle)],
      },
      properties: {},
    });
  }
  return features;
}

function setData(map: MapLibreMap, sourceId: string, features: Array<Record<string, unknown>>): void {
  const source = map.getSource(sourceId) as
    { setData?: (value: unknown) => void } | undefined;
  source?.setData?.({ type: 'FeatureCollection', features });
}

/** Точки линии с шагом {@link SAMPLING_M} м (WGS84). */
function buildSamples(coordinates: unknown): Array<[number, number]> {
  const samples: Array<[number, number]> = [];
  eachLine(coordinates, (line) => {
    const projected: Array<[number, number]> = [];
    line.forEach((coordinate) => {
      if (Array.isArray(coordinate) && typeof coordinate[0] === 'number'
          && typeof coordinate[1] === 'number') {
        const point = wgs84ToUtm(coordinate[0], coordinate[1]);
        projected.push([point.x, point.y]);
      }
    });
    if (projected.length < 2) {
      return;
    }
    let travelled = 0;
    let next = 0;
    for (let i = 0; i < projected.length - 1; i++) {
      const [x1, y1] = projected[i];
      const [x2, y2] = projected[i + 1];
      const segment = Math.hypot(x2 - x1, y2 - y1);
      if (segment <= 0) {
        continue;
      }
      for (let distance = next; distance <= travelled + segment; distance += SAMPLING_M) {
        const t = (distance - travelled) / segment;
        const [lng, lat] = utmToWgs84(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t);
        samples.push([lng, lat]);
      }
      travelled += segment;
      next = Math.ceil(travelled / SAMPLING_M) * SAMPLING_M;
    }
  });
  return samples;
}

function firstPoint(node: unknown): [number, number] | null {
  if (!Array.isArray(node)) {
    return null;
  }
  if (typeof node[0] === 'number' && typeof node[1] === 'number') {
    return [node[0], node[1]];
  }
  for (const child of node) {
    const point = firstPoint(child);
    if (point) {
      return point;
    }
  }
  return null;
}

function eachLine(node: unknown, visit: (line: unknown[]) => void): void {
  if (!Array.isArray(node) || node.length === 0) {
    return;
  }
  const first = node[0];
  if (Array.isArray(first) && typeof first[0] === 'number') {
    visit(node);
    return;
  }
  node.forEach((child) => eachLine(child, visit));
}

function stopAnimation(): void {
  streamActive = false;
  if (rafId) {
    window.cancelAnimationFrame(rafId);
    rafId = 0;
  }
}

function removeSelection(map: MapLibreMap): void {
  [BASE_LAYER, DOTS_LAYER, RING_LAYER, FILL_LAYER, FILL_OUTLINE_LAYER].forEach((id) => {
    if (map.getLayer(id)) {
      map.removeLayer(id);
    }
  });
  [SOURCE, DOTS_SOURCE, RING_SOURCE].forEach((id) => {
    if (map.getSource(id)) {
      map.removeSource(id);
    }
  });
}
