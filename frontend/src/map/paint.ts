/**
 * Чистые построители paint-выражений MapLibre (без зависимости от maplibre-gl,
 * чтобы их можно было покрыть unit-тестами).
 */

import {
  CONNECTION_POINT_COLORS,
  DEFAULT_RESTRICTION_STYLE,
  OKS_TARGET_COLOR,
  RESTRICTION_INACTIVE_COLOR,
  RESTRICTION_STYLES,
} from './restrictions';

/**
 * Условные диаметры по таблице 1 ТП v2 (`docs/02-domain/calculation-rules.md`):
 * 18 значений Ду, «ширина пары» в метрах и цвета — viridis (новая сеть) и
 * viridis в чёрно-белом исполнении (существующая сеть; тёмный — больше Ду).
 */
export interface DiameterRow {
  diameter: number;
  /** Ширина пары трубопроводов (подающий + обратный), м. */
  pairWidth: number;
  color: string;
  grayColor: string;
}

export const DIAMETER_TABLE: ReadonlyArray<DiameterRow> = [
  { diameter: 50, pairWidth: 0.4, color: '#470155', grayColor: '#dddddd' },
  { diameter: 65, pairWidth: 0.43, color: '#481669', grayColor: '#d2d2d2' },
  { diameter: 80, pairWidth: 0.47, color: '#472a79', grayColor: '#c6c6c6' },
  { diameter: 100, pairWidth: 0.51, color: '#443d85', grayColor: '#bababa' },
  { diameter: 125, pairWidth: 0.6, color: '#3f4e8b', grayColor: '#adadad' },
  { diameter: 150, pairWidth: 0.65, color: '#375e8e', grayColor: '#9f9f9f' },
  { diameter: 200, pairWidth: 0.88, color: '#2e6d8e', grayColor: '#939393' },
  { diameter: 250, pairWidth: 1.05, color: '#267b8e', grayColor: '#878787' },
  { diameter: 300, pairWidth: 1.15, color: '#21898c', grayColor: '#7d7d7d' },
  { diameter: 400, pairWidth: 1.37, color: '#1f978a', grayColor: '#737373' },
  { diameter: 500, pairWidth: 1.67, color: '#23a585', grayColor: '#6a6a6a' },
  { diameter: 600, pairWidth: 1.85, color: '#30b37d', grayColor: '#626262' },
  { diameter: 700, pairWidth: 2.05, color: '#44bf6f', grayColor: '#595959' },
  { diameter: 800, pairWidth: 2.25, color: '#62cb5c', grayColor: '#4f4f4f' },
  { diameter: 900, pairWidth: 2.45, color: '#87d545', grayColor: '#444444' },
  { diameter: 1000, pairWidth: 2.65, color: '#b1dc2d', grayColor: '#363636' },
  { diameter: 1200, pairWidth: 3.1, color: '#dae21d', grayColor: '#272727' },
  { diameter: 1400, pairWidth: 3.45, color: '#fce721', grayColor: '#161616' },
];

const UNKNOWN_DIAMETER_COLOR = '#64748b';

/** Цвет по Ду: viridis для новой сети, grayscale-viridis для существующей. */
function diameterColorExpression(existing: boolean): unknown {
  const match: unknown[] = ['match', ['get', 'diameter']];
  DIAMETER_TABLE.forEach((row) => {
    match.push(row.diameter, existing ? row.grayColor : row.color);
  });
  match.push(UNKNOWN_DIAMETER_COLOR);
  return match;
}

export const DIAMETER_COLOR: unknown = diameterColorExpression(false);
export const EXISTING_DIAMETER_COLOR: unknown = diameterColorExpression(true);

/**
 * Пропорциональная ширина линии (px) по Ду — режим по умолчанию
 * (отключённый переключатель «Реальный масштаб труб»).
 */
function diameterWidthExpression(
  stops: ReadonlyArray<[number, number]>,
): unknown {
  return [
    'interpolate',
    ['linear'],
    ['coalesce', ['get', 'diameter'], 100],
    ...stops.flatMap(([diameter, width]) => [diameter, width]),
  ];
}

export const DIAMETER_WIDTH: unknown = diameterWidthExpression([
  [50, 1.5],
  [300, 3],
  [800, 5],
  [1400, 8],
]);

export const EXISTING_DIAMETER_WIDTH: unknown = diameterWidthExpression([
  [50, 1],
  [300, 2],
  [800, 3.5],
  [1400, 5],
]);

