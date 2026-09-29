import { useEffect, useId, useState } from 'react';

/**
 * Рендер mermaid-диаграмм в разделе «Документация» (ADR-0074).
 * Библиотека подгружается лениво (dynamic import), чтобы не попадать в основной
 * бандл карты; рендер полностью офлайн.
 */
interface MermaidApi {
  initialize: (config: Record<string, unknown>) => void;
  render: (id: string, chart: string) => Promise<{ svg: string }>;
}

let mermaidLoader: Promise<MermaidApi> | null = null;
function loadMermaid(): Promise<MermaidApi> {
  if (!mermaidLoader) {
    mermaidLoader = import('mermaid').then((mod) => {
      const namespace = mod as unknown as { default?: MermaidApi } & MermaidApi;
      return namespace.default ?? namespace;
    });
  }
  return mermaidLoader;
}

/** Извлекает mermaid-код из markdown-блока; {@code null}, если это не mermaid. */
export function mermaidChart(className: string | undefined, children: unknown): string | null {
  const language = /language-(\w+)/.exec(className ?? '')?.[1];
  if (language !== 'mermaid') {
    return null;
  }
  return String(children).replace(/\n$/, '');
}

export function Mermaid({ chart }: { chart: string }) {
  const rawId = useId().replace(/[^a-zA-Z0-9_-]/g, '');
  const [svg, setSvg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setSvg(null);
    setError(null);
    loadMermaid()
      .then(async (mermaid) => {
        mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: 'neutral' });
        const { svg: rendered } = await mermaid.render(`mermaid-${rawId}`, chart);
        if (!cancelled) {
          setSvg(rendered);
        }
      })
      .catch((exception: unknown) => {
        if (!cancelled) {
          setError(exception instanceof Error ? exception.message : String(exception));
        }
      });
    return () => {
      cancelled = true;
    };
  }, [chart, rawId]);

  if (error) {
    return (
      <pre className="mermaid-error" title={error}>
        Не удалось отрисовать диаграмму
      </pre>
    );
  }
  if (!svg) {
    return <div className="mermaid-loading">Диаграмма…</div>;
  }
  return (
    <div className="mermaid" role="img" dangerouslySetInnerHTML={{ __html: svg }} />
  );
}
