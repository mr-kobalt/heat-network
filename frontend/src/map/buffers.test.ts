import { describe, expect, it } from 'vitest';
import { buildRestrictionBuffers } from './buffers';
import { pointInPolygon } from './geometry';
import type { FeatureCollection, GeoFeature } from '../types';

function feature(properties: Record<string, unknown>, geometry: unknown): GeoFeature {
  return { type: 'Feature', geometry: geometry as GeoFeature['geometry'], properties };
}

const square = [
  [0, 0],
  [0.0001, 0],
  [0.0001, 0.0001],
  [0, 0.0001],
  [0, 0],
];

function collection(features: GeoFeature[]): FeatureCollection {
  return { type: 'FeatureCollection', features };
}

describe('buildRestrictionBuffers', () => {
  it('returns empty for missing input', () => {
    expect(buildRestrictionBuffers(null).features).toHaveLength(0);
  });

  it('buffers only restrictions', () => {
    const input = collection([
      feature({ id: 1, object_type: 'heat_network', diameter: 100 }, { type: 'LineString', coordinates: square }),
      feature({ id: 2, object_type: 'restriction', restriction_type: 'railway' }, { type: 'LineString', coordinates: square }),
    ]);
    const buffers = buildRestrictionBuffers(input);
    expect(buffers.features).toHaveLength(1);
    expect(buffers.features[0].properties.is_buffer).toBe(true);
    expect(['Polygon', 'MultiPolygon']).toContain(buffers.features[0].geometry?.type);
  });

  it('keeps is_target on zones of OKS with a connection point', () => {
    const input = collection([
      feature({ id: 'oks1', object_type: 'restriction', restriction_type: 'oks' }, { type: 'Polygon', coordinates: [square] }),
      feature({ id: 'cp1', object_type: 'oks_connection_point', flow_tph: 10 }, { type: 'Point', coordinates: [0.00005, 0.00005] }),
    ]);
    const buffers = buildRestrictionBuffers(input);
    expect(buffers.features).toHaveLength(1);
    expect(buffers.features[0].properties.is_target).toBe(true);
  });

  it('subtracts the polygon interior so only the edge band is filled', () => {
    const input = collection([
      feature({ id: 'oks1', object_type: 'restriction', restriction_type: 'oks' }, { type: 'Polygon', coordinates: [square] }),
    ]);
    const ring = buildRestrictionBuffers(input).features[0];
    expect(['Polygon', 'MultiPolygon']).toContain(ring.geometry?.type);
    // Центр исходного полигона не заливается, точка у самой границы — заливается.
    expect(pointInPolygon([0.00005, 0.00005], ring.geometry)).toBe(false);
    expect(pointInPolygon([0.00012, 0.00005], ring.geometry)).toBe(true);
  });
});
