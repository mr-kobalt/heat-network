import { Suspense, lazy, useCallback, useEffect, useRef, useState } from 'react';
import {
  AppShell,
  Badge,
  Box,
  Burger,
  Center,
  Group,
  Loader,
  Stack,
  Tabs,
  Title,
} from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { IconApi, IconBook2, IconDatabase, IconMap2, IconStack2 } from '@tabler/icons-react';
import { MapView } from './map/MapView';
import { DataSourcePanel } from './components/DataSourcePanel';
import { VariantSummaryPanel } from './components/VariantSummaryPanel';
import { LayersPanel } from './components/LayersPanel';
import { SideStrip } from './components/SideStrip';
import { PanelToggleButton } from './components/PanelToggleButton';
import { StageDataLoader } from './components/StageDataLoader';
import { useHashRoute } from './useHashRoute';
import type { AppView } from './useHashRoute';
import { useStore } from './store';

const DocsView = lazy(() =>
  import('./docs/DocsView').then((module) => ({ default: module.DocsView })));
const ApiView = lazy(() =>
  import('./components/ApiView').then((module) => ({ default: module.ApiView })));

const MIN_NAV_WIDTH = 240;
const MAX_NAV_WIDTH = 640;
const DEFAULT_NAV_WIDTH = 360;
const STRIP_WIDTH = 68;
const NAV_WIDTH_KEY = 'visualizer.navWidth';
const NAV_COLLAPSED_KEY = 'visualizer.navCollapsed';

function readNavWidth(): number {
  const stored = Number(localStorage.getItem(NAV_WIDTH_KEY));
  if (Number.isFinite(stored) && stored >= MIN_NAV_WIDTH && stored <= MAX_NAV_WIDTH) {
    return stored;
  }
  return DEFAULT_NAV_WIDTH;
}

function readNavCollapsed(): boolean {
  return localStorage.getItem(NAV_COLLAPSED_KEY) === '1';
}

const PANEL_STYLE = { padding: 12, flex: 1, overflowY: 'auto' as const };

/** Область контента под шапкой (для вкладок «Документация» и «API»). */
const VIEW_STYLE = {
  position: 'absolute' as const,
  top: 'var(--app-shell-header-offset, 0px)',
  left: 'var(--app-shell-navbar-offset, 0px)',
  right: 0,
  bottom: 0,
};

function ViewFallback() {
  return (
    <Center h="100%">
      <Loader size="sm" />
    </Center>
  );
}

