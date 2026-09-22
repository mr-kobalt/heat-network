import { describe, expect, it } from 'vitest';
import { decodeBits } from './gridMask';

describe('decodeBits', () => {
  it('decodes little-endian bits from base64', () => {
    // 0b00000101 = 5 -> биты 1,0,1
    const base64 = btoa(String.fromCharCode(5));
    const bits = decodeBits(base64, 3);
    expect(Array.from(bits)).toEqual([1, 0, 1]);
  });

  it('handles more than eight cells across bytes', () => {
    // байт 0 = 0b00000001, байт 1 = 0b00000010
    const base64 = btoa(String.fromCharCode(1, 2));
    const bits = decodeBits(base64, 16);
    expect(bits[0]).toBe(1);
    expect(bits[8]).toBe(0);
    expect(bits[9]).toBe(1);
    expect(bits[1]).toBe(0);
  });
});
