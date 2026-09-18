import { useStore } from '../store';
import { FeatureProperties } from '../types';
import { Select, Stack, Table, Text } from '@mantine/core';

const ROWS: Array<{ key: keyof FeatureProperties; label: string; money?: boolean }> = [
  { key: 'rank', label: 'Ранг' },
  { key: 'calculated_cost', label: 'Итоговая стоимость, руб.', money: true },
  { key: 'construction_cost', label: 'Новые участки, руб.', money: true },
  { key: 'chamber_construction_cost', label: 'Новые камеры, руб.', money: true },
  { key: 'tie_in_cost', label: 'Врезки, руб.', money: true },
  { key: 'reconstruction_cost', label: 'Реконструкция участков, руб.', money: true },
  { key: 'chamber_reconstruction_cost', label: 'Реконструкция камер, руб.', money: true },
  { key: 'unconnected_penalty', label: 'Штраф за неподключённые, руб.', money: true },
  { key: 'new_network_length', label: 'Длина новой сети, м' },
  { key: 'reconstruction_length', label: 'Длина реконструкции, м' },
  { key: 'length', label: 'Общая длина работ, м' },
  { key: 'score', label: 'Показатель S' },
];

export function VariantSummaryPanel() {
  const result = useStore((state) => state.result);
  const variants = useStore((state) => state.variants);
  const activeVariant = useStore((state) => state.activeVariant);
  const setActiveVariant = useStore((state) => state.setActiveVariant);

  if (!result || variants.length === 0) {
    return (
      <Text size="sm" c="dimmed">
        Загрузите результат расчёта, чтобы увидеть сводку по вариантам.
      </Text>
    );
  }

  const summaryOf = (variantId: string) =>
    result.features.find(
      (feature) =>
        feature.properties.object_type === 'variant_summary' &&
        feature.properties.variant_id === variantId,
    )?.properties;

  const activeSummary = activeVariant ? summaryOf(activeVariant) : undefined;
  const summaries = variants.map((variant) => ({ variant, properties: summaryOf(variant) }));

  return (
    <Stack gap="xs">
      <Select
        label="Вариант"
        size="xs"
        data={variants.map((variant) => ({ value: variant, label: `Вариант ${variant}` }))}
        value={activeVariant}
        onChange={setActiveVariant}
        allowDeselect={false}
      />

      {activeSummary && (
        <Table withTableBorder fz="xs">
          <Table.Tbody>
            {ROWS.map((row) => (
              <Table.Tr key={String(row.key)}>
                <Table.Td fw={600}>{row.label}</Table.Td>
                <Table.Td>{formatNumber(activeSummary[row.key], row.money)}</Table.Td>
              </Table.Tr>
            ))}
            <Table.Tr>
              <Table.Td fw={600}>Неподключённые ОКС</Table.Td>
              <Table.Td>
                {Array.isArray(activeSummary.unconnected_oks_ids) &&
                activeSummary.unconnected_oks_ids.length > 0
                  ? (activeSummary.unconnected_oks_ids as string[]).join(', ')
                  : '—'}
              </Table.Td>
            </Table.Tr>
          </Table.Tbody>
        </Table>
      )}

      {summaries.length > 1 && (
        <Table withTableBorder fz="xs">
          <Table.Thead>
            <Table.Tr>
              <Table.Th>Показатель</Table.Th>
              {summaries.map(({ variant }) => (
                <Table.Th key={variant}>В{variant}</Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            <Table.Tr>
              <Table.Td fw={600}>Стоимость, руб.</Table.Td>
              {summaries.map(({ variant, properties }) => (
                <Table.Td key={variant}>{formatNumber(properties?.calculated_cost, true)}</Table.Td>
              ))}
            </Table.Tr>
            <Table.Tr>
              <Table.Td fw={600}>Длина, м</Table.Td>
              {summaries.map(({ variant, properties }) => (
                <Table.Td key={variant}>{formatNumber(properties?.length, false)}</Table.Td>
              ))}
            </Table.Tr>
            <Table.Tr>
              <Table.Td fw={600}>S</Table.Td>
              {summaries.map(({ variant, properties }) => (
                <Table.Td key={variant}>{formatNumber(properties?.score, false)}</Table.Td>
              ))}
            </Table.Tr>
          </Table.Tbody>
        </Table>
      )}
    </Stack>
  );
}

function formatNumber(value: unknown, money?: boolean): string {
  if (typeof value !== 'number') {
    return '—';
  }
  return money ? Math.round(value).toLocaleString('ru-RU') : value.toFixed(2);
}
