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

/** Типы объектов выходного GeoJSON (ТП v2 §7). */
export function isResultFeature(objectType: string | undefined): boolean {
  return (
    objectType === 'heat_network' ||
    objectType === 'heat_chamber' ||
    objectType === 'technical_node' ||
    objectType === 'variant_summary'
  );
}
