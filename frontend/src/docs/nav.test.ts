import { describe, expect, it } from 'vitest';
import { buildDocTree, parseDocLinks, resolveDocPath } from './nav';

describe('resolveDocPath', () => {
  it('resolves a link relative to the current document', () => {
    expect(resolveDocPath('01-project/requirements.md', 'charter.md'))
      .toBe('01-project/charter.md');
  });

  it('resolves parent segments', () => {
    expect(resolveDocPath('03-architecture/adr/README.md', '0001-mandated-stack.md'))
      .toBe('03-architecture/adr/0001-mandated-stack.md');
    expect(resolveDocPath('03-architecture/adr/README.md', '../overview.md'))
      .toBe('03-architecture/overview.md');
  });

  it('strips anchors and query strings', () => {
    expect(resolveDocPath('index.md', '01-project/charter.md#история'))
      .toBe('01-project/charter.md');
  });

  it('ignores external links, anchors and mailto', () => {
    expect(resolveDocPath('index.md', 'https://example.com/a.md')).toBeNull();
    expect(resolveDocPath('index.md', '#section')).toBeNull();
    expect(resolveDocPath('index.md', 'mailto:team@example.com')).toBeNull();
  });
});

describe('parseDocLinks', () => {
  it('extracts links in order', () => {
    const markdown = '- [Устав](01-project/charter.md) — цели\n'
      + '- [Требования](01-project/requirements.md#fr) — FR\n'
      + '[внешняя](https://example.com)\n';
    expect(parseDocLinks(markdown)).toEqual([
      { title: 'Устав', href: '01-project/charter.md' },
      { title: 'Требования', href: '01-project/requirements.md#fr' },
      { title: 'внешняя', href: 'https://example.com' },
    ]);
  });
});

describe('buildDocTree', () => {
  const tree = buildDocTree();

  it('puts the root index document first', () => {
    expect(tree[0]).toMatchObject({ path: 'index.md', label: 'Карта документации' });
  });

  it('groups documents into labelled directories', () => {
    const project = tree.find((node) => node.id === '01-project');
    expect(project?.label).toBe('01. Проект');
    expect(project?.children?.some(
      (child) => child.path === '01-project/charter.md' && child.label === 'Устав',
    )).toBe(true);
  });

  it('nests the ADR directory under architecture', () => {
    const architecture = tree.find((node) => node.id === '03-architecture');
    const adr = architecture?.children?.find((node) => node.id === '03-architecture/adr');
    expect(adr?.label).toBe('ADR');
    expect(adr?.children?.some(
      (child) => child.path === '03-architecture/adr/0072-web-docs-swagger-and-run-state.md',
    )).toBe(true);
  });

  it('orders files before folders and keeps stable order', () => {
    const architecture = tree.find((node) => node.id === '03-architecture');
    const children = architecture?.children ?? [];
    const firstFolder = children.findIndex((node) => Boolean(node.children));
    const lastFile = children.map((node) => Boolean(node.children)).lastIndexOf(false);
    expect(firstFolder).toBeGreaterThan(lastFile);
  });
});

