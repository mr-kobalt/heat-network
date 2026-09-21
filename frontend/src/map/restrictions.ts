/**
 * Условные обозначения и минимальные горизонтальные расстояния ограничений
 * (таблица 2 ТП v2, `docs/02-domain/calculation-rules.md`). Модуль без
 * зависимости от maplibre-gl, чтобы значения и цвета были покрыты тестами.
 */

export interface RestrictionStyle {
  /** Подпись для легенды. */
  label: string;
  /** Цвет заливки и контура на карте. */
  color: string;
  /** Минимальное горизонтальное расстояние, м (для зон). */
  bufferMeters: number;
  /** Допустимый специальный проход (иначе — запрет). */
  special: boolean;
}

export const RESTRICTION_STYLES: Record<string, RestrictionStyle> = {
  oks: { label: 'ОКС', color: '#a855f7', bufferMeters: 5, special: false },
  park: { label: 'Парк', color: '#16a34a', bufferMeters: 1, special: false },
  social_area: { label: 'Социальный объект', color: '#0ea5e9', bufferMeters: 1, special: false },
  prohibited_site: { label: 'Запрещённая территория', color: '#dc2626', bufferMeters: 1, special: false },
  water: { label: 'Водный объект', color: '#2563eb', bufferMeters: 1, special: false },
  railway: { label: 'Железная дорога', color: '#57534e', bufferMeters: 1, special: false },
  road: { label: 'Автодорога', color: '#f59e0b', bufferMeters: 1.5, special: true },
  tram_tracks: { label: 'Трамвайные пути', color: '#eab308', bufferMeters: 1.5, special: true },
  gas_pipeline: { label: 'Газопровод', color: '#f97316', bufferMeters: 2, special: true },
  power_cable: { label: 'Силовой кабель ≤35 кВ', color: '#e11d48', bufferMeters: 2, special: true },
  heat_network: { label: 'Существующая тепловая сеть', color: '#64748b', bufferMeters: 1, special: true },
};

export const DEFAULT_RESTRICTION_STYLE: RestrictionStyle = {
  label: 'Прочее ограничение',
  color: '#ef4444',
  bufferMeters: 1,
  special: false,
};

/** Цвет подключаемого ОКС (полигон, внутри которого есть точка подключения). */
export const OKS_TARGET_COLOR = '#a855f7';

/** Цвет ОКС без точки подключения и прочих неключевых ограничений. */
export const RESTRICTION_INACTIVE_COLOR = '#94a3b8';

/** Состояния точки подключения. */
export const CONNECTION_POINT_COLORS = {
  connected: '#16a34a',
  unconnected: '#dc2626',
  pending: '#0284c7',
} as const;

export function restrictionStyle(type: string | undefined): RestrictionStyle {
  return (type && RESTRICTION_STYLES[type]) || DEFAULT_RESTRICTION_STYLE;
}

export function restrictionBufferMeters(type: string | undefined): number {
  return restrictionStyle(type).bufferMeters;
}
