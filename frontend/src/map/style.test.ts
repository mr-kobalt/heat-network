import { afterEach, describe, expect, it, vi } from 'vitest';
import { resolveBasemapStyle } from './style';

function mockFiles(files: string[]): void {
  const set = new Set(files);
  vi.stubGlobal(
    'fetch',
    vi.fn(async (url: string) => ({ ok: set.has(url) })),
  );
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('resolveBasemapStyle', () => {
  it('returns a white blank style for "none"', async () => {
    mockFiles([]);
    const style = await resolveBasemapStyle('none') as {
      layers: Array<{ paint?: Record<string, unknown> }>;
    };
    expect(typeof style).toBe('object');
    expect(style.layers[0].paint?.['background-color']).toBe('#ffffff');
  });

  it('prefers the full OSM extract when available', async () => {
    mockFiles(['/basemap/moscow.pmtiles']);
    expect(await resolveBasemapStyle('osm')).toBe('/basemap/style.json');
  });

  it('falls back to the placeholder OSM extract', async () => {
    mockFiles(['/basemap/placeholder.pmtiles']);
    expect(await resolveBasemapStyle('osm')).toBe('/basemap/style-placeholder.json');
  });

  it('returns a graph-paper style for "grid" without any network', async () => {
    const fetchMock = vi.fn();
    vi.stubGlobal('fetch', fetchMock);
    const style = await resolveBasemapStyle('grid') as {
      layers: Array<{ paint?: Record<string, unknown> }>;
    };
    expect(style.layers[0].paint?.['background-pattern']).toBe('graph-paper');
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
