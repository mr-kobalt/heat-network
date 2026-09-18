import { create } from 'zustand';
import { FeatureCollection, GeoFeature } from './types';

export type LayerKey =
  | 'existingNetwork'
  | 'restrictions'
  | 'connectionPoints'
  | 'newNetwork'
  | 'reconstruction'
  | 'tieIns'
  | 'chambers'
  | 'technicalNodes';

type State = {
  input: FeatureCollection | null;
  result: FeatureCollection | null;
  variants: string[];
  activeVariant: string | null;
  selected: GeoFeature | null;
  visibility: Record<LayerKey, boolean>;
  setInput: (input: FeatureCollection | null) => void;
  setResult: (result: FeatureCollection | null) => void;
  setActiveVariant: (variant: string | null) => void;
  select: (feature: GeoFeature | null) => void;
  toggleLayer: (layer: LayerKey) => void;
};

const defaultVisibility: Record<LayerKey, boolean> = {
  existingNetwork: true,
  restrictions: true,
  connectionPoints: true,
  newNetwork: true,
  reconstruction: true,
  tieIns: true,
  chambers: true,
  technicalNodes: true,
};

export const useStore = create<State>((set) => ({
  input: null,
  result: null,
  variants: [],
  activeVariant: null,
  selected: null,
  visibility: defaultVisibility,
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
    set({ result, variants, activeVariant: variants[0] ?? null, selected: null });
  },
  setActiveVariant: (variant) => set({ activeVariant: variant, selected: null }),
  select: (feature) => set({ selected: feature }),
  toggleLayer: (layer) =>
    set((state) => ({ visibility: { ...state.visibility, [layer]: !state.visibility[layer] } })),
}));
