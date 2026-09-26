import type { FeatureCollection, GeoFeature } from '../types';

/** Инцидентное ребро узла: `edgeId` — участок, `other` — соседний узел. */
interface Incident {
  edgeId: string;
  other: string;
  start: string;
  end: string;
}

export type NodeGraph = Map<string, Incident[]>;

const NODE_TYPES = new Set(['heat_chamber', 'technical_node', 'oks_connection_point', 'source']);

const NODE_TYPE_LABELS: Record<string, string> = {
  heat_chamber: 'камера',
  technical_node: 'техузел',
  oks_connection_point: 'точка',
  source: 'источник',
  heat_network: 'трасса',
};

/** Короткое имя типа объекта для ссылок переходов. */
export function nodeTypeLabel(objectType: string | undefined | null): string {
  return (objectType && NODE_TYPE_LABELS[objectType]) || '';
}

function nodeIds(result: FeatureCollection | null, activeVariant: string | null): NodeGraph {
  const graph: NodeGraph = new Map();
  const addIncident = (nodeId: string, incident: Incident) => {
    const list = graph.get(nodeId);
    if (list) {
      list.push(incident);
    } else {
      graph.set(nodeId, [incident]);
    }
  };
  if (!result) {
    return graph;
  }
  result.features.forEach((feature) => {
    const properties = feature.properties;
    if (properties.object_type !== 'heat_network') {
      return;
    }
    if (activeVariant != null && String(properties.variant_id) !== String(activeVariant)) {
      return;
    }
    const start = properties.start_node_id;
    const end = properties.end_node_id;
    if (start == null || end == null) {
      return;
    }
    const s = String(start);
    const e = String(end);
    const edgeId = properties.id != null ? String(properties.id) : `${s}->${e}`;
    addIncident(s, { edgeId, other: e, start: s, end: e });
    addIncident(e, { edgeId, other: s, start: s, end: e });
  });
  return graph;
}

export function buildNodeGraph(
  result: FeatureCollection | null,
  activeVariant: string | null,
): NodeGraph {
  return nodeIds(result, activeVariant);
}

/** Индекс объектов по id: узлы (вход+результат) и участки активного варианта. */
export function buildNodeIndex(
  input: FeatureCollection | null,
  result: FeatureCollection | null,
  activeVariant: string | null,
): Map<string, GeoFeature> {
  const index = new Map<string, GeoFeature>();
  const put = (feature: GeoFeature) => {
    const id = feature.properties.id;
    if (id != null) {
      index.set(String(id), feature);
    }
  };
  input?.features.forEach((feature) => {
    if (NODE_TYPES.has(String(feature.properties.object_type))) {
      put(feature);
    }
  });
  result?.features.forEach((feature) => {
    const properties = feature.properties;
    const type = String(properties.object_type);
    if (type !== 'heat_network' && !NODE_TYPES.has(type)) {
      return;
    }
    if (activeVariant != null && type === 'heat_network'
        && String(properties.variant_id) !== String(activeVariant)) {
      return;
    }
    put(feature);
  });
  return index;
}

/**
 * Переходы (ADR-0058): у участка — узлы (`start`/`end`), у узла — инцидентные
 * участки. Ориентация рёбер — от корня к листьям: входящее ребро (`end === id`)
 * даёт «предыдущий» участок, исходящее (`start === id`) — «следующий».
 */
export function nodeLinks(
  feature: GeoFeature,
  graph: NodeGraph,
): { previous: string[]; next: string[] } {
  if (feature.properties.object_type === 'heat_network') {
    const start = feature.properties.start_node_id;
    const end = feature.properties.end_node_id;
    return {
      previous: start != null ? [String(start)] : [],
      next: end != null ? [String(end)] : [],
    };
  }
  const id = feature.properties.id != null ? String(feature.properties.id) : '';
  const previous: string[] = [];
  const next: string[] = [];
  (graph.get(id) ?? []).forEach((incident) => {
    if (incident.end === id && incident.other !== id) {
      previous.push(incident.edgeId);
    } else if (incident.start === id && incident.other !== id) {
      next.push(incident.edgeId);
    }
  });
  return { previous: unique(previous), next: unique(next) };
}

/** Якорь объекта: для линий — середина, иначе первая координата. */
export function featureAnchor(feature: GeoFeature): [number, number] | null {
  const geometry = feature.geometry;
  if (!geometry) {
    return null;
  }
  if (geometry.type === 'LineString' || geometry.type === 'MultiLineString') {
    const middle = middleCoordinate(geometry.coordinates);
    if (middle) {
      return middle;
    }
  }
  return firstCoordinate(geometry.coordinates);
}

function middleCoordinate(node: unknown): [number, number] | null {
  if (!Array.isArray(node) || node.length === 0) {
    return null;
  }
  const first = node[0];
  if (typeof first === 'number') {
    return null;
  }
  if (typeof (first as unknown[])[0] === 'number') {
    const line = node as Array<[number, number]>;
    return line[Math.floor(line.length / 2)];
  }
  return middleCoordinate(first);
}

function firstCoordinate(node: unknown): [number, number] | null {
  if (!Array.isArray(node)) {
    return null;
  }
  if (typeof node[0] === 'number' && typeof node[1] === 'number') {
    return [node[0], node[1]];
  }
  for (const child of node) {
    const coordinate = firstCoordinate(child);
    if (coordinate) {
      return coordinate;
    }
  }
  return null;
}

function unique(values: string[]): string[] {
  return Array.from(new Set(values));
}
