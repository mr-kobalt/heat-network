import { useCallback, useEffect, useRef, useState } from 'react';
import { AppShell, Badge, Box, Burger, Group, Title, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { MapView } from './map/MapView';
import { DataSourcePanel } from './components/DataSourcePanel';
import { VariantSummaryPanel } from './components/VariantSummaryPanel';
import { LayersPanel } from './components/LayersPanel';

const MIN_NAV_WIDTH = 240;
const MAX_NAV_WIDTH = 640;
const DEFAULT_NAV_WIDTH = 360;
const NAV_WIDTH_KEY = 'visualizer.navWidth';

function readNavWidth(): number {
  const stored = Number(localStorage.getItem(NAV_WIDTH_KEY));
  if (Number.isFinite(stored) && stored >= MIN_NAV_WIDTH && stored <= MAX_NAV_WIDTH) {
    return stored;
  }
  return DEFAULT_NAV_WIDTH;
}

export function App() {
  const [opened, { toggle }] = useDisclosure(false);
  const [navWidth, setNavWidth] = useState(readNavWidth);
  const dragRef = useRef<{ startX: number; startWidth: number } | null>(null);

  useEffect(() => {
    localStorage.setItem(NAV_WIDTH_KEY, String(Math.round(navWidth)));
  }, [navWidth]);

  const onPointerDown = useCallback(
    (event: React.PointerEvent<HTMLDivElement>) => {
      dragRef.current = { startX: event.clientX, startWidth: navWidth };
      event.currentTarget.setPointerCapture(event.pointerId);
    },
    [navWidth],
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
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  }, []);

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: navWidth, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding={0}
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="xs" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <Title order={4}>Трассы теплосети</Title>
            <Badge variant="light">ТП v2</Badge>
          </Group>
          <Text size="xs" c="dimmed" visibleFrom="md">
            визуализатор (ADR-0013), вне оцениваемой поставки
          </Text>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="sm" style={{ overflowY: 'auto' }}>
        <DataSourcePanel />
        <VariantSummaryPanel />
        <LayersPanel />
      </AppShell.Navbar>

      <AppShell.Main style={{ position: 'relative', height: '100vh' }}>
        <Box
          visibleFrom="sm"
          role="separator"
          aria-orientation="vertical"
          aria-label="Изменить ширину панели"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          style={{
            position: 'absolute',
            left: 'calc(var(--app-shell-navbar-offset, 0px) - 3px)',
            top: 'var(--app-shell-header-offset, 0px)',
            bottom: 0,
            width: 6,
            cursor: 'col-resize',
            zIndex: 50,
            touchAction: 'none',
          }}
        />
        <MapView />
      </AppShell.Main>
    </AppShell>
  );
}
