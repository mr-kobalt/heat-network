import GithubSlugger from 'github-slugger';

/** Заголовок документа для оглавления (ToC). */
export interface TocHeading {
  /** Уровень заголовка: 2 или 3. */
  level: number;
  /** Текст без инлайн-разметки. */
  text: string;
  /** Идентификатор якоря (совпадает с `rehype-slug`). */
  id: string;
}

const INLINE_LINK = /!?\[([^\]]*)\]\([^)]*\)/g;

/** Убирает инлайн-разметку markdown, оставляя видимый текст. */
export function plainHeadingText(raw: string): string {
  return raw
    .replace(INLINE_LINK, '$1')
    .replace(/`([^`]*)`/g, '$1')
    .replace(/\*\*([^*]+)\*\*/g, '$1')
    .replace(/__([^_]+)__/g, '$1')
    .replace(/\*([^*]+)\*/g, '$1')
    .replace(/_([^_]+)_/g, '$1')
    .replace(/~~([^~]+)~~/g, '$1')
    .trim();
}

/**
 * Извлекает заголовки H2–H3 в порядке появления. Идентификаторы считаются тем
 * же алгоритмом, что и `rehype-slug` (`github-slugger`), поэтому совпадают с
 * якорями отрендеренного markdown.
 */
export function extractHeadings(markdown: string): TocHeading[] {
  const slugger = new GithubSlugger();
  const headings: TocHeading[] = [];
  const pattern = /^(#{2,3})\s+(.+?)\s*#*\s*$/gm;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(markdown)) !== null) {
    const text = plainHeadingText(match[2]);
    if (!text) {
      continue;
    }
    headings.push({ level: match[1].length, text, id: slugger.slug(text) });
  }
  return headings;
}
