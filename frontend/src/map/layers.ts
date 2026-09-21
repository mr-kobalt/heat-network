import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl';
import { LngLatBounds } from 'maplibre-gl';
import { FeatureCollection, GeoFeature } from '../types';
import type { LayerKey } from '../store';
import {
  DIAMETER_COLOR,
  DIAMETER_WIDTH,
  EXISTING_DIAMETER_COLOR,
  EXISTING_DIAMETER_WIDTH,
  PIPE_WIDTH_HOVER,
  connectionPointColor,
  hoverOpacity,
  hoverRadius,
  hoverWidth,
  linePaint,
  restrictionFillColor,
} from './paint';
import { markOksTargets } from './geometry';
import { CHAMBER_EXISTING_ICON, CHAMBER_NEW_ICON } from './icons';
import { SOURCE_COLOR, TECHNICAL_NODE_COLOR, ZONE_COLOR, ZONE_PATTERN } from './visuals';

export { CHAMBER_EXISTING_COLOR, CHAMBER_NEW_COLOR } from './visuals';

export interface OverlayData {
  input: FeatureCollection | null;
  result: FeatureCollection | null;
  activeVariant: string | null;
  visibility: Record<LayerKey, boolean>;
  /** Зоны минимальных горизонтальных расстояний вокруг ограничений. */
  buffers: FeatureCollection;
  /** Реальная ширина пары труб вместо пропорциональной Ду. */
  realPipeScale: boolean;
}

/** Ширина линии существующей сети для текущего режима (с hover-акцентом). */
function existingPipeWidth(real: boolean): unknown {
  return real ? PIPE_WIDTH_HOVER : hoverWidth(EXISTING_DIAMETER_WIDTH);
}

/** Ширина линии новой сети для текущего режима (с hover-акцентом). */
function newPipeWidth(real: boolean): unknown {
  return real ? PIPE_WIDTH_HOVER : hoverWidth(DIAMETER_WIDTH);
}

function features(collection: FeatureCollection | null, predicate: (f: GeoFeature) => boolean): GeoFeature[] {
  return collection ? collection.features.filter(predicate) : [];
}

function collection(list: GeoFeature[]): FeatureCollection {
  return { type: 'FeatureCollection', features: list };
}

/** ID неподключённых точек ОКС для активного варианта (из variant_summary). */
function unconnectedOksIds(result: FeatureCollection | null, variant: string | null): Set<string> {
  const summary = result?.features.find(
    (f) => f.properties.object_type === 'variant_summary'
      && (variant === null || String(f.properties.variant_id) === String(variant)),
  );
  const ids = (summary?.properties.unconnected_oks_ids as Array<string | number> | undefined) ?? [];
  return new Set(ids.map((id) => String(id)));
}

/** Проставляет состояние точки подключения (`connection_status`). */
function markConnectionPoints(connectionPoints: GeoFeature[], unconnected: Set<string>): GeoFeature[] {
  return connectionPoints.map((feature) => ({
    ...feature,
    properties: {
      ...feature.properties,
      connection_status: unconnected.has(String(feature.properties.id)) ? 'unconnected' : 'connected',
    },
  }));
}

