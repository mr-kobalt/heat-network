import { describe, expect, it } from 'vitest';
import {
  DIAMETER_COLOR,
  DIAMETER_TABLE,
  DIAMETER_WIDTH,
  EXISTING_DIAMETER_COLOR,
  EXISTING_DIAMETER_WIDTH,
  PIPE_WIDTH,
  PIPE_WIDTH_HOVER,
  POLYGON_GEOMETRY_FILTER,
  RESTRICTION_FILL_FILTER,
  connectionPointColor,
  linePaint,
  restrictionFillColor,
} from './paint';

describe('linePaint', () => {
  it('omits color and dasharray when not provided', () => {
    const paint = linePaint(undefined, 3);
    expect(paint).toEqual({ 'line-width': 3 });
    expect('line-color' in paint).toBe(false);
    expect('line-dasharray' in paint).toBe(false);
  });

  it('includes color and dasharray when provided', () => {
    const paint = linePaint('#f97316', 4, [3, 2]);
    expect(paint['line-color']).toBe('#f97316');
    expect(paint['line-dasharray']).toEqual([3, 2]);
  });

  it('diameter colors use match and widths are interpolations', () => {
    [DIAMETER_COLOR, EXISTING_DIAMETER_COLOR].forEach((expression) => {
      expect((expression as unknown[])[0]).toBe('match');
    });
    [DIAMETER_WIDTH, EXISTING_DIAMETER_WIDTH].forEach((expression) => {
      expect((expression as unknown[])[0]).toBe('interpolate');
    });
    expect((PIPE_WIDTH as unknown[])[0]).toBe('interpolate');
    expect((PIPE_WIDTH as unknown[])[2]).toEqual(['zoom']);
    expect((PIPE_WIDTH_HOVER as unknown[])[2]).toEqual(['zoom']);
  });

  it('existing network grayscale is inverted (larger diameter is darker)', () => {
    const first = DIAMETER_TABLE[0];
    const last = DIAMETER_TABLE[DIAMETER_TABLE.length - 1];
    const luminance = (hex: string) => {
      const value = Number.parseInt(hex.slice(1, 3), 16);
      return value;
    };
    expect(luminance(first.grayColor)).toBeGreaterThan(luminance(last.grayColor));
  });

  it('diameter table covers 18 values from 50 to 1400 mm', () => {
    expect(DIAMETER_TABLE).toHaveLength(18);
    expect(DIAMETER_TABLE[0].diameter).toBe(50);
    expect(DIAMETER_TABLE[DIAMETER_TABLE.length - 1].diameter).toBe(1400);
    const widths = DIAMETER_TABLE.map((row) => row.pairWidth);
    expect(widths).toEqual([...widths].sort((a, b) => a - b));
  });

  it('restriction and connection point colors use match/case expressions', () => {
    expect((restrictionFillColor() as unknown[])[0]).toBe('case');
    expect((connectionPointColor() as unknown[])[0]).toBe('match');
  });

  it('polygon geometry filter allows only Polygon/MultiPolygon', () => {
    const filter = POLYGON_GEOMETRY_FILTER as unknown[];
    expect(filter[0]).toBe('match');
    expect(filter[1]).toEqual(['geometry-type']);
    expect(filter[2]).toEqual(['Polygon', 'MultiPolygon']);
    expect(filter[3]).toBe(true);
    expect(filter[4]).toBe(false);
  });

  it('restriction fill filter excludes linear special polygons', () => {
    const filter = RESTRICTION_FILL_FILTER as unknown[];
    expect(filter[0]).toBe('all');
    expect(filter[1]).toEqual(POLYGON_GEOMETRY_FILTER);
    expect(filter[2]).toEqual([
      'match',
      ['get', 'restriction_type'],
      ['road', 'tram_tracks'],
      false,
      true,
    ]);
  });
});
