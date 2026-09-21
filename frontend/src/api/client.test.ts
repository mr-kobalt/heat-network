import { afterEach, describe, expect, it, vi } from 'vitest';
import { createRun } from './client';

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
});
