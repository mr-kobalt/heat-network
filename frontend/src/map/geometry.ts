import type { GeoFeature, Geometry } from '../types';

/** Точка в кольце (ray casting); ring — массив [lng, lat, ...]. */
export function pointInRing(point: [number, number], ring: unknown): boolean {
  if (!Array.isArray(ring) || ring.length < 3) {
    return false;
  }
  const [x, y] = point;
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i, i += 1) {
    const a = ring[i] as number[];
    const b = ring[j] as number[];
    if (!Array.isArray(a) || !Array.isArray(b)) {
      continue;
    }
    const [xi, yi] = a;
    const [xj, yj] = b;
    const crosses = yi > y !== yj > y && x < ((xj - xi) * (y - yi)) / (yj - yi) + xi;
    if (crosses) {
      inside = !inside;
    }
  }
  return inside;
}

/**
 * Точка внутри полигона/мультиполигона с учётом отверстий.
 * Поддерживает только Polygon/MultiPolygon; для прочих геометрий — false.
 */
export function pointInPolygon(point: [number, number], geometry: Geometry | null | undefined): boolean {
  if (!geometry) {
    return false;
  }
  if (geometry.type === 'Polygon') {
    return pointInPolygonRings(point, geometry.coordinates as unknown[]);
  }
  if (geometry.type === 'MultiPolygon') {
    return (geometry.coordinates as unknown[]).some((polygon) =>
      pointInPolygonRings(point, polygon as unknown[]),
    );
  }
  return false;
}

/** Координаты точечного объекта (или null). */
export function pointCoordinates(geometry: Geometry | null | undefined): [number, number] | null {
  if (!geometry || geometry.type !== 'Point') {
    return null;
  }
  const coordinates = geometry.coordinates as unknown[];
  if (Array.isArray(coordinates) && typeof coordinates[0] === 'number' && typeof coordinates[1] === 'number') {
    return [coordinates[0], coordinates[1]];
  }
  return null;
}

/**
 * Помечает полигоны ОКС, содержащие точку подключения (`is_target`).
 * Подключаемый ОКС выделяется цветом среди прочих ограничений.
 */
export function markOksTargets(
  restrictions: GeoFeature[],
  connectionPoints: GeoFeature[],
): GeoFeature[] {
  const coordinates = connectionPoints
    .map((f) => pointCoordinates(f.geometry))
    .filter((value): value is [number, number] => value !== null);
  return restrictions.map((feature) => {
    const isTarget =
      feature.properties.restriction_type === 'oks'
      && coordinates.some((point) => pointInPolygon(point, feature.geometry));
    return isTarget ? { ...feature, properties: { ...feature.properties, is_target: true } } : feature;
  });
}

function pointInPolygonRings(point: [number, number], rings: unknown[]): boolean {
  if (!Array.isArray(rings) || rings.length === 0) {
    return false;
  }
  if (!pointInRing(point, rings[0])) {
    return false;
  }
  // Внутри внешнего кольца, но не внутри ни одного отверстия.
  for (let i = 1; i < rings.length; i += 1) {
    if (pointInRing(point, rings[i])) {
      return false;
    }
  }
  return true;
}
