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

/** Оценка высоты попапа: сколько свойств держим в одной колонке. */
function splitColumns(keys: string[]): [string[], string[]] {
  const half = Math.ceil(keys.length / 2);
  return [keys.slice(0, half), keys.slice(half)];
}

function PropertyLine({
  name,
  properties,
}: {
  name: string;
  properties: FeatureProperties;
}) {
  return (
    <Group gap={6} wrap="nowrap" align="baseline" justify="space-between">
      <Text size="xs" c="dimmed" style={{ flexShrink: 0 }}>
        {PROP_LABELS[name] ?? name}
      </Text>
      <Text
        size="xs"
        fw={600}
        ta="right"
        style={{ minWidth: 0, wordBreak: 'break-word' }}
      >
        {formatValue(name, properties[name as keyof FeatureProperties])}
      </Text>
    </Group>
  );
}

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
  const keys = [...ordered, ...rest];
  const special = properties.laying_method === 'special';
  const cost = typeof properties.cost === 'number' ? properties.cost : null;

  const header = (
    <Group justify="space-between" wrap="nowrap">
      <Group gap={4} wrap="nowrap">
        <Badge size="lg" variant="light">
          {OBJECT_LABELS[objectType] ?? objectType}
        </Badge>
        {special && (
          <Badge size="lg" color="orange" variant="filled">
            спецпроход{cost !== null ? ` · ${formatMoney(cost)}` : ''}
          </Badge>
        )}
      </Group>
      <Text size="xs" c="dimmed" style={{ cursor: 'pointer' }} onClick={close}>
        закрыть
      </Text>
    </Group>
  );

  if (compact) {
    // Плотная двухколоночная сетка — весь объект помещается без прокрутки.
    const [left, right] = splitColumns(keys);
    return (
      <Stack gap={6} w={330}>
        {header}
        <Group align="flex-start" gap="md" wrap="nowrap">
          <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
            {left.map((key) => (
              <PropertyLine key={key} name={key} properties={properties} />
            ))}
          </Stack>
          <Stack gap={2} style={{ flex: 1, minWidth: 0 }}>
            {right.map((key) => (
              <PropertyLine key={key} name={key} properties={properties} />
            ))}
          </Stack>
        </Group>
      </Stack>
    );
  }

  return (
    <Stack gap="xs" h="100%">
      {header}
      <ScrollArea style={{ flex: 1 }}>
        <Table striped withTableBorder fz="sm">
          <Table.Tbody>
            {keys.map((key) => (
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

/** Стоимость с разделением разрядов и руб. */
function formatMoney(value: number): string {
  return `${value.toLocaleString('ru-RU')} руб.`;
}

function formatValue(key: string, value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (key === 'laying_method') {
    return value === 'special' ? 'специальный проход' : 'обычная прокладка';
  }
  if ((key === 'cost' || key.endsWith('_cost')) && typeof value === 'number') {
    return formatMoney(value);
  }
  if (Array.isArray(value)) {
    return value.length > 0 ? value.join(', ') : '—';
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }
  return String(value);
}
