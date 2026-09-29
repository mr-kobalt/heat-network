import {
  ADR_INDEX_DOC,
  DOC_KEYS,
  INDEX_DOC,
  hasDoc,
  indexDocText,
} from './registry';

/** Узел дерева документации: каталог (children) либо документ (path). */
export interface DocTreeNode {
  id: string;
  label: string;
  path?: string;
  children?: DocTreeNode[];
}

/** Русские подписи каталогов верхнего уровня (в порядке docs/index.md). */
const DIR_LABELS: Record<string, string> = {
  '01-project': '01. Проект',
  '02-domain': '02. Предметная область',
  '03-architecture': '03. Архитектура',
  '03-architecture/adr': 'ADR',
  '04-delivery': '04. Поставка',
  '05-data': '05. Данные',
};

/**
 * Разрешает относительную ссылку markdown в путь документа внутри `docs/`.
 * Возвращает `null` для внешних ссылок, якорей и не-markdown целей.
 */
export function resolveDocPath(basePath: string, href: string): string | null {
  if (!href) {
    return null;
  }
  const trimmed = href.trim();
  if (
    trimmed.startsWith('#')
    || trimmed.startsWith('mailto:')
    || /^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)
  ) {
    return null;
  }
  const clean = trimmed.split('#')[0].split('?')[0];
  if (!clean) {
    return null;
  }
  const baseDir = basePath.includes('/') ? basePath.slice(0, basePath.lastIndexOf('/')) : '';
  const rawSegments = (clean.startsWith('/') ? clean.slice(1) : `${baseDir}/${clean}`).split('/');
  const segments: string[] = [];
  for (const segment of rawSegments) {
    if (segment === '' || segment === '.') {
      continue;
    }
    if (segment === '..') {
      segments.pop();
      continue;
    }
    segments.push(decodeURIComponent(segment));
  }
  return segments.join('/');
}

interface RawLink {
  title: string;
  href: string;
}

/** Извлекает markdown-ссылки `[title](href)` в порядке появления. */
export function parseDocLinks(markdown: string): RawLink[] {
  const links: RawLink[] = [];
  const pattern = /\[([^\]]+)\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(markdown)) !== null) {
    links.push({ title: match[1].trim(), href: match[2].trim() });
  }
  return links;
}

interface DocNavItem {
  path: string;
  title: string;
}

function itemsFromIndex(indexPath: string, markdown: string): DocNavItem[] {
  const seen = new Set<string>();
  const items: DocNavItem[] = [];
  for (const link of parseDocLinks(markdown)) {
    const path = resolveDocPath(indexPath, link.href);
    if (!path || !path.endsWith('.md') || !hasDoc(path) || seen.has(path)) {
      continue;
    }
    seen.add(path);
    items.push({ path, title: link.title });
  }
  return items;
}

/** Карта «путь документа → заголовок» из курируемых индексов. */
function curatedTitles(): Map<string, string> {
  const titles = new Map<string, string>();
  titles.set(INDEX_DOC, 'Карта документации');
  const mainIndex = indexDocText(INDEX_DOC);
  if (mainIndex) {
    itemsFromIndex(INDEX_DOC, mainIndex).forEach((item) => titles.set(item.path, item.title));
  }
  const adrIndex = indexDocText(ADR_INDEX_DOC);
  if (adrIndex) {
    itemsFromIndex(ADR_INDEX_DOC, adrIndex).forEach((item) => titles.set(item.path, item.title));
  }
  return titles;
}

function prettify(segment: string): string {
  const base = segment.replace(/\.md$/, '').replace(/^\d+[-_]?/, '');
  const words = base.split(/[-_]+/).filter(Boolean);
  const text = words.join(' ');
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : segment;
}

/** Документы/каталоги: сначала файлы, затем папки, внутри — по алфавиту. */
function sortTree(nodes: DocTreeNode[]): DocTreeNode[] {
  return nodes
    .map((node) => (node.children ? { ...node, children: sortTree(node.children) } : node))
    .sort((a, b) => {
      const aFolder = a.children ? 1 : 0;
      const bFolder = b.children ? 1 : 0;
      if (aFolder !== bFolder) {
        return aFolder - bFolder;
      }
      return a.label.localeCompare(b.label, 'ru');
    });
}

/**
 * Иерархия документации по каталогам `docs/`. Подписи файлов берутся из
 * курируемых индексов, подписи каталогов — из {@link DIR_LABELS}.
 */
export function buildDocTree(): DocTreeNode[] {
  const titles = curatedTitles();
  const roots: DocTreeNode[] = [];
  const folders = new Map<string, DocTreeNode>();

  for (const key of [...DOC_KEYS].sort()) {
    const segments = key.split('/');
    const leaf: DocTreeNode = {
      id: key,
      label: titles.get(key) ?? prettify(segments[segments.length - 1]),
      path: key,
    };
    if (segments.length === 1) {
      roots.push(leaf);
      continue;
    }
    let siblings = roots;
    let prefix = '';
    for (let i = 0; i < segments.length - 1; i++) {
      prefix = prefix ? `${prefix}/${segments[i]}` : segments[i];
      let folder = folders.get(prefix);
      if (!folder) {
        folder = {
          id: prefix,
          label: DIR_LABELS[prefix] ?? prettify(segments[i]),
          children: [],
        };
        folders.set(prefix, folder);
        siblings.push(folder);
      }
      siblings = folder.children as DocTreeNode[];
    }
    siblings.push(leaf);
  }

  return sortTree(roots);
}
