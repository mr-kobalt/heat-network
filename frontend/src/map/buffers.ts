import buffer from '@turf/buffer';
import difference from '@turf/difference';
import type { FeatureCollection, GeoFeature } from '../types';
import { markOksTargets } from './geometry';
import { restrictionBufferMeters } from './restrictions';

export const EMPTY_COLLECTION: FeatureCollection = { type: 'FeatureCollection', features: [] };

function isAreal(geometry: GeoFeature['geometry']): boolean {
  return geometry?.type === 'Polygon' || geometry?.type === 'MultiPolygon';
}

/**
 * Зоны минимального горизонтального расстояния вокруг ограничений
 * (таблица 2 ТП v2). Буферы строятся в метрах через @turf/buffer. Для
 * полигональных ограничений из буфера вычитается исходная геометрия
 * (@turf/difference), чтобы залить только кайму по краям, а не сам объект.
 * Полигоны ОКС с точкой подключения помечаются `is_target`.
 */
export function buildRestrictionBuffers(input: FeatureCollection | null): FeatureCollection {
  const out: GeoFeature[] = [];
  if (!input) {
    return EMPTY_COLLECTION;
  }
  const connectionPoints = input.features.filter(
    (feature) => feature.properties.object_type === 'oks_connection_point',
  );
  const restrictions = input.features.filter(
    (feature) => feature.properties.object_type === 'restriction' && feature.geometry,
  );
  markOksTargets(restrictions, connectionPoints).forEach((feature) => {
    const meters = restrictionBufferMeters(feature.properties.restriction_type);
    try {
      const buffered = buffer(
        feature as unknown as Parameters<typeof buffer>[0],
        meters,
        { units: 'meters' },
      ) as unknown as GeoFeature | FeatureCollection;
      const pieces = buffered.type === 'FeatureCollection' ? buffered.features : [buffered];
      pieces.forEach((piece) => {
        if (!piece.geometry) {
          return;
        }
        let geometry = piece.geometry;
        if (isAreal(feature.geometry)) {
          try {
            // @turf/difference v7 принимает FeatureCollection: буфер минус объект.
            const result = difference({
              type: 'FeatureCollection',
              features: [piece, feature],
            } as never) as unknown as GeoFeature | FeatureCollection | null;
            const ring = result
              ? (result.type === 'FeatureCollection' ? result.features[0] : result)
              : null;
            if (ring?.geometry) {
              geometry = ring.geometry;
            }
          } catch (error) {
            console.warn('[map] не удалось вырезать объект из зоны', feature.properties.id, error);
          }
        }
        out.push({
          type: 'Feature',
          geometry,
          properties: { ...feature.properties, is_buffer: true },
        });
      });
    } catch (error) {
      console.warn('[map] не удалось построить зону ограничения', feature.properties.id, error);
    }
  });
  return { type: 'FeatureCollection', features: out };
}
