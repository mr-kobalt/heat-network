import { describe, expect, it } from 'vitest';
import { validateStyleMin } from '@maplibre/maplibre-gl-style-spec';
import { DIAMETER_COLOR, DIAMETER_WIDTH, linePaint } from './paint';

function styleWith(paint: Record<string, unknown>, filter?: unknown) {
  return {
    version: 8,
    sources: {
      net: { type: 'geojson', data: { type: 'FeatureCollection', features: [] } },
    },
    layers: [
      {
        id: 'net',
        type: 'line',
        source: 'net',
        ...(filter !== undefined ? { filter } : {}),
        paint,
      },
    ],
  };
}

describe('валидация слоёв новой сети по style-spec MapLibre', () => {
  it('базовый слой (цвет и толщина по Ду) валиден', () => {
    const errors = validateStyleMin(
      styleWith(linePaint(DIAMETER_COLOR, DIAMETER_WIDTH), [
        '!=',
        ['get', 'laying_method'],
        'special',
      ]) as never,
    );
    expect(errors).toEqual([]);
  });

  it('слой спецпрохода валиден', () => {
    const errors = validateStyleMin(
      styleWith(linePaint('#f97316', 4, [3, 2]), ['==', ['get', 'laying_method'], 'special']) as never,
    );
    expect(errors).toEqual([]);
  });

  it('слой реконструкции валиден', () => {
    const errors = validateStyleMin(styleWith(linePaint('#a855f7', 4, [2, 1.5])) as never);
    expect(errors).toEqual([]);
  });

  it('двойная обёртка выражения (прежняя ошибка) невалидна', () => {
    const errors = validateStyleMin(
      styleWith({ 'line-color': [DIAMETER_COLOR], 'line-width': 3 }) as never,
    );
    expect(errors.length).toBeGreaterThan(0);
  });
});
