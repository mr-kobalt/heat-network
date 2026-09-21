import { create } from 'zustand';
import { FeatureCollection, GeoFeature } from './types';
import type { BasemapId } from './map/style';

export type LayerKey =
  | 'existingNetwork'
  | 'restrictions'
  | 'restrictionBuffers'
  | 'connectionPoints'
  | 'newNetwork'
  | 'chambers'
  | 'technicalNodes';

export type AlgorithmInfo = {
  id: string;
  description: string;
  defaultAlgorithm: boolean;
};

type State = {
  input: FeatureCollection | null;
  result: FeatureCollection | null;
  variants: string[];
  activeVariant: string | null;
  selected: GeoFeature | null;
  /** Координата попапа выбранного объекта [lng, lat]. */
  selectedAnchor: [number, number] | null;
  visibility: Record<LayerKey, boolean>;
  /** Выбранная подложка: OSM, топографическая или без подложки. */
  basemap: BasemapId;
  /** Показывать реальный масштаб пары труб (иначе — пропорционально Ду). */
  realPipeScale: boolean;
  algorithms: AlgorithmInfo[];
  selectedAlgorithm: string | null;
  setInput: (input: FeatureCollection | null) => void;
  setResult: (result: FeatureCollection | null) => void;
  setActiveVariant: (variant: string | null) => void;
  select: (feature: GeoFeature | null, anchor?: [number, number] | null) => void;
  toggleLayer: (layer: LayerKey) => void;
  setBasemap: (basemap: BasemapId) => void;
  toggleRealPipeScale: () => void;
  setAlgorithms: (algorithms: AlgorithmInfo[]) => void;
  setSelectedAlgorithm: (algorithm: string | null) => void;
};

const defaultVisibility: Record<LayerKey, boolean> = {
  existingNetwork: true,
  restrictions: true,
  restrictionBuffers: false,
  connectionPoints: true,
  newNetwork: true,
  chambers: true,
  technicalNodes: true,
};

export const useStore = create<State>((set) => ({
  input: null,
  result: null,
  variants: [],
  activeVariant: null,
  selected: null,
  selectedAnchor: null,
  visibility: defaultVisibility,
  basemap: 'osm',
  realPipeScale: false,
  algorithms: [],
  selectedAlgorithm: null,
  setInput: (input) => set({ input }),
  setResult: (result) => {
    const variants = result
      ? Array.from(
          new Set(
            result.features
              .map((feature) => feature.properties.variant_id)
              .filter((value): value is string => Boolean(value)),
          ),
        ).sort()
      : [];
    set({ result, variants, activeVariant: variants[0] ?? null, selected: null, selectedAnchor: null });
  },
  setActiveVariant: (variant) => set({ activeVariant: variant, selected: null, selectedAnchor: null }),
  select: (feature, anchor = null) => set({
    selected: feature,
    selectedAnchor: feature ? anchor : null,
  }),
  toggleLayer: (layer) =>
    set((state) => ({ visibility: { ...state.visibility, [layer]: !state.visibility[layer] } })),
  setBasemap: (basemap) => set({ basemap }),
  toggleRealPipeScale: () => set((state) => ({ realPipeScale: !state.realPipeScale })),
  setAlgorithms: (algorithms) =>
    set((state) => {
      const hasSelected = state.selectedAlgorithm
        && algorithms.some((algorithm) => algorithm.id === state.selectedAlgorithm);
      if (hasSelected) {
        return { algorithms };
      }
      const preferred = algorithms.find((algorithm) => algorithm.defaultAlgorithm) ?? algorithms[0];
      return { algorithms, selectedAlgorithm: preferred?.id ?? null };
    }),
  setSelectedAlgorithm: (algorithm) => set({ selectedAlgorithm: algorithm }),
}));
