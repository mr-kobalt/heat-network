import { LayerKey, useStore } from '../store';
import { Checkbox, Stack, Text } from '@mantine/core';

const LABELS: Record<LayerKey, string> = {
  existingNetwork: 'Существующая сеть',
  restrictions: 'Ограничения',
  connectionPoints: 'Точки подключения / источник',
  newNetwork: 'Новая сеть',
  reconstruction: 'Реконструкция',
  tieIns: 'Врезки',
  chambers: 'Камеры',
  technicalNodes: 'Технические узлы',
};

export function LayersPanel() {
  const visibility = useStore((state) => state.visibility);
  const toggleLayer = useStore((state) => state.toggleLayer);

  return (
    <Stack gap={6}>
      <Text fw={600} size="sm">
        Слои
      </Text>
      {(Object.keys(LABELS) as LayerKey[]).map((key) => (
        <Checkbox
          key={key}
          size="xs"
          label={LABELS[key]}
          checked={visibility[key]}
          onChange={() => toggleLayer(key)}
        />
      ))}
    </Stack>
  );
}
