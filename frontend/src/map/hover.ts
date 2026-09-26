import type { Map as MapLibreMap } from 'maplibre-gl';
import type { GeoFeature } from '../types';
import { featureAnchor } from '../components/nodeGraph';
import { SELECTABLE_LAYER_IDS } from './layers';

let previewKey: { source: string; id: string | number } | null = null;

/**
 * Подсветка объекта «как при наведении» (ADR-0058): находим отрисованную фичу
 * под якорем объекта и включаем ей feature-state `hover` — работает тот же
 * paint-стиль, что и у наведения мышью.
 */
export function applyHoverPreview(map: MapLibreMap, feature: GeoFeature | null): void {
  clearHoverPreview(map);
  if (!feature) {
    return;
  }
  const anchor = featureAnchor(feature);
  if (!anchor) {
    return;
  }
  const layers = SELECTABLE_LAYER_IDS.filter((id) => Boolean(map.getLayer(id)));
  if (layers.length === 0) {
    return;
  }
  const found = map.queryRenderedFeatures(map.project(anchor), { layers });
  if (found.length === 0) {
    return;
  }
  const hit = found[0];
  if (hit.source === undefined || hit.id === undefined) {
    return;
  }
  previewKey = { source: hit.source, id: hit.id as string | number };
  map.setFeatureState(previewKey, { hover: true });
}

export function clearHoverPreview(map: MapLibreMap): void {
  if (!previewKey) {
    return;
  }
  try {
    map.setFeatureState(previewKey, { hover: false });
  } catch {
    // источник мог исчезнуть (смена подложки) — состояние уже сброшено
  }
  previewKey = null;
}
