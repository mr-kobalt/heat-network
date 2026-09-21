import { describe, expect, it } from 'vitest';
import {
  CHAMBER_EXISTING_ICON,
  CHAMBER_NEW_ICON,
  GRAPH_PAPER_PATTERN,
  graphPaperPattern,
  overlayIconById,
  squareWithDotIcon,
  stripePattern,
} from './icons';
import { ZONE_PATTERN } from './visuals';

describe('icons', () => {
  it('rasterizes a square with a dot of the right size', () => {
    const icon = squareWithDotIcon('#000000', '#ff0000', 16, 2);
    expect(icon.width).toBe(16);
    expect(icon.height).toBe(16);
    expect(icon.data).toHaveLength(16 * 16 * 4);
    // Центр — точка (красная), есть непрозрачные пиксели.
    const center = (8 * 16 + 8) * 4;
    expect(icon.data[center]).toBe(255);
    expect(icon.data[center + 3]).toBe(255);
  });

  it('builds a diagonal stripe pattern with transparent background', () => {
    const pattern = stripePattern('#dc2626', 8, 3);
    expect(pattern.width).toBe(8);
    expect(pattern.height).toBe(8);
    const alphaAt = (x: number, y: number) => pattern.data[(y * 8 + x) * 4 + 3];
    expect(alphaAt(0, 0)).toBe(255);
    expect(alphaAt(6, 6)).toBe(0);
  });

  it('resolves known icon ids and ignores unknown ones', () => {
    expect(overlayIconById(CHAMBER_EXISTING_ICON, '#111111', '#222222')?.width).toBe(24);
    expect(overlayIconById(CHAMBER_NEW_ICON, '#111111', '#222222')?.width).toBe(24);
    expect(overlayIconById(ZONE_PATTERN, '#111111', '#222222')?.width).toBe(8);
    expect(overlayIconById(GRAPH_PAPER_PATTERN, '#111111', '#222222')?.width).toBe(100);
    expect(overlayIconById('unknown', '#111111', '#222222')).toBeUndefined();
  });

  it('builds an opaque graph-paper tile with minor and major lines', () => {
    const pattern = graphPaperPattern({ size: 100, minor: 10, major: 50 });
    expect(pattern.width).toBe(100);
    expect(pattern.height).toBe(100);
    const pixelAt = (x: number, y: number) => {
      const index = (y * 100 + x) * 4;
      return [pattern.data[index], pattern.data[index + 1], pattern.data[index + 2], pattern.data[index + 3]];
    };
    // Все пиксели непрозрачны (белый фон бумаги).
    expect(pixelAt(3, 3)[3]).toBe(255);
    expect(pixelAt(3, 3).slice(0, 3)).toEqual([255, 255, 255]);
    // Тонкая линия и утолщённая отличаются от фона.
    expect(pixelAt(10, 3).slice(0, 3)).not.toEqual([255, 255, 255]);
    expect(pixelAt(0, 0).slice(0, 3)).not.toEqual([255, 255, 255]);
  });
});
