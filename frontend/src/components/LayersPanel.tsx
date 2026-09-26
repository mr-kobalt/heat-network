import type { ReactNode } from 'react';
import { ActionIcon, Group, Popover, SegmentedControl, Stack, Text } from '@mantine/core';
import { IconCheck, IconEye, IconEyeOff, IconTextSize, IconX } from '@tabler/icons-react';
import { useStore } from '../store';
import type { LayerKey, LayerMode } from '../store';
import type { BasemapId } from '../map/style';
import { LegendSwatch } from './LegendSwatch';
import { DIAMETER_TABLE } from '../map/paint';
import {
  CONNECTION_POINT_COLORS,
  OKS_TARGET_COLOR,
  RESTRICTION_INACTIVE_COLOR,
  RESTRICTION_STYLES,
} from '../map/restrictions';
import {
  CHAMBER_EXISTING_COLOR,
  CHAMBER_NEW_COLOR,
  SOURCE_COLOR,
  TECHNICAL_NODE_COLOR,
} from '../map/visuals';

interface RowProps {
  layer: LayerKey;
  label: string;
  swatch: ReactNode;
  info?: ReactNode;
  /** Тройной режим (выкл / графика / графика+подписи) вместо простого вкл/выкл. */
  triple?: boolean;
}

/** Иконка выключенного состояния — приглушённый перечёркнутый глаз. */
const OFF_ICON = <IconEyeOff size={15} color="var(--mantine-color-gray-5)" />;

function ToggleOption({ title, children }: { title: string; children: ReactNode }) {
  return (
    <span
      title={title}
      aria-label={title}
      style={{ display: 'inline-flex', alignItems: 'center', gap: 2 }}
    >
      {children}
    </span>
  );
}

function LegendRow({ layer, label, swatch, info, triple = false }: RowProps) {
  const mode = useStore((state) => state.layerMode[layer]);
  const setLayerMode = useStore((state) => state.setLayerMode);
  // Глаз «горит», пока графика видима (geo или labels).
  const eyeIcon = (
    <IconEye
      size={15}
      color={mode === 'off' ? undefined : 'var(--mantine-color-indigo-6)'}
    />
  );
  const data = triple
    ? [
        { value: 'off', label: <ToggleOption title="Выкл">{OFF_ICON}</ToggleOption> },
        { value: 'geo', label: <ToggleOption title="Графика">{eyeIcon}</ToggleOption> },
        {
          value: 'labels',
          label: <ToggleOption title="Графика и подписи"><IconTextSize size={15} /></ToggleOption>,
        },
      ]
    : [
        { value: 'off', label: <ToggleOption title="Выкл">{OFF_ICON}</ToggleOption> },
        { value: 'on', label: <ToggleOption title="Вкл">{eyeIcon}</ToggleOption> },
      ];
  const value = triple ? mode : mode === 'off' ? 'off' : 'on';
  return (
    <Group gap={6} wrap="nowrap" align="center">
      <span
        className="layer-mode-toggle"
        data-labels={triple && mode === 'labels' ? 'true' : undefined}
      >
        <SegmentedControl
          size="xs"
          data={data}
          value={value}
          onChange={(next) => setLayerMode(
            layer,
            triple ? (next as LayerMode) : next === 'on' ? 'geo' : 'off',
          )}
        />
      </span>
      <LegendSwatchSlot>{swatch}</LegendSwatchSlot>
      <Text size="sm">{label}</Text>
      {info}
    </Group>
  );
}

function LegendSwatchSlot({ children }: { children: ReactNode }) {
  return <span style={{ display: 'inline-flex', alignItems: 'center' }}>{children}</span>;
}

function InfoPopover({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Popover width={340} position="right" withArrow shadow="md" withinPortal>
      <Popover.Target>
        <ActionIcon
          component="span"
          variant="subtle"
          size="sm"
          radius="xl"
          color="gray"
          aria-label={title}
          title={title}
          onClick={(event) => event.stopPropagation()}
        >
          ?
        </ActionIcon>
      </Popover.Target>
      <Popover.Dropdown onClick={(event) => event.stopPropagation()}>
        <Text size="sm" fw={600} mb={4}>
          {title}
        </Text>
        {children}
      </Popover.Dropdown>
    </Popover>
  );
}

function LegendLine({ color, width, label }: { color: string; width: number; label: string }) {
  return (
    <Group gap={8} wrap="nowrap">
      <LegendSwatch variant="line" color={color} width={Math.max(2, width)} />
      <Text size="sm">{label}</Text>
    </Group>
  );
}

function DiameterHint({ existing }: { existing: boolean }) {
  return (
    <InfoPopover title={existing ? 'Существующая сеть: viridis (ч/б)' : 'Новая сеть: viridis'}>
      <Stack gap={4}>
        {DIAMETER_TABLE.map((row) => (
          <LegendLine
            key={row.diameter}
            color={existing ? row.grayColor : row.color}
            width={3}
            label={`Ду ${row.diameter} мм — ${row.pairWidth.toFixed(2)} м`}
          />
        ))}
      </Stack>
      <Text size="xs" c="dimmed" mt={6}>
        Цвет — viridis по Ду. Ширина: пропорционально Ду либо реальная ширина пары
        по таблице 1 ТП v2 (переключатель «Реальный масштаб труб»).
      </Text>
      {!existing && (
        <Text size="xs" c="dimmed" mt={4}>
          Специальные проходы отмечены точечной линией.
        </Text>
      )}
    </InfoPopover>
  );
}

