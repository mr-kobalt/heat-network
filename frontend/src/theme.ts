import { createTheme } from '@mantine/core';

const SANS_STACK = 'Inter, system-ui, -apple-system, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';

/**
 * Единая тема визуализатора (ADR-0056): крупная типографика, единый радиус и
 * акцент, локальный шрифт Inter (офлайн, ADR-0012).
 */
export const theme = createTheme({
  primaryColor: 'indigo',
  defaultRadius: 'md',
  fontFamily: SANS_STACK,
  headings: {
    fontFamily: SANS_STACK,
    fontWeight: '700',
    sizes: {
      h1: { fontSize: '2rem', lineHeight: '1.2' },
      h2: { fontSize: '1.6rem', lineHeight: '1.25' },
      h3: { fontSize: '1.3rem', lineHeight: '1.3' },
      h4: { fontSize: '1.1rem', lineHeight: '1.35' },
      h5: { fontSize: '1rem', lineHeight: '1.4' },
    },
  },
  fontSizes: {
    xs: '0.875rem',
    sm: '1rem',
    md: '1.1rem',
    lg: '1.25rem',
    xl: '1.45rem',
  },
  defaultGradient: { from: 'indigo', to: 'cyan', deg: 45 },
});
