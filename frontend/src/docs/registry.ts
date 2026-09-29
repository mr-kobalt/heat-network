/**
 * Реестр проектной документации (ADR-0072). Markdown-файлы из `docs/`
 * подключаются Vite как ленивые raw-чанки: контент не попадает в основной
 * бандл карты.
 */
const DOCS_PREFIX = '../../../docs/';

const lazyModules = import.meta.glob('../../../docs/**/*.md', {
  query: '?raw',
  import: 'default',
}) as Record<string, () => Promise<string>>;

/** Индексные файлы нужны синхронно — для построения навигации. */
const eagerModules = import.meta.glob(
  ['../../../docs/index.md', '../../../docs/03-architecture/adr/README.md'],
  { query: '?raw', import: 'default', eager: true },
) as Record<string, string>;

export const INDEX_DOC = 'index.md';
export const ADR_INDEX_DOC = '03-architecture/adr/README.md';

export function normalizeDocKey(globKey: string): string {
  return globKey.startsWith(DOCS_PREFIX) ? globKey.slice(DOCS_PREFIX.length) : globKey;
}

const lazyByKey = new Map<string, () => Promise<string>>();
for (const [globKey, loader] of Object.entries(lazyModules)) {
  lazyByKey.set(normalizeDocKey(globKey), loader);
}

const eagerByKey = new Map<string, string>();
for (const [globKey, content] of Object.entries(eagerModules)) {
  eagerByKey.set(normalizeDocKey(globKey), content);
}

/** Все известные документы (отсортированные пути). */
export const DOC_KEYS: string[] = Array.from(lazyByKey.keys()).sort();

export function hasDoc(key: string): boolean {
  return lazyByKey.has(key);
}

export async function loadDoc(key: string): Promise<string> {
  const loader = lazyByKey.get(key);
  if (!loader) {
    throw new Error(`Документ не найден: ${key}`);
  }
  return loader();
}

/** Синхронный доступ к индексным документам (уже в бандле). */
export function indexDocText(key: string): string | null {
  return eagerByKey.get(key) ?? null;
}

/** Изображения документации (скриншоты и т.п.) — Vite отдаёт их URL. */
const assetModules = import.meta.glob(
  '../../../docs/**/*.{png,jpg,jpeg,svg,webp,gif}',
  { query: '?url', import: 'default', eager: true },
) as Record<string, string>;

const assetByKey = new Map<string, string>();
for (const [globKey, url] of Object.entries(assetModules)) {
  assetByKey.set(normalizeDocKey(globKey), url);
}

/** URL изображения из `docs/` по пути внутри `docs/` (или {@code null}). */
export function resolveDocAsset(key: string): string | null {
  return assetByKey.get(key) ?? null;
}

/** Заголовок документа — первая строка вида `# ...`. */
export function firstHeading(markdown: string, fallback: string): string {
  const match = markdown.match(/^#\s+(.+)$/m);
  return match ? match[1].trim() : fallback;
}