export function updateOverlays(map: MapLibreMap, data: OverlayData): void {
  const variant = data.activeVariant;
  const input = data.input;
  const result = data.result;
  const visibility = data.visibility;
  const real = data.realPipeScale;

  const rawConnectionPoints = features(input, (f) => f.properties.object_type === 'oks_connection_point');
  const hasResult = Boolean(result);
  const unconnected = unconnectedOksIds(result, variant);
  const connectionPoints = hasResult
    ? markConnectionPoints(rawConnectionPoints, unconnected)
    : rawConnectionPoints.map((f) => ({
        ...f,
        properties: { ...f.properties, connection_status: 'pending' },
      }));

  setSource(
    map,
    'in-network',
    collection(features(input, (f) => f.properties.object_type === 'heat_network')),
  );
  setSource(
    map,
    'in-restrictions',
    collection(
      markOksTargets(
        features(input, (f) => f.properties.object_type === 'restriction'),
        rawConnectionPoints,
      ),
    ),
  );
  setSource(map, 'in-buffers', data.buffers);
  setSource(map, 'in-chambers', collection(features(input, (f) => f.properties.object_type === 'heat_chamber')));
  setSource(map, 'in-source', collection(features(input, (f) => f.properties.object_type === 'source')));
  setSource(map, 'in-cp', collection(connectionPoints));

  const resultFeatures = (predicate: (f: GeoFeature) => boolean) =>
    collection(features(result, (f) => f.properties.variant_id === variant && predicate(f)));

  setSource(map, 'res-network', resultFeatures((f) => f.properties.object_type === 'heat_network'));
  setSource(map, 'res-chamber', resultFeatures((f) => f.properties.object_type === 'heat_chamber'));
  setSource(map, 'res-technode', resultFeatures((f) => f.properties.object_type === 'technical_node'));

  ensurePatternFill(map, 'layer-in-buffers', 'in-buffers', ZONE_PATTERN, 0.35, visibility.restrictionBuffers);
  ensureLine(
    map,
    'layer-in-buffers-outline',
    'in-buffers',
    ZONE_COLOR,
    1,
    undefined,
    visibility.restrictionBuffers,
    [2, 2],
  );
  ensureLine(
    map,
    'layer-in-network',
    'in-network',
    undefined,
    existingPipeWidth(real),
    undefined,
    visibility.existingNetwork,
  );
  ensureFill(
    map,
    'layer-in-restrictions',
    'in-restrictions',
    restrictionFillColor(),
    0.25,
    visibility.restrictions,
    0.45,
  );
  ensureLine(
    map,
    'layer-in-restrictions-outline',
    'in-restrictions',
    restrictionFillColor(),
    hoverWidth(2),
    undefined,
    visibility.restrictions,
  );
  ensureSymbol(map, 'layer-in-chambers', 'in-chambers', CHAMBER_EXISTING_ICON, 0.7, visibility.chambers);
  ensureCircle(map, 'layer-in-source', 'in-source', SOURCE_COLOR, 8, visibility.connectionPoints);
  ensureCircle(map, 'layer-in-cp', 'in-cp', connectionPointColor(), 6, visibility.connectionPoints);

  ensureLine(
    map,
    'layer-res-base',
    'res-network',
    undefined,
    newPipeWidth(real),
    ['!=', ['get', 'laying_method'], 'special'],
    visibility.newNetwork,
  );
  ensureLine(
    map,
    'layer-res-special',
    'res-network',
    DIAMETER_COLOR,
    newPipeWidth(real),
    ['==', ['get', 'laying_method'], 'special'],
    visibility.newNetwork,
    [3, 2],
  );
  ensureSymbol(map, 'layer-res-chamber', 'res-chamber', CHAMBER_NEW_ICON, 0.7, visibility.chambers);
  ensureHollowCircle(map, 'layer-res-technode', 'res-technode', TECHNICAL_NODE_COLOR, 3, visibility.technicalNodes);

  applyExistingNetworkPaint(map, real);
  applyDiameterPaint(map, real);
}

function setSource(map: MapLibreMap, id: string, data: FeatureCollection): void {
  try {
    const existing = map.getSource(id) as GeoJSONSource | undefined;
    if (existing) {
      existing.setData(data as never);
    } else {
      // generateId нужен для подсветки наведения через feature-state.
      map.addSource(id, { type: 'geojson', data: data as never, generateId: true });
    }
  } catch (error) {
    console.error(`[map] не удалось обновить источник ${id}`, error);
  }
}

function ensureLine(
  map: MapLibreMap,
  id: string,
  source: string,
  color: unknown | undefined,
  width: unknown,
  filter: unknown,
  visible: boolean,
  dasharray?: number[],
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'line',
        source,
        ...(filter !== undefined ? { filter } : {}),
        layout: { 'line-cap': 'round', 'line-join': 'round' },
        paint: linePaint(color, width, dasharray),
      } as never);
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setVisibility(map, id, visible);
}

function ensureFill(
  map: MapLibreMap,
  id: string,
  source: string,
  color: unknown,
  opacity: number,
  visible: boolean,
  hoverOpacityValue?: number,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'fill',
        source,
        paint: {
          'fill-color': color as never,
          'fill-opacity': (hoverOpacityValue === undefined
            ? opacity
            : hoverOpacity(opacity, hoverOpacityValue)) as never,
        } as never,
      });
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setVisibility(map, id, visible);
}

/** Заливка растровым паттерном (штриховка зон ограничений). */
function ensurePatternFill(
  map: MapLibreMap,
  id: string,
  source: string,
  pattern: string,
  opacity: number,
  visible: boolean,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'fill',
        source,
        paint: {
          'fill-pattern': pattern,
          'fill-opacity': opacity,
        } as never,
      });
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setVisibility(map, id, visible);
}

