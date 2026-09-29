#!/usr/bin/env node
// Сборка презентации: Markdown → HTML (pandoc) → PDF (Chromium).
// Тема: docs/06-submission/presentation-theme.css (+ -notes.css).
//
// Использование: node scripts/build-presentation.mjs [--notes]
//   (основной PDF; с --notes дополнительно notes.pdf)
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, rmSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const sub = join(root, 'docs', '06-submission');
const out = join(root, 'target', 'presentation');
const md = join(sub, 'presentation.md');
const pandoc = process.env.PANDOC || 'pandoc';
const chromium = process.env.CHROMIUM || 'chromium';
const args = new Set(process.argv.slice(2));

function run(cmd, argv, label) {
  const result = spawnSync(cmd, argv, { stdio: 'inherit' });
  if (result.error) {
    console.error(`[presentation] не удалось запустить ${label}: ${result.error.message}`);
    process.exit(1);
  }
  if (result.status !== 0) {
    console.error(`[presentation] ${label} завершился с кодом ${result.status}`);
    process.exit(result.status ?? 1);
  }
}

function pandocHtml(cssFiles, target) {
  run(pandoc, [
    md,
    '--from', 'markdown',
    '--to', 'html5',
    '--standalone',
    '--section-divs',
    '--resource-path', sub,
    ...cssFiles.flatMap((css) => ['--css', css]),
    '--output', target,
  ], 'pandoc');
}

function chromiumPdf(html, pdf) {
  run(chromium, [
    '--headless=new',
    '--disable-gpu',
    '--no-sandbox',
    '--allow-file-access-from-files',
    '--no-pdf-header-footer',
    '--virtual-time-budget=4000',
    `--user-data-dir=${join(out, '.chrome')}`,
    `--print-to-pdf=${pdf}`,
    `file://${html}`,
  ], 'chromium');
}

if (!existsSync(md)) {
  console.error(`[presentation] нет источника: ${md}`);
  process.exit(1);
}
rmSync(out, { recursive: true, force: true });
mkdirSync(out, { recursive: true });

// Ассеты: тема, шрифты, скриншоты — рядом с HTML (относительные пути).
cpSync(join(sub, 'presentation-theme.css'), join(out, 'presentation-theme.css'));
cpSync(join(sub, 'presentation-theme-notes.css'), join(out, 'presentation-theme-notes.css'));
cpSync(join(sub, 'presentation-assets'), join(out, 'presentation-assets'), { recursive: true });
cpSync(join(sub, 'screenshots'), join(out, 'screenshots'), { recursive: true });

const mainHtml = join(out, 'presentation.html');
pandocHtml(['presentation-theme.css'], mainHtml);
const mainPdf = join(out, 'sudoers-lct2026.pdf');
chromiumPdf(mainHtml, mainPdf);
console.log(`[presentation] → ${mainPdf}`);

if (args.has('--notes')) {
  const notesHtml = join(out, 'presentation-notes.html');
  pandocHtml(['presentation-theme.css', 'presentation-theme-notes.css'], notesHtml);
  const notesPdf = join(out, 'sudoers-lct2026-notes.pdf');
  chromiumPdf(notesHtml, notesPdf);
  console.log(`[presentation] → ${notesPdf}`);
}