function PipeScaleRow() {
  const realPipeScale = useStore((state) => state.realPipeScale);
  const toggleRealPipeScale = useStore((state) => state.toggleRealPipeScale);
  return (
    <Group gap={6} wrap="nowrap" align="center">
      <SegmentedControl
        size="xs"
        value={realPipeScale ? 'on' : 'off'}
        onChange={(value) => {
          if ((value === 'on') !== realPipeScale) {
            toggleRealPipeScale();
          }
        }}
        data={[
          {
            value: 'off',
            label: (
              <ToggleOption title="Пропорционально Ду">
                <IconX size={15} color="var(--mantine-color-gray-5)" />
              </ToggleOption>
            ),
          },
          {
            value: 'on',
            label: (
              <ToggleOption title="Реальная ширина пары">
                <IconCheck size={15} />
              </ToggleOption>
            ),
          },
        ]}
      />
      <Text size="sm">Реальный масштаб труб</Text>
      <InfoPopover title="Реальный масштаб труб">
        <Text size="sm">
          Включено: ширина линии — реальная ширина пары по таблице 1 ТП v2
          (0,40…3,45 м), поэтому на обзорном зуме трубы выглядят тонкими.
          Выключено: ширина пропорциональна Ду.
        </Text>
      </InfoPopover>
    </Group>
  );
}

function ChambersHint() {
  return (
    <InfoPopover title="Тепловые камеры">
      <Stack gap={4}>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="square-dot" color={CHAMBER_EXISTING_COLOR} />
          <Text size="sm">существующая</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="square-dot" color={CHAMBER_NEW_COLOR} />
          <Text size="sm">новая</Text>
        </Group>
      </Stack>
    </InfoPopover>
  );
}

function ConnectionPointsHint() {
  return (
    <InfoPopover title="Точки подключения ОКС">
      <Stack gap={4}>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="circle" color={CONNECTION_POINT_COLORS.connected} />
          <Text size="sm">подключена</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="circle" color={CONNECTION_POINT_COLORS.unconnected} />
          <Text size="sm">не подключена (есть в результате)</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="circle" color={CONNECTION_POINT_COLORS.pending} />
          <Text size="sm">результат не загружен</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="circle" color={SOURCE_COLOR} />
          <Text size="sm">источник тепла</Text>
        </Group>
      </Stack>
    </InfoPopover>
  );
}

function RestrictionsHint() {
  return (
    <InfoPopover title="Ограничения (таблица 2)">
      <Stack gap={4}>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="polygon" color={OKS_TARGET_COLOR} />
          <Text size="sm">ОКС с точкой подключения</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="polygon" color={RESTRICTION_INACTIVE_COLOR} />
          <Text size="sm">ОКС без точки / прочие</Text>
        </Group>
      </Stack>
      <Stack gap={4} mt={6}>
        {Object.entries(RESTRICTION_STYLES).filter(([type]) => type !== 'oks').map(([type, style]) => (
          <Group key={type} gap={8} wrap="nowrap" justify="space-between">
            <Group gap={8} wrap="nowrap">
              <LegendSwatch variant="polygon" color={style.color} />
              <Text size="sm">{style.label}</Text>
            </Group>
            <Text size="sm" c="dimmed">
              {style.bufferMeters} м
            </Text>
          </Group>
        ))}
      </Stack>
      <Text size="xs" c="dimmed" mt={6}>
        «Зоны мин. расстояний» показывают буферы этих расстояний вокруг объектов.
      </Text>
    </InfoPopover>
  );
}

export function LayersPanel() {
  return (
    <Stack gap={8}>
      <BasemapControl />
      <LegendRow
        layer="existingNetwork"
        label="Существующая сеть"
        swatch={<LegendSwatch variant="line" color={DIAMETER_TABLE[8].grayColor} width={3} />}
        info={<DiameterHint existing />}
        triple
      />
      <LegendRow
        layer="newNetwork"
        label="Новая сеть"
        swatch={<LegendSwatch variant="line" color={DIAMETER_TABLE[8].color} width={4} />}
        info={<DiameterHint existing={false} />}
        triple
      />
      <LegendRow
        layer="connectionPoints"
        label="Точки подключения"
        swatch={<LegendSwatch variant="circle" color={SOURCE_COLOR} />}
        info={<ConnectionPointsHint />}
        triple
      />
      <LegendRow
        layer="chambers"
        label="Тепловые камеры"
        swatch={<LegendSwatch variant="square-dot" color={CHAMBER_NEW_COLOR} />}
        info={<ChambersHint />}
        triple
      />
      <LegendRow
        layer="technicalNodes"
        label="Технические узлы"
        swatch={<LegendSwatch variant="hollow-circle" color={TECHNICAL_NODE_COLOR} />}
      />
      <LegendRow
        layer="restrictions"
        label="Ограничения"
        swatch={<LegendSwatch variant="polygon" color={OKS_TARGET_COLOR} />}
        info={<RestrictionsHint />}
      />
      <LegendRow
        layer="restrictionBuffers"
        label="Зоны мин. расстояний"
        swatch={<LegendSwatch variant="stripe" color="#dc2626" />}
      />
      <PipeScaleRow />
    </Stack>
  );
}

const BASEMAP_OPTIONS: Array<{ value: BasemapId; label: string }> = [
  { value: 'osm', label: 'OSM' },
  { value: 'grid', label: 'Чертёжная' },
  { value: 'none', label: 'Белая' },
];

function BasemapControl() {
  const basemap = useStore((state) => state.basemap);
  const setBasemap = useStore((state) => state.setBasemap);
  return (
    <Group gap={6} wrap="nowrap" align="center">
      <SegmentedControl
        size="xs"
        data={BASEMAP_OPTIONS}
        value={basemap}
        onChange={(value) => setBasemap(value as BasemapId)}
      />
      <Text size="sm">Подложка</Text>
    </Group>
  );
}
