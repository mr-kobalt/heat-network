/**
 * Чистые построители paint-выражений MapLibre (без зависимости от maplibre-gl,
 * чтобы их можно было покрыть unit-тестами).
 */

/** Цвет участка по условному диаметру (мм). */
export const DIAMETER_COLOR: unknown = [
  'interpolate',
  ['linear'],
  ['coalesce', ['get', 'diameter'], 100],
  50,
  '#2563eb',
  200,
  '#0ea5e9',
  400,
  '#16a34a',
  700,
  '#f59e0b',
  1000,
  '#ea580c',
  1400,
  '#dc2626',
];

/** Толщина линии участка по условному диаметру (мм). */
export const DIAMETER_WIDTH: unknown = [
  'interpolate',
  ['linear'],
  ['coalesce', ['get', 'diameter'], 100],
  50,
  1.5,
  300,
  3,
  800,
  5,
  1400,
  8,
];

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
