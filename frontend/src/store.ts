import { create } from 'zustand';
import { FeatureCollection, GeoFeature, GridMask, StageDescriptor, StageManifest } from './types';
import type { BasemapId } from './map/style';

/** Итоговая вкладка (результат) — вне этапов. */
export const RESULT_STAGE = 'result';

/** Статус запуска расчёта (ADR-0016/0057). */
export type RunStatus = 'IDLE' | 'PENDING' | 'RUNNING' | 'DONE' | 'PARTIAL' | 'FAILED';

export type LayerKey =
  | 'existingNetwork'
  | 'restrictions'
  | 'restrictionBuffers'
  | 'connectionPoints'
  | 'newNetwork'
  | 'chambers'
  | 'technicalNodes';

/** Режим отображения слоя: выкл / только геометрия / геометрия + подписи. */
export type LayerMode = 'off' | 'geo' | 'labels';

export function isLayerVisible(mode: LayerMode): boolean {
  return mode !== 'off';
}

export function hasLayerLabels(mode: LayerMode): boolean {
  return mode === 'labels';
}

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
  /** Связь вариант ↔ проход поиска (из `variant_summary.pass`). */
  variantToPass: Record<string, number>;
  passToVariant: Record<number, string>;
  /** Показатель S варианта (из `variant_summary.score`). */
  variantScore: Record<string, number>;
  selected: GeoFeature | null;
  /** Координата попапа выбранного объекта [lng, lat]. */
  selectedAnchor: [number, number] | null;
  /** Счётчик запросов центрирования карты на выбранном объекте (без зума). */
  centerRequest: number;
  /** Объект, подсвечиваемый при наведении на чип перехода (ADR-0058). */
  hovered: GeoFeature | null;
  layerMode: Record<LayerKey, LayerMode>;
  /** Выбранная подложка: OSM, топографическая или без подложки. */
  basemap: BasemapId;
  /** Показывать реальный масштаб пары труб (иначе — пропорционально Ду). */
  realPipeScale: boolean;
  algorithms: AlgorithmInfo[];
  selectedAlgorithm: string | null;
  /** ADR-0036: трассировка этапов доступна для текущего запуска. */
  runId: string | null;
  traced: boolean;
  stages: StageDescriptor[];
  /** Активная вкладка: 'result', 'input' или id этапа. */
  activeStage: string;
  /** Выбранный проход этапа «Деревья». */
  treePass: number;
  /** ADR-0057: глобальный статус текущего/последнего расчёта. */
  runStatus: RunStatus;
  runStage: string | null;
  runProgress: number;
  runError: string | null;
  /** Идёт расчёт/загрузка результата (для индикации в двух точках входа). */
  runBusy: boolean;
  /** ADR-0059: идёт перетаскивание границы панели (карта не ресайзится). */
  panelResizing: boolean;
  beginRun: () => void;
  updateRun: (status: RunStatus, stage?: string | null, progress?: number) => void;
  finishRun: (status: RunStatus, error?: string | null) => void;
  resetRun: () => void;
  setPanelResizing: (value: boolean) => void;
  stageData: Record<string, FeatureCollection>;
  gridMask: GridMask | null;
  setInput: (input: FeatureCollection | null) => void;
  setResult: (result: FeatureCollection | null) => void;
  setActiveVariant: (variant: string | null) => void;
  select: (feature: GeoFeature | null, anchor?: [number, number] | null) => void;
  selectAndCenter: (feature: GeoFeature, anchor?: [number, number] | null) => void;
  setHovered: (feature: GeoFeature | null) => void;
  setLayerMode: (layer: LayerKey, mode: LayerMode) => void;
  setBasemap: (basemap: BasemapId) => void;
  toggleRealPipeScale: () => void;
  setAlgorithms: (algorithms: AlgorithmInfo[]) => void;
  setSelectedAlgorithm: (algorithm: string | null) => void;
  setStages: (runId: string, manifest: StageManifest) => void;
  setActiveStage: (stage: string) => void;
  setTreePass: (pass: number) => void;
  setStageData: (stageId: string, data: FeatureCollection) => void;
  setGridMask: (mask: GridMask | null) => void;
};

const defaultLayerMode: Record<LayerKey, LayerMode> = {
  existingNetwork: 'geo',
  restrictions: 'geo',
  restrictionBuffers: 'off',
  connectionPoints: 'geo',
  newNetwork: 'geo',
  chambers: 'geo',
  technicalNodes: 'geo',
};

