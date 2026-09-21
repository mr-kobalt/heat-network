import { FeatureProperties, GeoFeature } from '../types';
import { useStore } from '../store';
import { Badge, Divider, Group, ScrollArea, Stack, Table, Text } from '@mantine/core';

const OBJECT_LABELS: Record<string, string> = {
  source: 'Источник',
  heat_network: 'Участок тепловой сети',
  heat_chamber: 'Тепловая камера',
  oks_connection_point: 'Точка подключения ОКС',
  restriction: 'Пространственное ограничение',
  technical_node: 'Технический узел',
  variant_summary: 'Сводка варианта',
};

const PROP_LABELS: Record<string, string> = {
  id: 'Идентификатор',
  variant_id: 'Вариант',
  diameter: 'Условный диаметр, мм',
  flow_tph: 'Расход, т/ч',
  length: 'Длина, м',
  cost: 'Стоимость, руб.',
  laying_method: 'Способ прокладки',
  restriction_type: 'Тип ограничения',
  address: 'Адрес',
  start_node_id: 'Начальный узел',
  end_node_id: 'Конечный узел',
  depth_start: 'Глубина в начале, м',
  depth_end: 'Глубина в конце, м',
  rank: 'Ранг',
  construction_cost: 'Новые участки, руб.',
  chamber_construction_cost: 'Новые камеры, руб.',
  existing_chamber_tie_in_count: 'Врезок в существующие камеры',
  existing_chamber_tie_in_cost: 'Стоимость врезок, руб.',
  unconnected_penalty: 'Штраф за неподключённые, руб.',
  calculated_cost: 'Итоговая стоимость, руб.',
  new_network_length: 'Длина новой сети, м',
  score: 'Показатель S',
  unconnected_oks_ids: 'Неподключённые ОКС',
};

const PREFERRED_ORDER = [
  'id',
  'object_type',
  'variant_id',
  'diameter',
  'flow_tph',
  'length',
  'laying_method',
  'cost',
  'rank',
  'calculated_cost',
  'score',
  'start_node_id',
  'end_node_id',
  'restriction_type',
  'address',
  'depth_start',
  'depth_end',
];

export function DetailsPanel({
  compact = false,
  feature,
  onClose,
}: {
  compact?: boolean;
  feature?: GeoFeature | null;
  onClose?: () => void;
}) {
  const storedSelected = useStore((state) => state.selected);
  const select = useStore((state) => state.select);
  const selected = feature ?? storedSelected;
  const close = onClose ?? (() => select(null));

  if (!selected) {
    return (
      <Text size="sm" c="dimmed">
        Выберите объект на карте, чтобы увидеть его параметры.
      </Text>
    );
  }

  const properties = selected.properties;
  const objectType = String(properties.object_type ?? '');
  const ordered = PREFERRED_ORDER.filter((key) => key in properties);
  const rest = Object.keys(properties).filter((key) => !ordered.includes(key));

  return (
    <Stack gap="xs" h={compact ? undefined : '100%'} w={compact ? 300 : undefined}>
      <Group justify="space-between" wrap="nowrap">
        <Badge size="lg" variant="light">
          {OBJECT_LABELS[objectType] ?? objectType}
        </Badge>
        <Text
          size="xs"
          c="dimmed"
          style={{ cursor: 'pointer' }}
          onClick={close}
        >
          закрыть
        </Text>
      </Group>
      <ScrollArea style={compact ? undefined : { flex: 1 }} mah={compact ? 280 : undefined}>
        <Table striped withTableBorder fz="xs">
          <Table.Tbody>
            {[...ordered, ...rest].map((key) => (
              <Table.Tr key={key}>
                <Table.Td fw={600}>{PROP_LABELS[key] ?? key}</Table.Td>
                <Table.Td>{formatValue(key, properties[key as keyof FeatureProperties])}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
        <Divider mt="xs" />
      </ScrollArea>
    </Stack>
  );
}

function formatValue(key: string, value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (key === 'laying_method') {
    return value === 'special' ? 'специальный проход' : 'обычная прокладка';
  }
  if (Array.isArray(value)) {
    return value.length > 0 ? value.join(', ') : '—';
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }
  return String(value);
}