/** Ширина пары по Ду (м) как выражение MapLibre. */
function diameterPairWidthExpression(): unknown {
  const match: unknown[] = ['match', ['get', 'diameter']];
  DIAMETER_TABLE.forEach((row) => {
    match.push(row.diameter, row.pairWidth);
  });
  match.push(0.5);
  return match;
}

/** Опорная широта региона (Москва) для перевода метров в пиксели. */
const REFERENCE_LATITUDE = 55.7;

function metersPerPixel(zoom: number): number {
  return (156543.03392 * Math.cos((REFERENCE_LATITUDE * Math.PI) / 180)) / 2 ** zoom;
}

/** Нижний порог, чтобы линию можно было навести/кликнуть (0 — чистый масштаб). */
export const MIN_PIPE_WIDTH_PX = 1;

/** Реальная ширина пары (м) → пиксели при заданном масштабе. */
function pipeWidthAt(zoom: number): unknown {
  return ['max', MIN_PIPE_WIDTH_PX, ['/', diameterPairWidthExpression(), metersPerPixel(zoom)]];
}

/**
 * Реальная ширина пары в пикселях. «zoom» допустим только как вход
 * top-level interpolate, поэтому переводим метры в пиксели в каждой опорной
 * точке зума, а hover-акцент — внутри значений (а не поверх интерполяции).
 */
function pipeWidthExpression(hover: boolean): unknown {
  const at = (zoom: number) => {
    const base = pipeWidthAt(zoom);
    if (!hover) {
      return base;
    }
    return ['case', ['boolean', ['feature-state', 'hover'], false], ['+', base, 2], base];
  };
  return [
    'interpolate',
    ['exponential', 2],
    ['zoom'],
    8,
    at(8),
    12,
    at(12),
    16,
    at(16),
    20,
    at(20),
  ];
}

/** Ширина линии сети без акцента (реальный масштаб пары). */
export const PIPE_WIDTH: unknown = pipeWidthExpression(false);

/** То же с акцентом наведения. */
export const PIPE_WIDTH_HOVER: unknown = pipeWidthExpression(true);

/**
 * Цвет заливки ограничения: подключаемый ОКС (есть точка внутри) — отдельно,
 * прочие ОКС — приглушённо, остальные типы — по таблице 2.
 */
export function restrictionFillColor(): unknown {
  const match: unknown[] = ['match', ['get', 'restriction_type']];
  Object.entries(RESTRICTION_STYLES).forEach(([type, style]) => {
    match.push(type, style.color);
  });
  match.push(DEFAULT_RESTRICTION_STYLE.color);
  return [
    'case',
    ['==', ['get', 'restriction_type'], 'oks'],
    ['case', ['==', ['get', 'is_target'], true], OKS_TARGET_COLOR, RESTRICTION_INACTIVE_COLOR],
    match,
  ];
}

/** Цвет точки подключения по состоянию (подключена / нет / нет результата). */
export function connectionPointColor(): unknown {
  return [
    'match',
    ['get', 'connection_status'],
    'connected',
    CONNECTION_POINT_COLORS.connected,
    'unconnected',
    CONNECTION_POINT_COLORS.unconnected,
    CONNECTION_POINT_COLORS.pending,
  ];
}

/**
 * Акценты наведения на кликабельный объект: меняют свойство, пока
 * `feature-state.hover` равно true (см. `generateId` у источников).
 */
export function hoverWidth(base: unknown, delta = 2): unknown {
  return ['case', ['boolean', ['feature-state', 'hover'], false], ['+', base, delta], base];
}

export function hoverRadius(base: number, delta = 2): unknown {
  return ['case', ['boolean', ['feature-state', 'hover'], false], base + delta, base];
}

export function hoverOpacity(base: number, hover: number): unknown {
  return ['case', ['boolean', ['feature-state', 'hover'], false], hover, base];
}

/**
 * Собирает paint для линии, не добавляя ключи со значением undefined
 * (MapLibre не принимает undefined в качестве значения свойства).
 */
export function linePaint(
  color: unknown | undefined,
  width: number | unknown,
  dasharray?: number[],
): Record<string, unknown> {
  const paint: Record<string, unknown> = { 'line-width': width };
  if (color !== undefined) {
    paint['line-color'] = color;
  }
  if (dasharray !== undefined) {
    paint['line-dasharray'] = dasharray;
  }
  return paint;
}
