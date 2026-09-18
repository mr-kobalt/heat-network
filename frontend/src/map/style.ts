import type { StyleSpecification } from 'maplibre-gl';

/**
 * Нейтральный офлайн-стиль по умолчанию. Если задан VITE_BASEMAP_STYLE_URL,
 * используется внешний локальный стиль (например, Protomaps PMTiles).
 */
export function blankStyle(): StyleSpecification {
  return {
    version: 8,
    sources: {},
    layers: [
      {
        id: 'background',
        type: 'background',
        paint: { 'background-color': '#eef2f6' },
      },
    ],
  };
}

export function basemapStyleUrl(): string | undefined {
  const url = import.meta.env.VITE_BASEMAP_STYLE_URL as string | undefined;
  return url && url.trim().length > 0 ? url : undefined;
}
