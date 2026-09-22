import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl';
import { LngLatBounds } from 'maplibre-gl';
import { FeatureCollection, GeoFeature, GridMask } from '../types';
import { RESULT_STAGE } from '../store';
import { OverlayData, fitToData, updateOverlays } from './layers';
import { decodeBits } from './gridMask';
import { utmToWgs84, wgs84ToUtm } from './utm';

/**
 * Отрисовка промежуточных этапов алгоритма (ADR-0036/0037). Когда активна
 * вкладка этапа — результат скрывается, поверх контекста накладываются объекты
 * этапа; для этапа «сетка» рисуется растровая маска (без сглаживания) и, при
 * достаточном приближении, контуры ячеек.
 */

export interface StageOverlayData extends OverlayData {
  activeStage: string;
  stageFeatures: FeatureCollection | null;
  gridMask: GridMask | null;
}

const STAGE_SOURCES = ['stage-polygons', 'stage-lines', 'stage-points', 'stage-grid', 'stage-grid-cells'];
const STAGE_LAYERS = [
  'layer-stage-polygons',
  'layer-stage-polygons-outline',
  'layer-stage-lines',
  'layer-stage-points',
  'layer-stage-grid',
  'layer-stage-grid-cells',
];

/** Минимальный зум, при котором рисуются контуры ячеек сетки. */
const GRID_CELL_MIN_ZOOM = 16;
/** Предел числа линий ячеек (защита от перерисовки на большом вьюпорте). */
const MAX_GRID_CELL_LINES = 3000;
/** Шаг сэмплирования линий при переводе UTM→WGS84, м. */
const GRID_LINE_SAMPLE_STEP_M = 200.0;

interface StageColors {
  polygon?: unknown;
  line?: string;
  point?: string;
}

function restrictionPolygonColor(): unknown {
  return ['match', ['get', 'object_type'], 'special_zone', '#f59e0b', 'obstacle', '#dc2626',
    '#dc2626'];
}

function stageColors(kind: string): StageColors {
  switch (kind) {
    case 'network':
      return { line: '#64748b', point: '#64748b' };
    case 'restrictions':
      return { polygon: restrictionPolygonColor() };
    case 'exits':
      return { line: '#2563eb', point: '#2563eb' };
    case 'trees':
      return { line: '#f97316' };
    case 'refine':
      return { line: '#16a34a', point: '#16a34a' };
    case 'relink':
      return { line: '#0d9488', point: '#0d9488' };
    default:
      return {};
  }
}

/** Цвет точки: сетка — источники/терминалы, сеть — камеры/источники. */
function pointColor(kind: string, fallback: string | undefined): unknown {
  if (kind === 'grid') {
    return [
      'match',
      ['get', 'object_type'],
      'grid_terminal',
      '#a21caf',
      'grid_source',
      '#2563eb',
      fallback ?? '#64748b',
    ];
  }
  if (kind === 'network') {
    return [
      'match',
      ['get', 'object_type'],
      'source',
      '#047857',
      'heat_chamber',
      '#64748b',
      fallback ?? '#64748b',
    ];
  }
  return fallback ?? '#64748b';
}

export function fitStageToData(map: MapLibreMap, data: StageOverlayData): void {
  if (data.activeStage === RESULT_STAGE) {
    fitToData(map, data);
    return;
  }
  const bounds = new LngLatBounds();
  let hasCoordinates = false;
  const extend = (node: unknown) => {
    if (!Array.isArray(node)) {
      return;
    }
    if (typeof node[0] === 'number' && typeof node[1] === 'number') {
      bounds.extend([node[0], node[1]]);
      hasCoordinates = true;
      return;
    }
    node.forEach(extend);
  };
  data.stageFeatures?.features.forEach((feature) => extend(feature.geometry?.coordinates));
  data.gridMask?.boundsWgs84?.forEach((corner) => {
    bounds.extend(corner);
    hasCoordinates = true;
  });
  data.input?.features.forEach((feature) => extend(feature.geometry?.coordinates));
  if (hasCoordinates) {
    map.fitBounds(bounds, { padding: 60, maxZoom: 18 });
  }
}