export function App() {
  const [opened, { toggle }] = useDisclosure(false);
  const [collapsed, setCollapsed] = useState(readNavCollapsed);
  const [navWidth, setNavWidth] = useState(readNavWidth);
  const dragRef = useRef<{ startX: number; startWidth: number } | null>(null);
  const setPanelResizing = useStore((state) => state.setPanelResizing);
  const { route, setView, navigateDoc } = useHashRoute();

  useEffect(() => {
    localStorage.setItem(NAV_WIDTH_KEY, String(Math.round(navWidth)));
  }, [navWidth]);

  useEffect(() => {
    localStorage.setItem(NAV_COLLAPSED_KEY, collapsed ? '1' : '0');
  }, [collapsed]);

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      dragRef.current = { startX: event.clientX, startWidth: navWidth };
      setPanelResizing(true);
      event.currentTarget.setPointerCapture(event.pointerId);
    },
    [navWidth, setPanelResizing],
  );

  const onPointerMove = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag) {
      return;
    }
    const next = Math.min(
      MAX_NAV_WIDTH,
      Math.max(MIN_NAV_WIDTH, drag.startWidth + (event.clientX - drag.startX)),
    );
    setNavWidth(next);
  }, []);

  const onPointerUp = useCallback((event: React.PointerEvent<HTMLDivElement>) => {
    dragRef.current = null;
    setPanelResizing(false);
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }, [setPanelResizing]);

  const navbarWidth = collapsed ? STRIP_WIDTH : STRIP_WIDTH + navWidth;
  const isMap = route.view === 'map';

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{
        width: navbarWidth,
        breakpoint: 'sm',
        collapsed: { mobile: !opened, desktop: !isMap },
      }}
      padding={0}
    >
      <AppShell.Header>
        <Group h="100%" px="md" gap="sm" wrap="nowrap" align="center">
          <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
          <Title order={3} visibleFrom="sm">Трассы теплосети</Title>
          <Badge variant="light" size="lg" radius="sm" visibleFrom="md">
            ТП v2
          </Badge>
          <Tabs
            value={route.view}
            onChange={(value) => setView((value ?? 'map') as AppView)}
            variant="outline"
            ml="auto"
          >
            <Tabs.List>
              <Tabs.Tab value="map" leftSection={<IconMap2 size={16} />}>
                Карта
              </Tabs.Tab>
              <Tabs.Tab value="docs" leftSection={<IconBook2 size={16} />}>
                Документация
              </Tabs.Tab>
              <Tabs.Tab value="api" leftSection={<IconApi size={16} />}>
                API
              </Tabs.Tab>
            </Tabs.List>
          </Tabs>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p={0} style={{ display: 'flex', flexDirection: 'column' }}>
        <Group
          gap={0}
          wrap="nowrap"
          align="stretch"
          style={{ flex: 1, minHeight: 0 }}
        >
          <Box
            style={{
              flexShrink: 0,
              width: STRIP_WIDTH,
              height: '100%',
              borderRight: '1px solid var(--mantine-color-gray-3)',
            }}
          >
            <SideStrip />
          </Box>
          {!collapsed && (
            <Box style={{ flex: 1, minWidth: 0, height: '100%' }}>
              <Tabs
                defaultValue="data"
                keepMounted
                style={{ display: 'flex', flexDirection: 'column', height: '100%' }}
              >
                <Tabs.List grow>
                  <Tabs.Tab
                    value="data"
                    leftSection={<IconDatabase size={16} />}
                    style={{ flex: 1, justifyContent: 'center' }}
                  >
                    Данные
                  </Tabs.Tab>
                  <Tabs.Tab
                    value="layers"
                    leftSection={<IconStack2 size={16} />}
                    style={{ flex: 1, justifyContent: 'center' }}
                  >
                    Слои
                  </Tabs.Tab>
                </Tabs.List>

                <Tabs.Panel value="data" style={PANEL_STYLE}>
                  <Stack gap="md">
                    <DataSourcePanel />
                    <VariantSummaryPanel />
                  </Stack>
                </Tabs.Panel>
                <Tabs.Panel value="layers" style={PANEL_STYLE}>
                  <LayersPanel />
                </Tabs.Panel>
              </Tabs>
            </Box>
          )}
        </Group>
        <PanelToggleButton
          collapsed={collapsed}
          onClick={() => setCollapsed((value) => !value)}
        />
      </AppShell.Navbar>

      <AppShell.Main style={{ position: 'relative', height: '100vh' }}>
        {/* Карта не размонтируется при переходе на «Документацию»/«API»,
            чтобы сохранялись вьюпорт, выбор и состояние расчёта. */}
        <Box style={{ display: isMap ? undefined : 'none' }}>
          {isMap && !collapsed && (
            <Box
              visibleFrom="sm"
              className="nav-resize-handle"
              role="separator"
              aria-orientation="vertical"
              aria-label="Изменить ширину панели"
              onPointerDown={onPointerDown}
              onPointerMove={onPointerMove}
              onPointerUp={onPointerUp}
              style={{
                position: 'absolute',
                left: 'calc(var(--app-shell-navbar-offset, 0px) - 8px)',
                top: 'var(--app-shell-header-offset, 0px)',
                bottom: 0,
                width: 16,
                cursor: 'col-resize',
                zIndex: 50,
                touchAction: 'none',
              }}
            />
          )}
          <MapView visible={isMap} />
        </Box>
        {route.view === 'docs' && (
          <Box style={VIEW_STYLE}>
            <Suspense fallback={<ViewFallback />}>
              <DocsView path={route.docPath} onNavigate={navigateDoc} />
            </Suspense>
          </Box>
        )}
        {route.view === 'api' && (
          <Box style={VIEW_STYLE}>
            <Suspense fallback={<ViewFallback />}>
              <ApiView />
            </Suspense>
          </Box>
        )}
        <StageDataLoader />
      </AppShell.Main>
    </AppShell>
  );
}