export const useStore = create<State>((set) => ({
  input: null,
  result: null,
  variants: [],
  activeVariant: null,
  variantToPass: {},
  passToVariant: {},
  variantScore: {},
  selected: null,
  selectedAnchor: null,
  centerRequest: 0,
  hovered: null,
  layerMode: defaultLayerMode,
  basemap: 'osm',
  realPipeScale: false,
  algorithms: [],
  selectedAlgorithm: null,
  runId: null,
  traced: false,
  stages: [],
  activeStage: RESULT_STAGE,
  treePass: 1,
  runStatus: 'IDLE',
  runStage: null,
  runProgress: 0,
  runError: null,
  runBusy: false,
  panelResizing: false,
  stageData: {},
  gridMask: null,
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
    const variantToPass: Record<string, number> = {};
    const passToVariant: Record<number, string> = {};
    const variantScore: Record<string, number> = {};
    if (result) {
      for (const feature of result.features) {
        const properties = feature.properties;
        if (properties.object_type !== 'variant_summary') {
          continue;
        }
        const variant = properties.variant_id;
        const pass = properties.pass;
        if (variant == null) {
          continue;
        }
        const key = String(variant);
        if (typeof properties.score === 'number') {
          variantScore[key] = properties.score;
        }
        if (typeof pass !== 'number') {
          continue;
        }
        variantToPass[key] = pass;
        passToVariant[pass] = key;
      }
    }
    const ranked = result?.features.find(
      (feature) =>
        feature.properties.object_type === 'variant_summary' && feature.properties.rank === 1,
    )?.properties.variant_id;
    const activeVariant = ranked != null ? String(ranked) : (variants[0] ?? null);
    set({
      result,
      variants,
      variantToPass,
      passToVariant,
      variantScore,
      activeVariant,
      selected: null,
      selectedAnchor: null,
      runId: null,
      traced: false,
      stages: [],
      activeStage: RESULT_STAGE,
      treePass: activeVariant && variantToPass[activeVariant] ? variantToPass[activeVariant] : 1,
      stageData: {},
      gridMask: null,
    });
  },
  setActiveVariant: (variant) =>
    set((state) => ({
      activeVariant: variant,
      selected: null,
      selectedAnchor: null,
      treePass:
        variant && state.variantToPass[variant] ? state.variantToPass[variant] : state.treePass,
    })),
  select: (feature, anchor = null) => set({
    selected: feature,
    selectedAnchor: feature ? anchor : null,
    hovered: null,
  }),
  selectAndCenter: (feature, anchor = null) => set((state) => ({
    selected: feature,
    selectedAnchor: anchor,
    hovered: null,
    centerRequest: state.centerRequest + 1,
  })),
  setHovered: (feature) => set({ hovered: feature }),
  setLayerMode: (layer, mode) =>
    set((state) => ({ layerMode: { ...state.layerMode, [layer]: mode } })),
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
  setStages: (runId, manifest) =>
    set((state) => {
      const activeVariantPass = state.activeVariant
        ? state.variantToPass[state.activeVariant]
        : undefined;
      const treePass = activeVariantPass ?? manifest.bestPass ?? manifest.passes ?? 1;
      return {
        runId,
        traced: true,
        stages: manifest.stages,
        activeStage: RESULT_STAGE,
        treePass,
        activeVariant: state.passToVariant[treePass] ?? state.activeVariant,
        stageData: {},
        gridMask: null,
      };
    }),
  setActiveStage: (stage) => set({ activeStage: stage, selected: null, selectedAnchor: null }),
  setTreePass: (pass) =>
    set((state) => ({
      treePass: pass,
      activeVariant: state.passToVariant[pass] ?? state.activeVariant,
    })),
  beginRun: () => set({
    runStatus: 'PENDING',
    runStage: null,
    runProgress: 0,
    runError: null,
    runBusy: true,
  }),
  updateRun: (status, stage = null, progress = 0) =>
    set({ runStatus: status, runStage: stage, runProgress: progress }),
  finishRun: (status, error = null) =>
    set((state) => ({
      runStatus: status,
      runError: error,
      runBusy: false,
      runStage: status === 'FAILED' ? state.runStage : 'done',
      runProgress: status === 'FAILED' ? state.runProgress : 100,
    })),
  resetRun: () => set({
    runStatus: 'IDLE',
    runStage: null,
    runProgress: 0,
    runError: null,
    runBusy: false,
  }),
  setPanelResizing: (value) => set({ panelResizing: value }),
  setStageData: (stageId, data) =>
    set((state) => ({ stageData: { ...state.stageData, [stageId]: data } })),
  setGridMask: (mask) => set({ gridMask: mask }),
}));