export function applyOverlays(map: MapLibreMap, data: StageOverlayData): void {
  if (data.activeStage === RESULT_STAGE) {
    clearStageLayers(map);
    updateOverlays(map, data);
    return;
  }
  // Контекст входа (сеть, ограничения, точки) остаётся, результат скрываем.
  updateOverlays(map, { ...data, result: null });
  updateStageLayers(map, data);
}

function splitFeatures(collection: FeatureCollection | null): {
  polygons: GeoFeature[];
  lines: GeoFeature[];
  points: GeoFeature[];
} {
  const polygons: GeoFeature[] = [];
  const lines: GeoFeature[] = [];
  const points: GeoFeature[] = [];
  collection?.features.forEach((feature) => {
    const type = feature.geometry?.type;
    if (type === 'Polygon' || type === 'MultiPolygon') {
      polygons.push(feature);
    } else if (type === 'LineString' || type === 'MultiLineString') {
      lines.push(feature);
    } else if (type === 'Point' || type === 'MultiPoint') {
      points.push(feature);
    }
  });
  return { polygons, lines, points };
}

function updateStageLayers(map: MapLibreMap, data: StageOverlayData): void {
  const kind = data.activeStage;
  const colors = stageColors(kind);
  const { polygons, lines, points } = splitFeatures(data.stageFeatures);
  const gridPoints = kind === 'grid' && data.gridMask
    ? gridMaskPoints(data.gridMask)
    : null;

  const empty: FeatureCollection = { type: 'FeatureCollection', features: [] };
  setSource(map, 'stage-polygons', colors.polygon ? toCollection(polygons) : empty);
  setSource(map, 'stage-lines', colors.line ? toCollection(lines) : empty);
  setSource(
    map,
    'stage-points',
    gridPoints ?? (colors.point || kind === 'network' ? toCollection(points) : empty),
  );

  if (colors.polygon) {
    ensureFill(map, 'layer-stage-polygons', 'stage-polygons', colors.polygon, 0.22);
    ensureLine(map, 'layer-stage-polygons-outline', 'stage-polygons', colors.polygon, 1.5);
  } else {
    setLayerVisibility(map, 'layer-stage-polygons', false);
    setLayerVisibility(map, 'layer-stage-polygons-outline', false);
  }
  if (colors.line) {
    ensureLine(map, 'layer-stage-lines', 'stage-lines', colors.line, 2);
  } else {
    setLayerVisibility(map, 'layer-stage-lines', false);
  }
  if (colors.point || kind === 'network' || kind === 'grid') {
    ensureCircle(map, 'layer-stage-points', 'stage-points', pointColor(kind, colors.point));
  } else {
    setLayerVisibility(map, 'layer-stage-points', false);
  }

  updateGridLayer(map, kind === 'grid' ? data.gridMask : null);
  refreshStageGridCells(map, data);
}

