import type { StyleSpecification } from 'maplibre-gl';
import { GRAPH_PAPER_PATTERN } from './icons';

/** Доступные подложки: обесцвеченный OSM, миллиметровка, без подложки. */
export type BasemapId = 'osm' | 'grid' | 'none';

/** Нейтральный офлайн-стиль: используется, если подложка не найдена. */
export function blankStyle(color = '#ffffff'): StyleSpecification {
  return {
    version: 8,
    sources: {},
    layers: [
      {
        id: 'background',
        type: 'background',
        paint: { 'background-color': color },
      },
    ],
  };
}

/**
 * Чертёжная бумага: белый фон с миллиметровой сеткой. Паттерн добавляется
 * в карту (`addOverlayIcons`) и тайлится в экранных пикселях.
 */
export function gridPaperStyle(): StyleSpecification {
  return {
    version: 8,
    sources: {},
    layers: [
      {
        id: 'background',
        type: 'background',
        paint: {
          'background-color': '#ffffff',
          'background-pattern': GRAPH_PAPER_PATTERN,
        },
      },
    ],
  };
}

async function exists(url: string): Promise<boolean> {
  try {
    const response = await fetch(url, { method: 'HEAD' });
    return response.ok;
  } catch {
    return false;
  }
}

/**
 * Локальная офлайн-подложка (Москва + область). Приоритет:
 * 1) явный VITE_BASEMAP_STYLE_URL;
 * 2) полный экстракт `/basemap/moscow.pmtiles` (готовится скриптом);
 * 3) placeholder `/basemap/placeholder.pmtiles`;
 * 4) нейтральный фон.
 * «grid» — миллиметровка, «none» — чистый белый фон (без сети).
 */
export async function resolveBasemapStyle(id: BasemapId = 'osm'): Promise<StyleSpecification | string> {
  if (id === 'none') {
    return blankStyle('#ffffff');
  }
  if (id === 'grid') {
    return gridPaperStyle();
  }
  const explicit = (import.meta.env.VITE_BASEMAP_STYLE_URL as string | undefined)?.trim();
  if (explicit) {
    return explicit;
  }
  if (await exists('/basemap/moscow.pmtiles')) {
    return '/basemap/style.json';
  }
  if (await exists('/basemap/placeholder.pmtiles')) {
    return '/basemap/style-placeholder.json';
  }
  return blankStyle('#ffffff');
}
