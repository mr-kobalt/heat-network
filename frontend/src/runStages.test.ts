import { describe, expect, it } from 'vitest';
import { formatDuration, stageLabel } from './runStages';

describe('formatDuration', () => {
  it('formats milliseconds as mm:ss', () => {
    expect(formatDuration(0)).toBe('00:00');
    expect(formatDuration(5_000)).toBe('00:05');
    expect(formatDuration(65_000)).toBe('01:05');
    expect(formatDuration(3_600_000)).toBe('60:00');
  });

  it('clamps negative values', () => {
    expect(formatDuration(-1000)).toBe('00:00');
  });
});

describe('stageLabel', () => {
  it('maps known stages and falls back to the raw id', () => {
    expect(stageLabel('generate')).toBe('Поиск трасс');
    expect(stageLabel('custom')).toBe('custom');
    expect(stageLabel(null)).toBe('Подготовка');
  });
});