/** Контуры ячеек сетки в текущем вьюпорте (при достаточном зуме). */
export function refreshStageGridCells(map: MapLibreMap, data: StageOverlayData): void {
  const mask = data.activeStage === 'grid' ? data.gridMask : null;
  if (!mask || map.getZoom() < GRID_CELL_MIN_ZOOM) {
    setSource(map, 'stage-grid-cells', { type: 'FeatureCollection', features: [] });
    setLayerVisibility(map, 'layer-stage-grid-cells', false);
    return;
  }
  const cell = mask.cellM;
  const rowSpacing = mask.rowSpacing && mask.rowSpacing > 0 ? mask.rowSpacing : cell;
  const gridMaxX = mask.originX + mask.width * cell;
  const gridMaxY = mask.originY + mask.height * rowSpacing;
  const bounds = map.getBounds();
  const sw = wgs84ToUtm(bounds.getWest(), bounds.getSouth());
  const ne = wgs84ToUtm(bounds.getEast(), bounds.getNorth());
  const minX = Math.max(sw.x, mask.originX);
  const maxX = Math.min(ne.x, gridMaxX);
  const minY = Math.max(sw.y, mask.originY);
  const maxY = Math.min(ne.y, gridMaxY);
  if (maxX <= minX || maxY <= minY) {
    setSource(map, 'stage-grid-cells', { type: 'FeatureCollection', features: [] });
    setLayerVisibility(map, 'layer-stage-grid-cells', false);
    return;
  }
  const col0 = Math.floor((minX - mask.originX) / cell);
  const col1 = Math.ceil((maxX - mask.originX) / cell);
  const row0 = Math.floor((minY - mask.originY) / rowSpacing);
  const row1 = Math.ceil((maxY - mask.originY) / rowSpacing);
  const features: GeoFeature[] = [];
  if (mask.gridShape === 'hex') {
    const size = cell / Math.sqrt(3);
    if ((col1 - col0 + 1) * (row1 - row0 + 1) > MAX_GRID_CELL_LINES) {
      setSource(map, 'stage-grid-cells', { type: 'FeatureCollection', features: [] });
      setLayerVisibility(map, 'layer-stage-grid-cells', false);
      return;
    }
    for (let row = row0; row <= row1; row++) {
      const cy = mask.originY + (row + 0.5) * rowSpacing;
      for (let col = col0; col <= col1; col++) {
        const cx = mask.originX + (col + 0.5 + (row & 1) * 0.5) * cell;
        if (cx < minX || cx > maxX || cy < minY || cy > maxY) {
          continue;
        }
        features.push(hexRing(cx, cy, size));
      }
    }
  } else {
    if ((col1 - col0) + (row1 - row0) > MAX_GRID_CELL_LINES) {
      setSource(map, 'stage-grid-cells', { type: 'FeatureCollection', features: [] });
      setLayerVisibility(map, 'layer-stage-grid-cells', false);
      return;
    }
    for (let col = col0; col <= col1; col++) {
      const x = mask.originX + col * cell;
      features.push(cellLine(x, minY, x, maxY));
    }
    for (let row = row0; row <= row1; row++) {
      const y = mask.originY + row * rowSpacing;
      features.push(cellLine(minX, y, maxX, y));
    }
  }
  setSource(map, 'stage-grid-cells', { type: 'FeatureCollection', features });
  ensureLine(map, 'layer-stage-grid-cells', 'stage-grid-cells', '#0f172a', 0.5);
  setLayerVisibility(map, 'layer-stage-grid-cells', true);
}

/** Контур гексагональной ячейки (pointy-top) в UTM → WGS84. */
function hexRing(cx: number, cy: number, size: number): GeoFeature {
  const coordinates: Array<[number, number]> = [];
  for (let k = 0; k <= 6; k++) {
    const angle = (Math.PI / 180) * (60 * k + 30);
    coordinates.push(utmToWgs84(cx + size * Math.cos(angle), cy + size * Math.sin(angle)));
  }
  return {
    type: 'Feature',
    geometry: { type: 'LineString', coordinates },
    properties: { object_type: 'grid_cell' },
  };
}

/** Прямая в UTM как ломаная в WGS84 (кривизна меридианов). */
function cellLine(x1: number, y1: number, x2: number, y2: number): GeoFeature {
  const length = Math.hypot(x2 - x1, y2 - y1);
  const steps = Math.max(1, Math.ceil(length / GRID_LINE_SAMPLE_STEP_M));
  const coordinates: Array<[number, number]> = [];
  for (let i = 0; i <= steps; i++) {
    const t = i / steps;
    coordinates.push(utmToWgs84(x1 + (x2 - x1) * t, y1 + (y2 - y1) * t));
  }
  return {
    type: 'Feature',
    geometry: { type: 'LineString', coordinates },
    properties: { object_type: 'grid_cell' },
  };
}

function gridMaskPoints(mask: GridMask): FeatureCollection {
  const features: GeoFeature[] = [];
  mask.sources?.forEach((coordinate, index) => {
    features.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: coordinate },
      properties: { id: `grid_source_${index}`, object_type: 'grid_source' },
    });
  });
  mask.terminalCells?.forEach((coordinate, index) => {
    features.push({
      type: 'Feature',
      geometry: { type: 'Point', coordinates: coordinate },
      properties: { id: `grid_terminal_${index}`, object_type: 'grid_terminal' },
    });
  });
  return { type: 'FeatureCollection', features };
}

