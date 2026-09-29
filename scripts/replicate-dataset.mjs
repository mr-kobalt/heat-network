#!/usr/bin/env node
// E8-03a: крупный нагрузочный набор репликацией реального. Копии
// `Датасет скорректированный.geojson` размещаются в сетке N×N со сдвигами
// (id получают суффикс), образуя контролируемый по размеру вход (~100 МБ…1 ГБ)
// без обращения к сети (Overpass).
//
// Запуск:
//   node scripts/replicate-dataset.mjs --grid 13 --out data/generated/replica-13.geojson
//   node scripts/replicate-dataset.mjs --target-mb 500 --out data/generated/replica-500.geojson

import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, writeFile } from 'node:fs/promises';
import { resolve, dirname } from 'node:path';

const ROOT = resolve(new URL('..', import.meta.url).pathname);
const DEFAULT_SOURCE = 'source/Датасет скорректированный.geojson';

function parseArgs(argv) {
  const args = {
    source: DEFAULT_SOURCE,
    grid: 0,
    targetMb: 0,
    gapM: 200.0,
    out: 'data/generated/replica.geojson',
  };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--source') args.source = argv[++i];
    else if (arg === '--grid') args.grid = Number(argv[++i]);
    else if (arg === '--target-mb') args.targetMb = Number(argv[++i]);
    else if (arg === '--gap-m') args.gapM = Number(argv[++i]);
    else if (arg === '--out') args.out = argv[++i];
    else throw new Error(`Неизвестный аргумент: ${arg}`);
  }
  return args;
}

async function readJson(path) {
  const chunks = [];
  for await (const chunk of createReadStream(path)) {
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString('utf8'));
}

function bboxOf(features) {
  const box = [Infinity, Infinity, -Infinity, -Infinity];
  const visit = (c) => {
    if (typeof c[0] === 'number') {
      box[0] = Math.min(box[0], c[0]);
      box[1] = Math.min(box[1], c[1]);
      box[2] = Math.max(box[2], c[0]);
      box[3] = Math.max(box[3], c[1]);
    } else {
      for (const child of c) visit(child);
    }
  };
  for (const feature of features) visit(feature.geometry.coordinates);
  return box;
}

function shiftGeometry(geometry, dx, dy) {
  const shift = (c) => (typeof c[0] === 'number' ? [c[0] + dx, c[1] + dy] : c.map(shift));
  return { type: geometry.type, coordinates: shift(geometry.coordinates) };
}

class StreamingWriter {
  constructor(path) {
    this.stream = createWriteStream(path, 'utf8');
    this.bytes = 0;
    this.first = true;
    this.stream.write('{"type":"FeatureCollection","name":"replica_loadtest",'
        + '"crs":{"type":"name","properties":{"name":"urn:ogc:def:crs:OGC:1.3:CRS84"}},'
        + '"features":[');
  }

  async write(feature) {
    const text = (this.first ? '' : ',') + JSON.stringify(feature);
    this.first = false;
    this.bytes += Buffer.byteLength(text);
    if (!this.stream.write(text)) {
      await new Promise((done) => this.stream.once('drain', done));
    }
  }

  async close(features) {
    const tail = '],"_meta":{"features":' + features + '}}';
    this.bytes += Buffer.byteLength(tail);
    this.stream.write(tail);
    await new Promise((done, fail) => {
      this.stream.end();
      this.stream.on('finish', done);
      this.stream.on('error', fail);
    });
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const sourcePath = resolve(ROOT, args.source);
  const outPath = resolve(ROOT, args.out);
  await mkdir(dirname(outPath), { recursive: true });

  const source = await readJson(sourcePath);
  const features = source.features ?? [];
  const bbox = bboxOf(features);
  const width = bbox[2] - bbox[0];
  const height = bbox[3] - bbox[1];
  const centerLat = (bbox[1] + bbox[3]) / 2.0;
  const mLon = 111320.0 * Math.cos((centerLat * Math.PI) / 180.0);
  const mLat = 110540.0;
  const gapLon = args.gapM / mLon;
  const gapLat = args.gapM / mLat;
  const stepLon = width + gapLon;
  const stepLat = height + gapLat;

  let grid = args.grid;
  if (grid <= 0) {
    if (args.targetMb <= 0) {
      throw new Error('Укажите --grid или --target-mb');
    }
    const sourceBytes = Buffer.byteLength(JSON.stringify(source));
    const copies = Math.ceil((args.targetMb * 1024 * 1024) / sourceBytes);
    grid = Math.max(1, Math.ceil(Math.sqrt(copies)));
  }

  const writer = new StreamingWriter(outPath);
  let count = 0;
  const started = Date.now();
  for (let r = 0; r < grid; r++) {
    for (let c = 0; c < grid; c++) {
      const dx = c * stepLon;
      const dy = r * stepLat;
      const suffix = `_r${r}c${c}`;
      for (const feature of features) {
        const replica = {
          type: 'Feature',
          properties: { ...feature.properties, id: `${feature.properties.id}${suffix}` },
          geometry: shiftGeometry(feature.geometry, dx, dy),
        };
        await writer.write(replica);
        count++;
      }
    }
  }
  await writer.close(count);

  const manifest = {
    generated: new Date().toISOString(),
    source: args.source,
    out: args.out,
    grid,
    replicas: grid * grid,
    gapM: args.gapM,
    stepLon,
    stepLat,
    features: count,
    bytes: writer.bytes,
    elapsedMs: Date.now() - started,
  };
  await writeFile(outPath.replace(/\.geojson$/, '.generation.json'),
      JSON.stringify(manifest, null, 2), 'utf8');
  process.stderr.write(`[E8-03] Реплика: grid=${grid} features=${count} `
      + `size=${(writer.bytes / 1048576).toFixed(1)} МБ\n`);
}

main().catch((error) => {
  process.stderr.write(`[E8-03] Ошибка: ${error.stack ?? error.message}\n`);
  process.exit(1);
});
