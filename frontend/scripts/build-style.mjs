// Генерирует локальные стили подложки (style.json / style-placeholder.json)
// на основе @protomaps/basemaps, с локальными PMTiles, спрайтом и глифами.
// Запуск: node scripts/build-style.mjs  (или pnpm run basemap:style)
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { layers, namedFlavor } from '@protomaps/basemaps';

const here = dirname(fileURLToPath(import.meta.url));
const outDir = resolve(here, '..', 'public', 'basemap');
mkdirSync(outDir, { recursive: true });

// Обесцвеченная подложка: не отвлекает от объектов трассировки.
const flavor = namedFlavor('grayscale');
const glyphs = '/basemap/fonts/{fontstack}/{range}.pbf';
const sprite = '/basemap/sprites/light';
const attribution = '© OpenStreetMap contributors, © Protomaps';
const bounds = [35.0, 54.0, 40.5, 57.0];

function styleFor(pmtilesFile, maxzoom) {
  return {
    version: 8,
    name: `Offline basemap (${pmtilesFile})`,
    glyphs,
    sprite,
    sources: {
      protomaps: {
        type: 'vector',
        url: `pmtiles:///basemap/${pmtilesFile}`,
        minzoom: 0,
        maxzoom,
        bounds,
        attribution,
      },
    },
    layers: layers('protomaps', flavor, { lang: 'ru' }),
  };
}

for (const [file, target, maxzoom] of [
  ['moscow.pmtiles', 'style.json', 14],
  ['placeholder.pmtiles', 'style-placeholder.json', 6],
]) {
  writeFileSync(resolve(outDir, target), `${JSON.stringify(styleFor(file, maxzoom), null, 2)}\n`);
  console.log(`[basemap] ${target} <- ${file} (maxzoom ${maxzoom})`);
}