function updateGridLayer(map: MapLibreMap, mask: GridMask | null): void {
  removeGridLayer(map);
  if (!mask) {
    return;
  }
  const url = buildGridDataUrl(mask);
  try {
    map.addSource('stage-grid', {
      type: 'image',
      url,
      coordinates: mask.boundsWgs84 as never,
    });
    map.addLayer({
      id: 'layer-stage-grid',
      type: 'raster',
      source: 'stage-grid',
      paint: {
        'raster-opacity': 0.75,
        'raster-fade-duration': 0,
        'raster-resampling': 'nearest',
      },
    } as never);
  } catch (error) {
    console.error('[map] не удалось показать маску сетки', error);
  }
}

function removeGridLayer(map: MapLibreMap): void {
  if (map.getLayer('layer-stage-grid')) {
    map.removeLayer('layer-stage-grid');
  }
  if (map.getSource('stage-grid')) {
    map.removeSource('stage-grid');
  }
}

/** Раскрашивает маски запретов/достижимости в RGBA-изображение (data URL). */
export function buildGridDataUrl(mask: GridMask): string {
  const width = mask.imageWidth;
  const height = mask.imageHeight;
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  if (!context) {
    return '';
  }
  const image = context.createImageData(width, height);
  const blocked = decodeBits(mask.blocked, width * height);
  const reachable = decodeBits(mask.reachable, width * height);
  for (let index = 0; index < width * height; index++) {
    const offset = index * 4;
    if (blocked[index]) {
      image.data[offset] = 100;
      image.data[offset + 1] = 116;
      image.data[offset + 2] = 139;
      image.data[offset + 3] = 130;
    } else if (reachable[index]) {
      image.data[offset] = 37;
      image.data[offset + 1] = 99;
      image.data[offset + 2] = 235;
      image.data[offset + 3] = 55;
    }
  }
  context.putImageData(image, 0, 0);
  return canvas.toDataURL('image/png');
}

function clearStageLayers(map: MapLibreMap): void {
  STAGE_LAYERS.forEach((id) => {
    if (map.getLayer(id)) {
      map.removeLayer(id);
    }
  });
  STAGE_SOURCES.forEach((id) => {
    if (map.getSource(id)) {
      map.removeSource(id);
    }
  });
}

function toCollection(features: GeoFeature[]): FeatureCollection {
  return { type: 'FeatureCollection', features };
}

function setSource(map: MapLibreMap, id: string, data: FeatureCollection): void {
  try {
    const existing = map.getSource(id) as GeoJSONSource | undefined;
    if (existing) {
      existing.setData(data as never);
    } else {
      map.addSource(id, { type: 'geojson', data: data as never });
    }
  } catch (error) {
    console.error(`[map] не удалось обновить источник этапа ${id}`, error);
  }
}

function ensureFill(
  map: MapLibreMap,
  id: string,
  source: string,
  color: unknown,
  opacity: number,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'fill',
        source,
        paint: { 'fill-color': color, 'fill-opacity': opacity },
      } as never);
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setLayerVisibility(map, id, true);
}

function ensureLine(
  map: MapLibreMap,
  id: string,
  source: string,
  color: unknown,
  width: number,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'line',
        source,
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: { 'line-color': color, 'line-width': width },
      } as never);
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setLayerVisibility(map, id, true);
}

function ensureCircle(
  map: MapLibreMap,
  id: string,
  source: string,
  color: unknown,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'circle',
        source,
        paint: {
          'circle-color': color as never,
          'circle-radius': 5,
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 1,
        },
      } as never);
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setLayerVisibility(map, id, true);
}

function setLayerVisibility(map: MapLibreMap, id: string, visible: boolean): void {
  if (!map.getLayer(id)) {
    return;
  }
  try {
    map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none');
  } catch (error) {
    console.error(`[map] не удалось переключить видимость слоя ${id}`, error);
  }
}
