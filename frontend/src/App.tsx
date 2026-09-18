import { AppShell, Badge, Burger, Divider, Group, Title, Text } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { MapView } from './map/MapView';
import { DataSourcePanel } from './components/DataSourcePanel';
import { VariantSummaryPanel } from './components/VariantSummaryPanel';
import { LayersPanel } from './components/LayersPanel';
import { DetailsPanel } from './components/DetailsPanel';

export function App() {
  const [opened, { toggle }] = useDisclosure(false);

  return (
    <AppShell
      header={{ height: 56 }}
      navbar={{ width: 360, breakpoint: 'sm', collapsed: { mobile: !opened } }}
      padding={0}
    >
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="xs" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" />
            <Title order={4}>Трассы теплосети</Title>
            <Badge variant="light">M1</Badge>
          </Group>
          <Text size="xs" c="dimmed" visibleFrom="md">
            визуализатор (ADR-0013), вне оцениваемой поставки
          </Text>
        </Group>
      </AppShell.Header>

      <AppShell.Navbar p="sm" style={{ overflowY: 'auto' }}>
        <DataSourcePanel />
        <Divider my="sm" />
        <VariantSummaryPanel />
        <Divider my="sm" />
        <LayersPanel />
        <Divider my="sm" />
        <DetailsPanel />
      </AppShell.Navbar>

      <AppShell.Main style={{ position: 'relative', height: '100vh' }}>
        <MapView />
      </AppShell.Main>
    </AppShell>
  );
}
