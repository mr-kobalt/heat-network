import { describe, expect, it } from 'vitest';
import { mermaidChart } from './Mermaid';

describe('mermaidChart', () => {
  it('extracts a fenced mermaid block', () => {
    expect(mermaidChart('language-mermaid', 'flowchart LR\n  A --> B\n')).toBe(
      'flowchart LR\n  A --> B',
    );
  });

  it('returns null for other languages and inline code', () => {
    expect(mermaidChart('language-ts', 'const x = 1;')).toBeNull();
    expect(mermaidChart(undefined, 'plain')).toBeNull();
  });

  it('supports other mermaid diagram kinds', () => {
    expect(mermaidChart('language-mermaid', 'sequenceDiagram\n  A->>B: hi')).toContain(
      'sequenceDiagram',
    );
  });
});
