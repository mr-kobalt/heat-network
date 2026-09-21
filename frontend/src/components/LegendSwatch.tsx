export type SwatchVariant = 'line' | 'square-dot' | 'hollow-circle' | 'circle' | 'polygon' | 'basemap' | 'stripe';

export interface LegendSwatchProps {
  variant: SwatchVariant;
  color: string;
  width?: number;
  dashed?: boolean;
}

/** Мини-иконка легенды, повторяющая условное обозначение на карте. */
export function LegendSwatch({ variant, color, width = 2, dashed = false }: LegendSwatchProps) {
  const common = { width: 20, height: 14, viewBox: '0 0 20 14' } as const;
  switch (variant) {
    case 'line':
      return (
        <svg {...common} aria-hidden="true">
          <line
            x1="1"
            y1="7"
            x2="19"
            y2="7"
            stroke={color}
            strokeWidth={width}
            strokeLinecap="round"
            strokeDasharray={dashed ? '3 2' : undefined}
          />
        </svg>
      );
    case 'square-dot':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="4.5" y="1.5" width="11" height="11" fill="none" stroke={color} strokeWidth="2" />
          <circle cx="10" cy="7" r="1.8" fill={color} />
        </svg>
      );
    case 'hollow-circle':
      return (
        <svg {...common} aria-hidden="true">
          <circle cx="10" cy="7" r="4.5" fill="none" stroke={color} strokeWidth="2" />
        </svg>
      );
    case 'circle':
      return (
        <svg {...common} aria-hidden="true">
          <circle cx="10" cy="7" r="4.5" fill={color} stroke="#ffffff" strokeWidth="1" />
        </svg>
      );
    case 'basemap':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="2" y="2" width="16" height="10" fill="#e5e7eb" stroke="#9ca3af" strokeWidth="1" />
          <line x1="2" y1="6" x2="18" y2="6" stroke="#9ca3af" strokeWidth="0.8" />
          <line x1="2" y1="10" x2="18" y2="10" stroke="#9ca3af" strokeWidth="0.8" />
          <line x1="8" y1="2" x2="8" y2="12" stroke="#9ca3af" strokeWidth="0.8" />
          <line x1="13" y1="2" x2="13" y2="12" stroke="#9ca3af" strokeWidth="0.8" />
        </svg>
      );
    case 'stripe':
      return (
        <svg {...common} aria-hidden="true">
          <rect x="2" y="2" width="16" height="10" fill="#fee2e2" />
          <line x1="0" y1="14" x2="7" y2="1" stroke="#dc2626" strokeWidth="2" />
          <line x1="6" y1="14" x2="13" y2="1" stroke="#dc2626" strokeWidth="2" />
          <line x1="12" y1="14" x2="19" y2="1" stroke="#dc2626" strokeWidth="2" />
          <rect
            x="2"
            y="2"
            width="16"
            height="10"
            fill="none"
            stroke="#dc2626"
            strokeWidth="1.5"
            strokeDasharray="3 2"
          />
        </svg>
      );
    case 'polygon':
      return (
        <svg {...common} aria-hidden="true">
          <path
            d="M2 12 L5 3 L18 4 L16 12 Z"
            fill={color}
            fillOpacity={dashed ? 0.12 : 0.35}
            stroke={color}
            strokeWidth="1.5"
            strokeDasharray={dashed ? '3 2' : undefined}
          />
        </svg>
      );
    default:
      return null;
  }
}
