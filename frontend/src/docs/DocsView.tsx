import { useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeSlug from 'rehype-slug';
import {
  Alert,
  Anchor,
  Box,
  Group,
  Loader,
  NavLink,
  ScrollArea,
  Stack,
  Text,
  TextInput,
  UnstyledButton,
} from '@mantine/core';
import { IconFileText, IconFolder, IconSearch } from '@tabler/icons-react';
import type { ReactNode } from 'react';
import { INDEX_DOC, firstHeading, hasDoc, loadDoc } from './registry';
import { buildDocTree, resolveDocPath } from './nav';
import type { DocTreeNode } from './nav';
import { extractHeadings } from './toc';
import type { TocHeading } from './toc';

const NAV_WIDTH = 300;
const TOC_WIDTH = 232;

function isExternal(href: string): boolean {
  return /^[a-z][a-z0-9+.-]*:\/\//i.test(href) || href.startsWith('mailto:');
}

/** Каталоги-предки документа (для раскрытия ветки в дереве). */
function folderAncestors(path: string): string[] {
  const segments = path.split('/');
  const result: string[] = [];
  let prefix = '';
  for (let i = 0; i < segments.length - 1; i++) {
    prefix = prefix ? `${prefix}/${segments[i]}` : segments[i];
    result.push(prefix);
  }
  return result;
}

/** Оставляет ветки, содержащие совпадение; каталоги при поиске раскрываются. */
function filterTree(nodes: DocTreeNode[], query: string): DocTreeNode[] {
  const result: DocTreeNode[] = [];
  for (const node of nodes) {
    if (node.children) {
      const children = filterTree(node.children, query);
      if (children.length > 0) {
        result.push({ ...node, children });
      }
      continue;
    }
    const haystack = `${node.label} ${node.path ?? ''}`.toLowerCase();
    if (haystack.includes(query)) {
      result.push(node);
    }
  }
  return result;
}

function DocTreeBranch({
  nodes,
  current,
  opened,
  forceOpen,
  onToggle,
  onNavigate,
}: {
  nodes: DocTreeNode[];
  current: string;
  opened: Record<string, boolean>;
  forceOpen: boolean;
  onToggle: (id: string) => void;
  onNavigate: (path: string) => void;
}) {
  return (
    <>
      {nodes.map((node) => {
        if (node.children) {
          return (
            <NavLink
              key={node.id}
              label={node.label}
              opened={forceOpen || Boolean(opened[node.id])}
              onChange={() => onToggle(node.id)}
              leftSection={<IconFolder size={14} />}
              childrenOffset={12}
              styles={{ label: { fontSize: 12, fontWeight: 600 } }}
            >
              <DocTreeBranch
                nodes={node.children}
                current={current}
                opened={opened}
                forceOpen={forceOpen}
                onToggle={onToggle}
                onNavigate={onNavigate}
              />
            </NavLink>
          );
        }
        return (
          <NavLink
            key={node.id}
            label={node.label}
            active={node.path === current}
            onClick={() => node.path && onNavigate(node.path)}
            leftSection={<IconFileText size={14} />}
            styles={{ label: { fontSize: 12, whiteSpace: 'normal' } }}
          />
        );
      })}
    </>
  );
}

function TableOfContents({
  headings,
  activeId,
  onSelect,
}: {
  headings: TocHeading[];
  activeId: string | null;
  onSelect: (id: string) => void;
}) {
  return (
    <Box
      visibleFrom="md"
      style={{
        width: TOC_WIDTH,
        flexShrink: 0,
        borderLeft: '1px solid var(--mantine-color-gray-3)',
        height: '100%',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <Text size="xs" fw={700} c="dimmed" tt="uppercase" px="sm" py="xs">
        Содержание
      </Text>
      <ScrollArea style={{ flex: 1 }} type="auto" offsetScrollbars>
        <nav className="docs-toc">
          {headings.map((heading) => (
            <UnstyledButton
              key={heading.id}
              className="docs-toc-item"
              data-active={heading.id === activeId ? 'true' : undefined}
              data-level={heading.level}
              onClick={() => onSelect(heading.id)}
              style={{ paddingLeft: 8 + (heading.level - 2) * 14 }}
            >
              {heading.text}
            </UnstyledButton>
          ))}
        </nav>
      </ScrollArea>
    </Box>
  );
}

export function DocsView({
  path,
  onNavigate,
}: {
  path: string | null;
  onNavigate: (path: string) => void;
}) {
  const tree = useMemo(() => buildDocTree(), []);
  const [query, setQuery] = useState('');
  const [opened, setOpened] = useState<Record<string, boolean>>({});
  const [content, setContent] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [activeId, setActiveId] = useState<string | null>(null);
  const viewportRef = useRef<HTMLDivElement>(null);
  const contentRef = useRef<HTMLDivElement>(null);

  const current = path && hasDoc(path) ? path : INDEX_DOC;

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setError(null);
    setActiveId(null);
    loadDoc(current)
      .then((text) => {
        if (!cancelled) {
          setContent(text);
          setLoading(false);
          viewportRef.current?.scrollTo({ top: 0 });
        }
      })
      .catch((exception) => {
        if (!cancelled) {
          setError(exception instanceof Error ? exception.message : String(exception));
          setContent('');
          setLoading(false);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [current]);

  // Раскрываем ветку текущего документа.
  useEffect(() => {
    const ancestors = folderAncestors(current);
    if (ancestors.length === 0) {
      return;
    }
    setOpened((previous) => {
      const next = { ...previous };
      let changed = false;
      for (const id of ancestors) {
        if (!next[id]) {
          next[id] = true;
          changed = true;
        }
      }
      return changed ? next : previous;
    });
  }, [current]);

  const normalizedQuery = query.trim().toLowerCase();
  const visibleTree = normalizedQuery ? filterTree(tree, normalizedQuery) : tree;

  const headings = useMemo(() => extractHeadings(content), [content]);

  const onScroll = () => {
    const root = contentRef.current;
    const viewport = viewportRef.current;
    if (!root || !viewport) {
      return;
    }
    const top = viewport.getBoundingClientRect().top + 32;
    const elements = Array.from(root.querySelectorAll<HTMLElement>('h2[id], h3[id]'));
    let active: string | null = elements.length > 0 ? elements[0].id : null;
    for (const element of elements) {
      if (element.getBoundingClientRect().top <= top) {
        active = element.id;
      } else {
        break;
      }
    }
    setActiveId(active);
  };

  const scrollToHeading = (id: string) => {
    const target = contentRef.current?.querySelector<HTMLElement>(`#${CSS.escape(id)}`);
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
    setActiveId(id);
  };

  const title = firstHeading(content, current);
  // Первый заголовок показываем отдельно, чтобы не дублировать H1.
  const body = content.replace(/^#\s+.+(?:\r?\n|$)/, '');

  const renderLink = (href: string | undefined, children: ReactNode) => {
    if (!href) {
      return <Text span>{children}</Text>;
    }
    if (href.startsWith('#')) {
      return <Anchor href={href}>{children}</Anchor>;
    }
    if (isExternal(href)) {
      return (
        <Anchor href={href} target="_blank" rel="noreferrer">
          {children}
        </Anchor>
      );
    }
    const target = resolveDocPath(current, href);
    if (target && target.endsWith('.md') && hasDoc(target)) {
      return (
        <Anchor
          href={`#docs/${target}`}
          onClick={(event) => {
            event.preventDefault();
            onNavigate(target);
          }}
        >
          {children}
        </Anchor>
      );
    }
    return (
      <Text span c="dimmed" title="Файл недоступен в веб-версии">
        {children}
      </Text>
    );
  };

  return (
    <Group gap={0} align="stretch" wrap="nowrap" h="100%" style={{ minHeight: 0 }}>
      <Box
        style={{
          width: NAV_WIDTH,
          flexShrink: 0,
          borderRight: '1px solid var(--mantine-color-gray-3)',
          height: '100%',
          display: 'flex',
          flexDirection: 'column',
        }}
      >
        <Box p="sm" style={{ borderBottom: '1px solid var(--mantine-color-gray-3)' }}>
          <TextInput
            size="xs"
            placeholder="Поиск по документации"
            leftSection={<IconSearch size={14} />}
            value={query}
            onChange={(event) => setQuery(event.currentTarget.value)}
          />
        </Box>
        <ScrollArea style={{ flex: 1 }} type="auto" offsetScrollbars>
          <Stack gap={0} p="xs">
            {visibleTree.map((node) => (
              <DocTreeBranch
                key={node.id}
                nodes={[node]}
                current={current}
                opened={opened}
                forceOpen={Boolean(normalizedQuery)}
                onToggle={(id) => setOpened((prev) => ({ ...prev, [id]: !prev[id] }))}
                onNavigate={onNavigate}
              />
            ))}
            {visibleTree.length === 0 && (
              <Text size="xs" c="dimmed" p="xs">
                Ничего не найдено.
              </Text>
            )}
          </Stack>
        </ScrollArea>
      </Box>

      <ScrollArea
        style={{ flex: 1, minWidth: 0 }}
        type="auto"
        offsetScrollbars
        viewportRef={viewportRef}
        onScrollPositionChange={onScroll}
      >
        <Box ref={contentRef} className="docs-content" p="lg" maw={1100} mx="auto">
          {loading && (
            <Group gap="xs">
              <Loader size="sm" />
              <Text size="sm" c="dimmed">Загрузка…</Text>
            </Group>
          )}
          {error && (
            <Alert color="red" title="Не удалось открыть документ">
              {error}
            </Alert>
          )}
          {!loading && !error && (
            <>
              <Text component="h1" fz={28} fw={700} mb="md">
                {title}
              </Text>
              <ReactMarkdown
                remarkPlugins={[remarkGfm]}
                rehypePlugins={[rehypeSlug]}
                components={{
                  a: ({ href, children }) => renderLink(href, children),
                }}
              >
                {body}
              </ReactMarkdown>
            </>
          )}
        </Box>
      </ScrollArea>

      {!loading && !error && headings.length > 0 && (
        <TableOfContents headings={headings} activeId={activeId} onSelect={scrollToHeading} />
      )}
    </Group>
  );
}
