import { FeatureProperties } from '../types';
import { useStore } from '../store';
import { Badge, Divider, ScrollArea, Stack, Table, Text } from '@mantine/core';

const OBJECT_LABELS: Record<string, string> = {
  source: 'Источник',
  heat_network: 'Участок тепловой сети',
  heat_network_reconstruction: 'Реконструкция участка',
  heat_chamber: 'Тепловая камера',
  heat_chamber_reconstruction: 'Реконструкция камеры',
  oks_connection_point: 'Точка подключения ОКС',
  oks_future: 'Перспективный ОКС',
  oks_existing: 'Существующий ОКС',
  restriction: 'Пространственное ограничение',
  tie_in: 'Точка врезки',
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
  existing_object_id: 'Существующий объект',
  existing_object_type: 'Тип существующего объекта',
  existing_diameter: 'Существующий Ду, мм',
  required_diameter: 'Требуемый Ду, мм',
  depth_start: 'Глубина в начале, м',
  depth_end: 'Глубина в конце, м',
  rank: 'Ранг',
  calculated_cost: 'Итоговая стоимость, руб.',
  construction_cost: 'Новые участки, руб.',
  chamber_construction_cost: 'Новые камеры, руб.',
  chamber_reconstruction_cost: 'Реконструкция камер, руб.',
  reconstruction_cost: 'Реконструкция участков, руб.',
  tie_in_cost: 'Врезки, руб.',
  unconnected_penalty: 'Штраф за неподключённые, руб.',
  new_network_length: 'Длина новой сети, м',
  reconstruction_length: 'Длина реконструкции, м',
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
  'existing_object_id',
  'existing_object_type',
  'existing_diameter',
  'required_diameter',
  'restriction_type',
  'address',
  'depth_start',
  'depth_end',
];

export function DetailsPanel() {
  const selected = useStore((state) => state.selected);

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
    <ScrollArea h="100%">
      <Stack gap="xs">
        <Badge size="lg" variant="light">
          {OBJECT_LABELS[objectType] ?? objectType}
        </Badge>
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
        <Divider />
      </Stack>
    </ScrollArea>
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
