import { describe, expect, it } from 'vitest';
import { pointInPolygon, pointInRing } from './geometry';

const square: [number, number][] = [
  [0, 0],
  [10, 0],
  [10, 10],
  [0, 10],
  [0, 0],
];

describe('pointInRing', () => {
  it('detects points inside and outside', () => {
    expect(pointInRing([5, 5], square)).toBe(true);
    expect(pointInRing([15, 5], square)).toBe(false);
  });

  it('ignores malformed rings', () => {
    expect(pointInRing([5, 5], null)).toBe(false);
    expect(pointInRing([5, 5], [[0, 0], [1, 1]])).toBe(false);
  });
});

describe('pointInPolygon', () => {
  it('handles polygons', () => {
    expect(pointInPolygon([5, 5], { type: 'Polygon', coordinates: [square] })).toBe(true);
    expect(pointInPolygon([50, 50], { type: 'Polygon', coordinates: [square] })).toBe(false);
  });

  it('respects holes', () => {
    const hole: [number, number][] = [
      [4, 4],
      [6, 4],
      [6, 6],
      [4, 6],
      [4, 4],
    ];
    const geometry = { type: 'Polygon', coordinates: [square, hole] };
    expect(pointInPolygon([1, 1], geometry)).toBe(true);
    expect(pointInPolygon([5, 5], geometry)).toBe(false);
  });

  it('handles multipolygons and non-areal geometries', () => {
    const geometry = {
      type: 'MultiPolygon',
      coordinates: [
        [square],
        [[[20, 20], [30, 20], [30, 30], [20, 30], [20, 20]]],
      ],
    };
    expect(pointInPolygon([25, 25], geometry)).toBe(true);
    expect(pointInPolygon([5, 5], geometry)).toBe(true);
    expect(pointInPolygon([5, 5], { type: 'LineString', coordinates: [[0, 0], [1, 1]] })).toBe(false);
    expect(pointInPolygon([5, 5], null)).toBe(false);
  });
});
