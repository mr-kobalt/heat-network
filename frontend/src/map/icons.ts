import type { Map as MapLibreMap } from 'maplibre-gl';
import { ZONE_COLOR, ZONE_PATTERN } from './visuals';

/**
 * Чистая растеризация значков для symbol-слоёв. Возвращает структуру,
 * совместимую с `map.addImage` (RGBA, ширина/высота). Без canvas — работает
 * и в юнит-тестах.
 */
export interface RasterImage {
  width: number;
  height: number;
  data: Uint8Array;
}

export const CHAMBER_EXISTING_ICON = 'chamber-existing';
export const CHAMBER_NEW_ICON = 'chamber-new';
export const GRAPH_PAPER_PATTERN = 'graph-paper';

function parseHex(hex: string): [number, number, number] {
  const raw = hex.replace('#', '');
  const full = raw.length === 3 ? raw.split('').map((c) => c + c).join('') : raw;
  return [
    Number.parseInt(full.slice(0, 2), 16),
    Number.parseInt(full.slice(2, 4), 16),
    Number.parseInt(full.slice(4, 6), 16),
  ];
}

/** Квадрат с точкой в центре (условное обозначение тепловой камеры). */
export function squareWithDotIcon(
  stroke: string,
  dot: string,
  size = 24,
  strokeWidth = 3,
): RasterImage {
  const data = new Uint8Array(size * size * 4);
  const [sr, sg, sb] = parseHex(stroke);
  const [dr, dg, db] = parseHex(dot);
  const lo = strokeWidth;
  const hi = size - 1 - strokeWidth;
  const center = (size - 1) / 2;
  const dotRadius = size * 0.13;

  const put = (x: number, y: number, r: number, g: number, b: number) => {
    const index = (y * size + x) * 4;
    data[index] = r;
    data[index + 1] = g;
    data[index + 2] = b;
    data[index + 3] = 255;
  };

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const nearEdge =
        x >= lo
        && x <= hi
        && y >= lo
        && y <= hi
        && (x < lo + strokeWidth || x > hi - strokeWidth || y < lo + strokeWidth || y > hi - strokeWidth);
      const dx = x - center;
      const dy = y - center;
      if (dx * dx + dy * dy <= dotRadius * dotRadius) {
        put(x, y, dr, dg, db);
      } else if (nearEdge) {
        put(x, y, sr, sg, sb);
      }
    }
  }
  return { width: size, height: size, data };
}

/** Диагональные полосы для заливки зон минимальных расстояний. */
export function stripePattern(color: string, size = 8, thickness = 2): RasterImage {
  const data = new Uint8Array(size * size * 4);
  const [r, g, b] = parseHex(color);
  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      if ((x + y) % size >= thickness) {
        continue;
      }
      const index = (y * size + x) * 4;
      data[index] = r;
      data[index + 1] = g;
      data[index + 2] = b;
      data[index + 3] = 255;
    }
  }
  return { width: size, height: size, data };
}

/**
 * Миллиметровая чертёжная бумага: белый тайл с тонкой сеткой каждые 10 px и
 * утолщённой каждые 50 px. Используется как `background-pattern` — тайлится
 * в экранных пикселях, поэтому не «плывёт» при зуме.
 */
export function graphPaperPattern(options?: {
  size?: number;
  minor?: number;
  major?: number;
  minorColor?: string;
  majorColor?: string;
}): RasterImage {
  const size = options?.size ?? 100;
  const minor = options?.minor ?? 10;
  const major = options?.major ?? 50;
  const [nr, ng, nb] = parseHex(options?.minorColor ?? '#e2eaf5');
  const [mr, mg, mb] = parseHex(options?.majorColor ?? '#c2d2e8');
  const data = new Uint8Array(size * size * 4);

  const put = (x: number, y: number, r: number, g: number, b: number) => {
    const index = (y * size + x) * 4;
    data[index] = r;
    data[index + 1] = g;
    data[index + 2] = b;
    data[index + 3] = 255;
  };

  for (let y = 0; y < size; y += 1) {
    for (let x = 0; x < size; x += 1) {
      const onMajor = x % major === 0 || y % major === 0;
      const onMinor = x % minor === 0 || y % minor === 0;
      if (onMajor) {
        put(x, y, mr, mg, mb);
      } else if (onMinor) {
        put(x, y, nr, ng, nb);
      } else {
        put(x, y, 255, 255, 255);
      }
    }
  }
  return { width: size, height: size, data };
}

/** Изображение по id (камеры, штриховка зон, миллиметровка). */
export function overlayIconById(
  id: string,
  existingColor: string,
  newColor: string,
): RasterImage | undefined {
  switch (id) {
    case CHAMBER_EXISTING_ICON:
      return squareWithDotIcon(existingColor, existingColor);
    case CHAMBER_NEW_ICON:
      return squareWithDotIcon(newColor, newColor);
    case ZONE_PATTERN:
      return stripePattern(ZONE_COLOR);
    case GRAPH_PAPER_PATTERN:
      return graphPaperPattern();
    default:
      return undefined;
  }
}

/** Набор значков визуализатора: камеры, штриховка зон и подложка-сетка. */
export function overlayIcons(existingColor: string, newColor: string): Record<string, RasterImage> {
  return {
    [CHAMBER_EXISTING_ICON]: squareWithDotIcon(existingColor, existingColor),
    [CHAMBER_NEW_ICON]: squareWithDotIcon(newColor, newColor),
    [ZONE_PATTERN]: stripePattern(ZONE_COLOR),
    [GRAPH_PAPER_PATTERN]: graphPaperPattern(),
  };
}

export function addOverlayIcons(map: MapLibreMap, existingColor: string, newColor: string): void {
  const icons = overlayIcons(existingColor, newColor);
  Object.entries(icons).forEach(([id, image]) => {
    if (!map.hasImage(id)) {
      map.addImage(id, image as never);
    }
  });
}
