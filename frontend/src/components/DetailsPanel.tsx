import { Fragment, useMemo } from 'react';
import { FeatureProperties, GeoFeature } from '../types';
import { useStore } from '../store';
import {
  ActionIcon,
  Badge,
  Box,
  CopyButton,
  Divider,
  Group,
  ScrollArea,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { IconCheck, IconCopy, IconX } from '@tabler/icons-react';
import { popupWidth } from './popupWidth';
import {
  buildNodeGraph,
  buildNodeIndex,
  featureAnchor,
  nodeLinks,
  nodeTypeLabel,
} from './nodeGraph';

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
  object_type: 'Тип',
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

/** Поля, скрываемые в карточке (id — в заголовке, узлы — в переходах). */
const HIDDEN_FIELDS = new Set(['id', 'start_node_id', 'end_node_id']);

/** Единый стиль чипов заголовка (название объекта и id) — одинаковая высота. */
const HEADER_CHIP_STYLE = {
  textTransform: 'none',
  fontWeight: 500,
  maxWidth: '100%',
  height: 30,
  lineHeight: 1,
  display: 'inline-flex',
  alignItems: 'center',
  padding: '0 10px',
} as const;

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
  const selectAndCenter = useStore((state) => state.selectAndCenter);
  const setHovered = useStore((state) => state.setHovered);
  const input = useStore((state) => state.input);
  const result = useStore((state) => state.result);
  const activeVariant = useStore((state) => state.activeVariant);
  const selected = feature ?? storedSelected;
  const close = onClose ?? (() => select(null));

  const graph = useMemo(
    () => buildNodeGraph(result, activeVariant),
    [result, activeVariant],
  );
  const nodeIndex = useMemo(
    () => buildNodeIndex(input, result, activeVariant),
    [input, result, activeVariant],
  );
  const links = useMemo(
    () => (selected
      ? nodeLinks(selected, graph)
      : { previous: [] as string[], next: [] as string[] }),
    [selected, graph],
  );

  if (!selected) {
    return (
      <Text size="sm" c="dimmed">
        Выберите объект на карте, чтобы увидеть его параметры.
      </Text>
    );
  }

  const properties = selected.properties;
  const objectType = String(properties.object_type ?? '');
  const objectId = properties.id != null ? String(properties.id) : null;
  const ordered = PREFERRED_ORDER.filter((key) => key in properties && !HIDDEN_FIELDS.has(key));
  const rest = Object.keys(properties).filter(
    (key) => !HIDDEN_FIELDS.has(key) && !PREFERRED_ORDER.includes(key),
  );
  const keys = [...ordered, ...rest];
  const columns = objectType === 'heat_network' ? 2 : 1;

  const transitionChip = (id: string) => {
    const node = nodeIndex.get(id);
    const anchor = node ? featureAnchor(node) : null;
    const clickable = Boolean(node && anchor);
    const typeName = node ? nodeTypeLabel(node.properties.object_type) : '';
    const label = typeName ? `${typeName} ${id}` : id;
    return (
      <Badge
        key={id}
        variant={clickable ? 'light' : 'outline'}
        color={clickable ? 'indigo' : 'gray'}
        radius="sm"
        size="sm"
        title={id}
        style={{
          cursor: clickable ? 'pointer' : 'default',
          textTransform: 'none',
          fontWeight: 500,
          maxWidth: '100%',
          whiteSpace: 'normal',
        }}
        onClick={clickable
          ? () => selectAndCenter(node as GeoFeature, anchor as [number, number])
          : undefined}
        onMouseEnter={clickable ? () => setHovered(node as GeoFeature) : undefined}
        onMouseLeave={clickable ? () => setHovered(null) : undefined}
      >
        {label}
      </Badge>
    );
  };

  const header = (
    <Group justify="space-between" align="flex-start" wrap="nowrap" gap="sm">
      <Group gap={6} wrap="wrap" style={{ minWidth: 0 }}>
        <Badge size="lg" variant="light" radius="sm" style={HEADER_CHIP_STYLE}>
          {OBJECT_LABELS[objectType] ?? objectType}
        </Badge>
        {objectId && (
          <CopyButton value={objectId} timeout={1500}>
            {({ copied, copy }) => (
              <Badge
                variant="light"
                color={copied ? 'teal' : 'gray'}
                radius="sm"
                size="lg"
                onClick={copy}
                title="Скопировать id"
                style={{ ...HEADER_CHIP_STYLE, cursor: 'pointer' }}
              >
                <Group gap={6} wrap="nowrap" style={{ minWidth: 0 }}>
                  <Text
                    size="xs"
                    style={{ fontFamily: 'monospace', overflowWrap: 'anywhere', minWidth: 0 }}
                  >
                    {objectId}
                  </Text>
                  {copied ? <IconCheck size={14} /> : <IconCopy size={14} />}
                </Group>
              </Badge>
            )}
          </CopyButton>
        )}
      </Group>
      <ActionIcon
        variant="subtle"
        color="gray"
        size="sm"
        aria-label="Закрыть"
        title="Закрыть"
        onClick={close}
      >
        <IconX size={16} />
      </ActionIcon>
    </Group>
  );

  const transitions = (links.previous.length > 0 || links.next.length > 0) && (
    <>
      <Divider my={2} />
      <Group wrap="nowrap" align="center" gap="xs" style={{ minWidth: 0 }}>
        <Text size="lg" c="dimmed" aria-hidden>◀</Text>
        <Stack gap={4} style={{ flex: 1, minWidth: 0 }}>
          {links.previous.map(transitionChip)}
        </Stack>
        <Stack gap={4} style={{ flex: 1, minWidth: 0 }}>
          {links.next.map(transitionChip)}
        </Stack>
        <Text size="lg" c="dimmed" aria-hidden>▶</Text>
      </Group>
    </>
  );

  if (compact) {
    const gridTemplate = columns === 2
      ? 'repeat(2, minmax(0, max-content) minmax(0, 1fr))'
      : 'minmax(0, max-content) minmax(0, 1fr)';
    return (
      <Stack
        gap={6}
        w={popupWidth(objectType)}
        style={{ maxWidth: '100%', boxSizing: 'border-box', overflowX: 'hidden' }}
      >
        {header}
        <Box style={{ display: 'grid', gridTemplateColumns: gridTemplate, columnGap: 12, rowGap: 2 }}>
          {keys.map((key) => (
            <Fragment key={key}>
              <Text size="xs" c="dimmed" style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                {PROP_LABELS[key] ?? key}
              </Text>
              <Text size="xs" fw={600} ta="right" style={{ minWidth: 0, overflowWrap: 'anywhere' }}>
                {formatValue(key, properties[key as keyof FeatureProperties])}
              </Text>
            </Fragment>
          ))}
        </Box>
        {transitions}
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
        {transitions}
      </ScrollArea>
    </Stack>
  );
}

function formatValue(key: string, value: unknown): string {
  if (value === null || value === undefined) {
    return '—';
  }
  if (key === 'laying_method') {
    return value === 'special' ? 'спецпроход' : 'обычный';
  }
  if ((key === 'cost' || key.endsWith('_cost')) && typeof value === 'number') {
    return `${value.toLocaleString('ru-RU')} руб.`;
  }
  if (Array.isArray(value)) {
    return value.length > 0 ? value.join(', ') : '—';
  }
  if (typeof value === 'number') {
    return Number.isInteger(value) ? String(value) : value.toFixed(2);
  }
  return String(value);
}
