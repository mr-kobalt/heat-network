import { beforeEach, describe, expect, it } from 'vitest';
import { useStore } from './store';

describe('store', () => {
  beforeEach(() => {
    useStore.setState({
      input: null,
      result: null,
      variants: [],
      activeVariant: null,
      selected: null,
      selectedAnchor: null,
      algorithms: [],
      selectedAlgorithm: null,
    });
  });

  it('stores the popup anchor with the selected feature', () => {
    const feature = { type: 'Feature' as const, geometry: null, properties: { id: 1 } };
    useStore.getState().select(feature, [37.6, 55.7]);
    expect(useStore.getState().selected).toBe(feature);
    expect(useStore.getState().selectedAnchor).toEqual([37.6, 55.7]);
    useStore.getState().select(null);
    expect(useStore.getState().selectedAnchor).toBeNull();
  });

  it('selects the default algorithm', () => {
    useStore.getState().setAlgorithms([
      { id: 'a', description: 'A', defaultAlgorithm: false },
      { id: 'mst', description: 'MST', defaultAlgorithm: true },
    ]);
    expect(useStore.getState().selectedAlgorithm).toBe('mst');
  });

  it('keeps an explicitly selected algorithm when the list refreshes', () => {
    useStore.getState().setAlgorithms([
      { id: 'a', description: 'A', defaultAlgorithm: false },
      { id: 'b', description: 'B', defaultAlgorithm: true },
    ]);
    useStore.getState().setSelectedAlgorithm('a');
    useStore.getState().setAlgorithms([
      { id: 'a', description: 'A', defaultAlgorithm: false },
      { id: 'b', description: 'B', defaultAlgorithm: true },
    ]);
    expect(useStore.getState().selectedAlgorithm).toBe('a');
  });

  it('defaults to the OSM basemap and switches it', () => {
    expect(useStore.getState().basemap).toBe('osm');
    useStore.getState().setBasemap('grid');
    expect(useStore.getState().basemap).toBe('grid');
    useStore.getState().setBasemap('osm');
  });

  it('keeps real pipe scale disabled by default and toggles it', () => {
    expect(useStore.getState().realPipeScale).toBe(false);
    useStore.getState().toggleRealPipeScale();
    expect(useStore.getState().realPipeScale).toBe(true);
    useStore.getState().toggleRealPipeScale();
    expect(useStore.getState().realPipeScale).toBe(false);
  });

  it('keeps restriction buffer zones hidden by default and toggles them', () => {
    expect(useStore.getState().visibility.restrictionBuffers).toBe(false);
    useStore.getState().toggleLayer('restrictionBuffers');
    expect(useStore.getState().visibility.restrictionBuffers).toBe(true);
    useStore.getState().toggleLayer('restrictionBuffers');
  });

  it('activates stages from a manifest and resets on a new result', () => {
    useStore.getState().setStages('run-1', {
      runId: 'run-1',
      algorithm: 'grid-forest',
      passes: 2,
      bestPass: 2,
      stages: [
        { id: 'input', title: 'Вход', kind: 'input', format: 'input', available: true },
        { id: 'trees', title: 'Деревья', kind: 'trees', format: 'geojson', available: true, passes: [1, 2], bestPass: 2 },
      ],
    });
    expect(useStore.getState().traced).toBe(true);
    expect(useStore.getState().runId).toBe('run-1');
    expect(useStore.getState().activeStage).toBe('result');
    expect(useStore.getState().treePass).toBe(2);

    useStore.getState().setActiveStage('trees');
    useStore.getState().setTreePass(1);
    expect(useStore.getState().activeStage).toBe('trees');
    expect(useStore.getState().treePass).toBe(1);

    useStore.getState().setStageData('trees-1', { type: 'FeatureCollection', features: [] });
    expect(useStore.getState().stageData['trees-1']).toBeDefined();

    useStore.getState().setResult({ type: 'FeatureCollection', features: [] });
    expect(useStore.getState().traced).toBe(false);
    expect(useStore.getState().activeStage).toBe('result');
    expect(useStore.getState().stages).toEqual([]);
  });

  it('collects variants from the result', () => {
    useStore.getState().setResult({
      type: 'FeatureCollection',
      features: [
        { type: 'Feature', geometry: null, properties: { object_type: 'heat_network', variant_id: 'v2' } },
        { type: 'Feature', geometry: null, properties: { object_type: 'heat_network', variant_id: 'v1' } },
      ],
    });
    expect(useStore.getState().variants).toEqual(['v1', 'v2']);
    expect(useStore.getState().activeVariant).toBe('v1');
  });
});
