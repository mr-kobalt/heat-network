import { describe, expect, it } from 'vitest';
import { extractHeadings, plainHeadingText } from './toc';

describe('plainHeadingText', () => {
  it('removes inline code, links and emphasis', () => {
    expect(plainHeadingText('Метод `grid-forest` и **варианты**')).toBe('Метод grid-forest и варианты');
    expect(plainHeadingText('[ADR](03-architecture/adr/README.md)')).toBe('ADR');
  });
});

describe('extractHeadings', () => {
  it('collects H2 and H3 only, in order', () => {
    const markdown = [
      '# Заголовок',
      '## Первый',
      'текст',
      '### Вложенный',
      '#### Слишком глубоко',
      '## Второй',
    ].join('\n');
    expect(extractHeadings(markdown).map((heading) => [heading.level, heading.text])).toEqual([
      [2, 'Первый'],
      [3, 'Вложенный'],
      [2, 'Второй'],
    ]);
  });

  it('slugifies Cyrillic and strips inline formatting', () => {
    const headings = extractHeadings('## Предметная область\n### Метод `grid-forest`');
    expect(headings[0].id).toBe('предметная-область');
    expect(headings[1]).toMatchObject({ text: 'Метод grid-forest', id: 'метод-grid-forest' });
  });

  it('deduplicates repeated headings', () => {
    const headings = extractHeadings('## Следствия\n## Следствия');
    expect(headings.map((heading) => heading.id)).toEqual(['следствия', 'следствия-1']);
  });
});
