import type { ReactNode } from 'react';
import { ActionIcon, Checkbox, Group, Popover, ScrollArea, Select, Stack, Text } from '@mantine/core';
import { LayerKey, useStore } from '../store';
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
}

function LegendRow({ layer, label, swatch, info }: RowProps) {
  const checked = useStore((state) => state.visibility[layer]);
  const toggleLayer = useStore((state) => state.toggleLayer);
  return (
    <Group gap={4} wrap="nowrap" align="center">
      <Checkbox
        size="xs"
        checked={checked}
        onChange={() => toggleLayer(layer)}
        label={(
          <Group component="span" gap={6} wrap="nowrap" align="center">
            <LegendSwatchSlot>{swatch}</LegendSwatchSlot>
            <Text component="span" size="xs">{label}</Text>
          </Group>
        )}
      />
      {info}
    </Group>
  );
}

function LegendSwatchSlot({ children }: { children: ReactNode }) {
  return <span style={{ display: 'inline-flex', alignItems: 'center' }}>{children}</span>;
}

function InfoPopover({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Popover width={280} position="right" withArrow shadow="md" withinPortal>
      <Popover.Target>
        <ActionIcon
          component="span"
          variant="subtle"
          size="xs"
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
        <Text size="xs" fw={600} mb={4}>
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
      <Text size="xs">{label}</Text>
    </Group>
  );
}

function DiameterHint({ existing }: { existing: boolean }) {
  return (
    <InfoPopover title={existing ? 'Существующая сеть: viridis (ч/б)' : 'Новая сеть: viridis'}>
      <ScrollArea h={240} type="auto">
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
      </ScrollArea>
      <Text size="10px" c="dimmed" mt={6}>
        Цвет — viridis по Ду. Ширина: пропорционально Ду либо реальная ширина пары
        по таблице 1 ТП v2 (переключатель «Реальный масштаб труб»).
      </Text>
    </InfoPopover>
  );
}

function PipeScaleRow() {
  const realPipeScale = useStore((state) => state.realPipeScale);
  const toggleRealPipeScale = useStore((state) => state.toggleRealPipeScale);
  return (
    <Group gap={4} wrap="nowrap" align="center">
      <Checkbox
        size="xs"
        checked={realPipeScale}
        onChange={() => toggleRealPipeScale()}
        label={<Text component="span" size="xs">Реальный масштаб труб</Text>}
      />
      <InfoPopover title="Реальный масштаб труб">
        <Text size="xs">
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
          <Text size="xs">существующая</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="square-dot" color={CHAMBER_NEW_COLOR} />
          <Text size="xs">новая</Text>
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
          <Text size="xs">подключена</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="circle" color={CONNECTION_POINT_COLORS.unconnected} />
          <Text size="xs">не подключена (есть в результате)</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="circle" color={CONNECTION_POINT_COLORS.pending} />
          <Text size="xs">результат не загружен</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="circle" color={SOURCE_COLOR} />
          <Text size="xs">источник тепла</Text>
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
          <Text size="xs">ОКС с точкой подключения</Text>
        </Group>
        <Group gap={8} wrap="nowrap">
          <LegendSwatch variant="polygon" color={RESTRICTION_INACTIVE_COLOR} />
          <Text size="xs">ОКС без точки / прочие</Text>
        </Group>
      </Stack>
      <ScrollArea h={190} mt={6} type="auto">
        <Stack gap={4}>
          {Object.entries(RESTRICTION_STYLES).filter(([type]) => type !== 'oks').map(([type, style]) => (
            <Group key={type} gap={8} wrap="nowrap" justify="space-between">
              <Group gap={8} wrap="nowrap">
                <LegendSwatch variant="polygon" color={style.color} />
                <Text size="xs">{style.label}</Text>
              </Group>
              <Text size="xs" c="dimmed">
                {style.bufferMeters} м
              </Text>
            </Group>
          ))}
        </Stack>
      </ScrollArea>
      <Text size="10px" c="dimmed" mt={6}>
        «Зоны мин. расстояний» показывают буферы этих расстояний вокруг объектов.
      </Text>
    </InfoPopover>
  );
}

export function LayersPanel() {
  return (
    <Stack gap={6}>
      <Text fw={600} size="sm">
        Слои
      </Text>
      <LegendRow
        layer="existingNetwork"
        label="Существующая сеть"
        swatch={<LegendSwatch variant="line" color={DIAMETER_TABLE[8].grayColor} width={3} />}
        info={<DiameterHint existing />}
      />
      <LegendRow
        layer="newNetwork"
        label="Новая сеть"
        swatch={<LegendSwatch variant="line" color={DIAMETER_TABLE[8].color} width={4} />}
        info={<DiameterHint existing={false} />}
      />
      <PipeScaleRow />
      <LegendRow
        layer="chambers"
        label="Тепловые камеры"
        swatch={<LegendSwatch variant="square-dot" color={CHAMBER_NEW_COLOR} />}
        info={<ChambersHint />}
      />
      <LegendRow
        layer="technicalNodes"
        label="Технические узлы"
        swatch={<LegendSwatch variant="hollow-circle" color={TECHNICAL_NODE_COLOR} />}
      />
      <LegendRow
        layer="connectionPoints"
        label="Источник / точки подключения"
        swatch={<LegendSwatch variant="circle" color={SOURCE_COLOR} />}
        info={<ConnectionPointsHint />}
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
      <BasemapSelect />
    </Stack>
  );
}

const BASEMAP_OPTIONS: Array<{ value: BasemapId; label: string }> = [
  { value: 'osm', label: 'OSM (обесцвеченная)' },
  { value: 'grid', label: 'Чертёжная бумага (миллиметровка)' },
  { value: 'none', label: 'Без подложки' },
];

function BasemapSelect() {
  const basemap = useStore((state) => state.basemap);
  const setBasemap = useStore((state) => state.setBasemap);
  return (
    <Select
      label="Подложка"
      size="xs"
      data={BASEMAP_OPTIONS}
      value={basemap}
      onChange={(value) => value && setBasemap(value as BasemapId)}
      allowDeselect={false}
      comboboxProps={{ withinPortal: true }}
    />
  );
}
