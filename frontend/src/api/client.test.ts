import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRun, fetchGridMask, fetchStage, fetchStages } from './client';

describe('createRun', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('adds the algorithm query parameter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ id: 'run-1' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await createRun('dataset-1', 'mst');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/datasets/dataset-1/runs?algorithm=mst',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('omits the parameter without an algorithm', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ id: 'run-1' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await createRun('dataset-1', null);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/datasets/dataset-1/runs',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('adds the trace parameter', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ id: 'run-1' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await createRun('dataset-1', 'grid-forest', true);

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/datasets/dataset-1/runs?algorithm=grid-forest&trace=true',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('adds the depth mode parameter (ADR-0073)', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ id: 'run-1' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await createRun('dataset-1', 'grid-forest', true, 'depth');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/datasets/dataset-1/runs?algorithm=grid-forest&trace=true&mode=depth',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('omits the mode parameter for 2D', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ id: 'run-1' }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await createRun('dataset-1', null, false, '2d');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/v1/datasets/dataset-1/runs',
      expect.objectContaining({ method: 'POST' }),
    );
  });
});

describe('stage endpoints', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
  });

  it('requests the stage manifest', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ stages: [] }) });
    vi.stubGlobal('fetch', fetchMock);

    await fetchStages('run-7');

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/runs/run-7/stages');
  });

  it('requests a single stage', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: async () => ({ type: 'FeatureCollection', features: [] }),
    });
    vi.stubGlobal('fetch', fetchMock);

    await fetchStage('run-7', 'trees-2');

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/runs/run-7/stages/trees-2');
  });

  it('requests the grid mask', async () => {
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, json: async () => ({ width: 2 }) });
    vi.stubGlobal('fetch', fetchMock);

    await fetchGridMask('run-7');

    expect(fetchMock).toHaveBeenCalledWith('/api/v1/runs/run-7/stages/grid');
  });
});
