/**
 * Декодирование растровых масок этапа «сетка» (ADR-0036). Чистый модуль без
 * зависимости от maplibre-gl — пригоден для unit-тестов.
 */

/** base64 → массив бит (строка 0 — север, младший бит вперёд). */
export function decodeBits(base64: string, count: number): Uint8Array {
  const binary = atob(base64 ?? '');
  const bytes = new Uint8Array(binary.length);
  for (let index = 0; index < binary.length; index++) {
    bytes[index] = binary.charCodeAt(index);
  }
  const bits = new Uint8Array(count);
  for (let index = 0; index < count; index++) {
    bits[index] = (bytes[index >> 3] >> (index & 7)) & 1;
  }
  return bits;
}
