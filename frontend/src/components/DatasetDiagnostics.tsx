import { Stack, Text } from '@mantine/core';
import { useStore } from '../store';

/** Русские подписи для счётчиков типов входных объектов (FR-08). */
const COUNT_LABELS: Record<string, string> = {
  source: 'источники',
  heat_network: 'участки',
  heat_chamber: 'камеры',
  oks_future: 'ОКС перспективные',
  oks_connection_point: 'точки ОКС',
  oks_existing: 'ОКС существующие',
  restriction: 'ограничения',
};

function countLabel(key: string): string {
  return COUNT_LABELS[key] ?? key;
}

/** Компактная сводка по загруженному набору: файл и число объектов. */
export function DatasetDiagnostics() {
  const datasetInfo = useStore((state) => state.datasetInfo);
  if (!datasetInfo) {
    return null;
  }
  const counts = Object.entries(datasetInfo.objectCounts ?? {}).filter(([, value]) => value > 0);
  if (counts.length === 0 && !datasetInfo.originalFilename) {
    return null;
  }
  return (
    <Stack gap={2}>
      {datasetInfo.originalFilename && (
        <Text size="xs" c="dimmed">
          Набор: {datasetInfo.originalFilename}
        </Text>
      )}
      {counts.length > 0 && (
        <Text size="xs" c="dimmed">
          {counts.map(([key, value]) => `${countLabel(key)}: ${value}`).join(' · ')}
        </Text>
      )}
    </Stack>
  );
}
