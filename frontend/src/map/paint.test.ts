import { describe, expect, it } from 'vitest';
import { DIAMETER_COLOR, DIAMETER_WIDTH, linePaint } from './paint';

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

  it('diameter expressions are valid (single-level) MapLibre expressions', () => {
    expect(Array.isArray(DIAMETER_COLOR)).toBe(true);
    expect((DIAMETER_COLOR as unknown[])[0]).toBe('interpolate');
    expect(Array.isArray((DIAMETER_COLOR as unknown[])[0])).toBe(false);

    expect(Array.isArray(DIAMETER_WIDTH)).toBe(true);
    expect((DIAMETER_WIDTH as unknown[])[0]).toBe('interpolate');
    expect(Array.isArray((DIAMETER_WIDTH as unknown[])[0])).toBe(false);
  });
});
