import { describe, expect, it } from 'vitest';
import { parseFeatureCollection } from './types';

describe('parseFeatureCollection', () => {
  it('accepts a valid FeatureCollection', () => {
    const collection = parseFeatureCollection({
      type: 'FeatureCollection',
      features: [{ type: 'Feature', geometry: null, properties: { object_type: 'variant_summary' } }],
    });
    expect(collection.features).toHaveLength(1);
  });

  it('rejects other objects', () => {
    expect(() => parseFeatureCollection({ type: 'Feature' })).toThrow();
  });
});
