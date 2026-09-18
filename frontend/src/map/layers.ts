import type { GeoJSONSource, Map as MapLibreMap } from 'maplibre-gl';
import { LngLatBounds } from 'maplibre-gl';
import { FeatureCollection, GeoFeature } from '../types';
import type { LayerKey } from '../store';
import { DIAMETER_COLOR, DIAMETER_WIDTH, linePaint } from './paint';

const SPECIAL_RESTRICTIONS = new Set([
  'road',
  'tram_tracks',
  'railway',
  'gas_pipeline',
  'power_cable',
  'heat_network',
]);

export interface OverlayData {
  input: FeatureCollection | null;
  result: FeatureCollection | null;
  activeVariant: string | null;
  visibility: Record<LayerKey, boolean>;
}

function features(collection: FeatureCollection | null, predicate: (f: GeoFeature) => boolean): GeoFeature[] {
  return collection ? collection.features.filter(predicate) : [];
}

function collection(list: GeoFeature[]): FeatureCollection {
  return { type: 'FeatureCollection', features: list };
}

export function updateOverlays(map: MapLibreMap, data: OverlayData): void {
  const variant = data.activeVariant;
  const input = data.input;
  const result = data.result;

  setSource(map, 'in-network', collection(features(input, (f) => f.properties.object_type === 'heat_network')));
  setSource(map, 'in-restrictions', collection(features(input, (f) => f.properties.object_type === 'restriction')));
  setSource(map, 'in-chambers', collection(features(input, (f) => f.properties.object_type === 'heat_chamber')));
  setSource(map, 'in-source', collection(features(input, (f) => f.properties.object_type === 'source')));
  setSource(map, 'in-cp', collection(features(input, (f) => f.properties.object_type === 'oks_connection_point')));

  const resultFeatures = (predicate: (f: GeoFeature) => boolean) =>
    collection(features(result, (f) => f.properties.variant_id === variant && predicate(f)));

  setSource(map, 'res-network', resultFeatures((f) => f.properties.object_type === 'heat_network'));
  setSource(map, 'res-recon', resultFeatures((f) => f.properties.object_type === 'heat_network_reconstruction'));
  setSource(map, 'res-tiein', resultFeatures((f) => f.properties.object_type === 'tie_in'));
  setSource(map, 'res-chamber', resultFeatures((f) => f.properties.object_type === 'heat_chamber'));
  setSource(map, 'res-technode', resultFeatures((f) => f.properties.object_type === 'technical_node'));

  ensureLine(map, 'layer-in-network', 'in-network', '#94a3b8', 2, undefined, data.visibility.existingNetwork);
  ensureFill(map, 'layer-in-restrictions', 'in-restrictions', data.visibility.restrictions);
  ensureCircle(map, 'layer-in-chambers', 'in-chambers', '#475569', 5, data.visibility.chambers);
  ensureCircle(map, 'layer-in-source', 'in-source', '#047857', 8, data.visibility.connectionPoints);
  ensureCircle(map, 'layer-in-cp', 'in-cp', '#0284c7', 5, data.visibility.connectionPoints);

  ensureLine(
    map,
    'layer-res-base',
    'res-network',
    undefined,
    3,
    ['!=', ['get', 'laying_method'], 'special'],
    data.visibility.newNetwork,
  );
  ensureLine(
    map,
    'layer-res-special',
    'res-network',
    '#f97316',
    4,
    ['==', ['get', 'laying_method'], 'special'],
    data.visibility.newNetwork,
    [3, 2],
  );
  ensureLine(
    map,
    'layer-res-recon',
    'res-recon',
    '#a855f7',
    4,
    undefined,
    data.visibility.reconstruction,
    [2, 1.5],
  );
  ensureCircle(map, 'layer-res-tiein', 'res-tiein', '#dc2626', 6, data.visibility.tieIns);
  ensureCircle(map, 'layer-res-chamber', 'res-chamber', '#059669', 7, data.visibility.chambers);
  ensureCircle(map, 'layer-res-technode', 'res-technode', '#ca8a04', 3, data.visibility.technicalNodes);

  applyDiameterPaint(map);
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
    console.error(`[map] не удалось обновить источник ${id}`, error);
  }
}

function ensureLine(
  map: MapLibreMap,
  id: string,
  source: string,
  color: unknown | undefined,
  width: number,
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

function ensureFill(map: MapLibreMap, id: string, source: string, visible: boolean): void {
  if (!map.getLayer(id)) {
    try {
      map.addLayer({
        id,
        type: 'fill',
        source,
        paint: {
          'fill-color': [
            'case',
            ['in', ['get', 'restriction_type'], ['literal', Array.from(SPECIAL_RESTRICTIONS)]],
            '#fb923c',
            '#ef4444',
          ],
          'fill-opacity': 0.25,
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
          'circle-color': color,
          'circle-radius': radius,
          'circle-stroke-color': '#ffffff',
          'circle-stroke-width': 1,
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

function applyDiameterPaint(map: MapLibreMap): void {
  if (!map.getLayer('layer-res-base')) {
    return;
  }
  try {
    map.setPaintProperty('layer-res-base', 'line-color', DIAMETER_COLOR as never);
    map.setPaintProperty('layer-res-base', 'line-width', DIAMETER_WIDTH as never);
  } catch (error) {
    console.error('[map] не удалось задать стиль базового слоя сети', error);
  }
}

export const OVERLAY_LAYER_IDS = [
  'layer-in-network',
  'layer-in-restrictions',
  'layer-in-chambers',
  'layer-in-source',
  'layer-in-cp',
  'layer-res-base',
  'layer-res-special',
  'layer-res-recon',
  'layer-res-tiein',
  'layer-res-chamber',
  'layer-res-technode',
];

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
