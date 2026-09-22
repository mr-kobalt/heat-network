import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl';
import { LngLatBounds } from 'maplibre-gl';
import { FeatureCollection, GeoFeature, GridMask } from '../types';
import { RESULT_STAGE } from '../store';
import { OverlayData, fitToData, updateOverlays } from './layers';
import { decodeBits } from './gridMask';

/**
 * Отрисовка промежуточных этапов алгоритма (ADR-0036). Когда активна вкладка
 * этапа — результат скрывается, поверх контекста накладываются объекты этапа;
 * для этапа «сетка» дополнительно строится растровое изображение маски.
 */

export interface StageOverlayData extends OverlayData {
  activeStage: string;
  stageFeatures: FeatureCollection | null;
  gridMask: GridMask | null;
}

const STAGE_SOURCES = ['stage-polygons', 'stage-lines', 'stage-points', 'stage-grid'];
const STAGE_LAYERS = [
  'layer-stage-polygons',
  'layer-stage-polygons-outline',
  'layer-stage-lines',
  'layer-stage-points',
  'layer-stage-grid',
];

interface StageColors {
  polygon?: string;
  line?: string;
  point?: string;
}

function stageColors(kind: string): StageColors {
  switch (kind) {
    case 'network':
      return { line: '#64748b', point: '#64748b' };
    case 'obstacles':
      return { polygon: '#dc2626' };
    case 'special':
      return { polygon: '#f59e0b' };
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
      paint: { 'raster-opacity': 0.75, 'raster-fade-duration': 0 },
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
  color: string,
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
  color: string,
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
