/** Ширина попапа по типу объекта (ADR-0058): у трасс длинные id, им нужно больше места. */
const WIDTHS: Record<string, number> = {
  heat_network: 560,
  restriction: 460,
  heat_chamber: 440,
  technical_node: 420,
  oks_connection_point: 360,
  source: 340,
  variant_summary: 360,
};

const DEFAULT_WIDTH = 360;

export function popupWidth(objectType: string | undefined | null): number {
  return (objectType && WIDTHS[objectType]) || DEFAULT_WIDTH;
}
