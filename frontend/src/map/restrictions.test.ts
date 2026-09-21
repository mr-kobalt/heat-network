import { describe, expect, it } from 'vitest';
import {
  DEFAULT_RESTRICTION_STYLE,
  restrictionBufferMeters,
  restrictionStyle,
} from './restrictions';

describe('restrictions', () => {
  it('uses table 2 minimum horizontal distances', () => {
    expect(restrictionBufferMeters('railway')).toBe(1);
    expect(restrictionBufferMeters('water')).toBe(1);
    expect(restrictionBufferMeters('road')).toBe(1.5);
    expect(restrictionBufferMeters('tram_tracks')).toBe(1.5);
    expect(restrictionBufferMeters('gas_pipeline')).toBe(2);
    expect(restrictionBufferMeters('power_cable')).toBe(2);
    expect(restrictionBufferMeters('heat_network')).toBe(1);
    expect(restrictionBufferMeters('oks')).toBe(5);
  });

  it('falls back to the default style for unknown types', () => {
    expect(restrictionStyle('unknown')).toEqual(DEFAULT_RESTRICTION_STYLE);
    expect(restrictionBufferMeters(undefined)).toBe(1);
  });

  it('marks special crossings per table 2', () => {
    expect(restrictionStyle('road').special).toBe(true);
    expect(restrictionStyle('railway').special).toBe(false);
  });
});
