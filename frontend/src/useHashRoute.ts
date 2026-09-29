import { useCallback, useEffect, useRef, useState } from 'react';

/** Верхнеуровневые разделы приложения (ADR-0072). */
export type AppView = 'map' | 'docs' | 'api';

export interface HashRoute {
  view: AppView;
  /** Путь markdown-документа внутри `docs/` для раздела `docs`. */
  docPath: string | null;
}

export function parseHash(hash: string): HashRoute {
  const raw = hash.replace(/^#/, '');
  const [segment, ...rest] = raw.split('/');
  const docPath = rest.join('/') || null;
  if (segment === 'docs') {
    return { view: 'docs', docPath };
  }
  if (segment === 'api') {
    return { view: 'api', docPath: null };
  }
  return { view: 'map', docPath: null };
}

export function formatHash(route: HashRoute): string {
  if (route.view === 'docs') {
    return route.docPath ? `#docs/${route.docPath}` : '#docs';
  }
  if (route.view === 'api') {
    return '#api';
  }
  return '#map';
}

/**
 * Хэш-роутинг разделов: `#map` (по умолчанию), `#docs[/path]`, `#api`.
 * Смена раздела не трогает состояние расчёта (zustand), карта остаётся
 * смонтированной.
 */
export function useHashRoute() {
  const [route, setRoute] = useState<HashRoute>(() => parseHash(window.location.hash));
  const lastDocRef = useRef<string | null>(route.view === 'docs' ? route.docPath : null);

  useEffect(() => {
    const onHashChange = () => setRoute(parseHash(window.location.hash));
    window.addEventListener('hashchange', onHashChange);
    return () => window.removeEventListener('hashchange', onHashChange);
  }, []);

  useEffect(() => {
    if (route.view === 'docs' && route.docPath) {
      lastDocRef.current = route.docPath;
    }
  }, [route]);

  const navigate = useCallback((next: HashRoute) => {
    const hash = formatHash(next);
    if (window.location.hash !== hash) {
      window.location.hash = hash;
    } else {
      setRoute(next);
    }
  }, []);

  const setView = useCallback((view: AppView) => {
    if (view === 'docs') {
      navigate({ view: 'docs', docPath: lastDocRef.current });
      return;
    }
    navigate({ view, docPath: null });
  }, [navigate]);

  const navigateDoc = useCallback((docPath: string) => {
    navigate({ view: 'docs', docPath });
  }, [navigate]);

  return { route, setView, navigateDoc };
}
