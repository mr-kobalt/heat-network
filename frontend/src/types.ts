export type Geometry = {
  type: string;
  coordinates: unknown;
};

export type FeatureProperties = {
  id?: string;
  object_type?: string;
  variant_id?: string;
  diameter?: number;
  flow_tph?: number;
  length?: number;
  cost?: number;
  laying_method?: string;
  restriction_type?: string;
  address?: string;
  start_node_id?: string;
  end_node_id?: string;
  existing_object_id?: string;
  existing_object_type?: string;
  existing_diameter?: number;
  required_diameter?: number;
  depth_start?: number | null;
  depth_end?: number | null;
  rank?: number;
  calculated_cost?: number;
  construction_cost?: number;
  reconstruction_cost?: number;
  chamber_construction_cost?: number;
  chamber_reconstruction_cost?: number;
  tie_in_cost?: number;
  unconnected_penalty?: number;
  new_network_length?: number;
  reconstruction_length?: number;
  score?: number;
  unconnected_oks_ids?: string[];
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

export function isResultFeature(objectType: string | undefined): boolean {
  return (
    objectType === 'heat_network' ||
    objectType === 'tie_in' ||
    objectType === 'heat_chamber' ||
    objectType === 'technical_node' ||
    objectType === 'heat_network_reconstruction' ||
    objectType === 'heat_chamber_reconstruction'
  );
}
