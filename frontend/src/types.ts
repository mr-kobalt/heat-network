export type Geometry = {
  type: string;
  coordinates: unknown;
};

export type FeatureProperties = {
  id?: string | number;
  object_type?: string;
  variant_id?: string | number;
  diameter?: number;
  flow_tph?: number;
  length?: number;
  cost?: number;
  laying_method?: string;
  restriction_type?: string;
  address?: string;
  start_node_id?: string | number;
  end_node_id?: string | number;
  depth_start?: number | null;
  depth_end?: number | null;
  rank?: number;
  construction_cost?: number;
  chamber_construction_cost?: number;
  existing_chamber_tie_in_count?: number;
  existing_chamber_tie_in_cost?: number;
  unconnected_penalty?: number;
  calculated_cost?: number;
  new_network_length?: number;
  score?: number;
  /** Номер прохода поиска, из которого выращен вариант (связь с «Деревьями»). */
  pass?: number;
  unconnected_oks_ids?: Array<string | number>;
  [key: string]: unknown;
};

export type GeoFeature = {
  type: 'Feature';
  geometry: Geometry | null;
  properties: FeatureProperties;
};

export type FeatureCollection = {
  type: 'FeatureCollection';
  features: GeoFeature[];
};

export function parseFeatureCollection(raw: unknown): FeatureCollection {
  if (!raw || typeof raw !== 'object') {
    throw new Error('Некорректный GeoJSON');
  }
  const candidate = raw as Partial<FeatureCollection>;
  if (candidate.type !== 'FeatureCollection' || !Array.isArray(candidate.features)) {
    throw new Error('Ожидался GeoJSON FeatureCollection');
  }
  return candidate as FeatureCollection;
}

/** Сводная информация о загруженном наборе (диагностика ingest, FR-08). */
export interface DatasetInfo {
  id: string;
  status: string;
  originalFilename?: string;
  objectCounts?: Record<string, number>;
  bbox?: string;
}

/** Этап алгоритма в манифесте трассировки (ADR-0036). */
export type StageDescriptor = {
  id: string;
  title: string;
  kind: string;
  format: 'input' | 'geojson' | 'mask';
  available: boolean;
  passes?: number[];
  bestPass?: number;
};

export type StageManifest = {
  runId: string | null;
  algorithm: string | null;
  passes: number;
  bestPass: number;
  stages: StageDescriptor[];
};

/**
 * Ключ файла стадии: постадийные стадии (`trees`, `relink`, `refine`, …)
 * зависят от выбранного прохода и грузятся как `<id>-<pass>` (ADR-0036).
 */
export function stageFileKey(stage: StageDescriptor | undefined, pass: number): string {
  if (!stage) {
    return '';
  }
  return stage.passes && stage.passes.length > 0 ? `${stage.id}-${pass}` : stage.id;
}

/** Растровая диагностика этапа «сетка» (ADR-0036). */
export type GridMask = {
  originX: number;
  originY: number;
  cellM: number;
  /** Форма сетки: square | hex (ADR-0041). */
  gridShape?: string;
  /** Вертикальный шаг строк, м (для hex меньше cellM). */
  rowSpacing?: number;
  width: number;
  height: number;
  imageWidth: number;
  imageHeight: number;
  imageCellM: number;
  downscaled: boolean;
  /** base64-битсеты, строка 0 — север. */
  blocked: string;
  reachable: string;
  /** Углы изображения TL, TR, BR, BL: [lng, lat]. */
  boundsWgs84: Array<[number, number]>;
  sources: Array<[number, number]>;
  terminalCells: Array<[number, number]>;
};

/** Режим расчёта (ADR-0073): обязательный 2D или с учётом глубины. */
export type CalculationMode = '2d' | 'depth';

/** Подпись режима расчёта для интерфейса. */
export function calculationModeLabel(mode: CalculationMode): string {
  return mode === 'depth' ? 'Глубина' : '2D';
}

/** Режим по данным результата: глубина, если заполнены глубины участков. */
export function inferCalculationMode(
  features: Array<{ properties: FeatureProperties }>,
): CalculationMode {
  const hasDepth = features.some((feature) => {
    const properties = feature.properties;
    return properties.object_type === 'heat_network'
      && (properties.depth_start != null || properties.depth_end != null);
  });
  return hasDepth ? 'depth' : '2d';
}

/** Типы объектов выходного GeoJSON (ТП v2 §7). */
export function isResultFeature(objectType: string | undefined): boolean {
  return (
    objectType === 'heat_network' ||
    objectType === 'heat_chamber' ||
    objectType === 'technical_node' ||
    objectType === 'variant_summary'
  );
}