function ensureCircle(
  map: MapLibreMap,
  id: string,
  source: string,
  color: unknown,
  radius: number,
  visible: boolean,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'circle',
        source,
        paint: {
          'circle-color': color as never,
          'circle-radius': hoverRadius(radius) as never,
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 1,
        } as never,
      });
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setVisibility(map, id, visible);
}

/** Полый круг (условное обозначение технического узла). */
function ensureHollowCircle(
  map: MapLibreMap,
  id: string,
  source: string,
  color: string,
  radius: number,
  visible: boolean,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'circle',
        source,
        paint: {
          'circle-color': 'rgba(0, 0, 0, 0)',
          'circle-radius': hoverRadius(radius) as never,
          'circle-stroke-color': color,
          'circle-stroke-width': 2,
        } as never,
      });
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setVisibility(map, id, visible);
}

function ensureSymbol(
  map: MapLibreMap,
  id: string,
  source: string,
  icon: string,
  size: number,
  visible: boolean,
): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'symbol',
        source,
        layout: {
          'icon-image': icon,
          // icon-size — layout-свойство, feature-state в layout запрещён.
          'icon-size': size,
          'icon-allow-overlap': true,
          'icon-ignore-placement': true,
          'icon-anchor': 'center',
        },
      });
    } catch (error) {
      console.error(`[map] не удалось добавить слой ${id}`, error);
    }
  }
  setVisibility(map, id, visible);
}

function setVisibility(map: MapLibreMap, id: string, visible: boolean): void {
  if (!map.getLayer(id)) {
    return;
  }
  try {
    map.setLayoutProperty(id, 'visibility', visible ? 'visible' : 'none');
  } catch (error) {
    console.error(`[map] не удалось переключить видимость слоя ${id}`, error);
  }
}

function applyExistingNetworkPaint(map: MapLibreMap, real: boolean): void {
  if (!map.getLayer('layer-in-network')) {
    return;
  }
  try {
    map.setPaintProperty('layer-in-network', 'line-color', EXISTING_DIAMETER_COLOR as never);
    map.setPaintProperty(
      'layer-in-network',
      'line-width',
      existingPipeWidth(real) as never,
    );
  } catch (error) {
    console.error('[map] не удалось задать стиль существующей сети', error);
  }
}

function applyDiameterPaint(map: MapLibreMap, real: boolean): void {
  if (!map.getLayer('layer-res-base')) {
    return;
  }
  try {
    map.setPaintProperty('layer-res-base', 'line-color', DIAMETER_COLOR as never);
    map.setPaintProperty('layer-res-base', 'line-width', newPipeWidth(real) as never);
    if (map.getLayer('layer-res-special')) {
      map.setPaintProperty('layer-res-special', 'line-width', newPipeWidth(real) as never);
    }
  } catch (error) {
    console.error('[map] не удалось задать стиль базового слоя сети', error);
  }
}

export const OVERLAY_LAYER_IDS = [
  'layer-in-network',
  'layer-in-buffers',
  'layer-in-buffers-outline',
  'layer-in-restrictions',
  'layer-in-restrictions-outline',
  'layer-in-chambers',
  'layer-in-source',
  'layer-in-cp',
  'layer-res-base',
  'layer-res-special',
  'layer-res-chamber',
  'layer-res-technode',
];

/** Слои, объекты которых можно выбирать кликом (без вспомогательных зон). */
export const SELECTABLE_LAYER_IDS = OVERLAY_LAYER_IDS.filter((id) => !id.includes('buffers'));

export function fitToData(map: MapLibreMap, data: OverlayData): void {
  const bounds = new LngLatBounds();
  let hasCoordinates = false;
  const extend = (geometry: GeoFeature['geometry']) => {
    if (!geometry) {
      return;
    }
    collectCoordinates(geometry.coordinates, (lng, lat) => {
      bounds.extend([lng, lat]);
      hasCoordinates = true;
    });
  };
  data.result?.features.forEach((feature) => {
    if (feature.properties.variant_id === data.activeVariant) {
      extend(feature.geometry);
    }
  });
  data.input?.features.forEach((feature) => extend(feature.geometry));
  if (hasCoordinates) {
    map.fitBounds(bounds, { padding: 60, maxZoom: 18 });
  }
}

function collectCoordinates(node: unknown, visit: (lng: number, lat: number) => void): void {
  if (!Array.isArray(node)) {
    return;
  }
  if (typeof node[0] === 'number' && typeof node[1] === 'number') {
    visit(node[0], node[1]);
    return;
  }
  node.forEach((child) => collectCoordinates(child, visit));
}
