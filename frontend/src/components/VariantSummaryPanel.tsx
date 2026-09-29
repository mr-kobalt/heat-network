import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Badge, Box, Divider, Group, Stack, Table, Text, Tooltip } from '@mantine/core';
import { useStore } from '../store';
import type { FeatureCollection, FeatureProperties } from '../types';
import { DatasetDiagnostics } from './DatasetDiagnostics';

interface Aggregate {
  length: number;
  cost: number;
  count: number;
}

interface Breakdown {
  base: Map<number, Aggregate>;
  special: Map<number, Aggregate>;
  chambers: Map<string, Aggregate>;
  tieInCount: number;
  tieInCost: number;
  penalty: number;
  totalCost: number;
  totalLength: number;
  score: number;
  rank: number;
}

/** Четыре ценовых типа камер (пороги по Ду из конфига стоимости) + врезки. */
const CHAMBER_BANDS: Array<{ key: string; label: string; maxDn: number }> = [
  { key: 'small', label: 'Малая (Ду ≤ 200)', maxDn: 200 },
  { key: 'medium', label: 'Средняя (Ду ≤ 500)', maxDn: 500 },
  { key: 'large', label: 'Большая (Ду ≤ 1000)', maxDn: 1000 },
  { key: 'xlarge', label: 'Сверхбольшая (Ду > 1000)', maxDn: Number.POSITIVE_INFINITY },
];

function chamberBandKey(diameter: number): string {
  return CHAMBER_BANDS.find((band) => diameter <= band.maxDn)?.key ?? 'xlarge';
}

function agg(): Aggregate {
  return { length: 0, cost: 0, count: 0 };
}

/** Агрегация результата по варианту: трассы (обычные/спец), камеры, врезки. */
function buildBreakdown(result: FeatureCollection, variant: string): Breakdown {
  const base = new Map<number, Aggregate>();
  const special = new Map<number, Aggregate>();
  const chambers = new Map<string, Aggregate>();
  let summary: FeatureProperties | undefined;
  for (const feature of result.features) {
    const properties = feature.properties;
    if (String(properties.variant_id) !== variant) {
      continue;
    }
    if (properties.object_type === 'heat_network') {
      const diameter = Number(properties.diameter) || 0;
      const target = properties.laying_method === 'special' ? special : base;
      const value = target.get(diameter) ?? agg();
      value.length += Number(properties.length) || 0;
      value.cost += Number(properties.cost) || 0;
      value.count += 1;
      target.set(diameter, value);
    } else if (properties.object_type === 'heat_chamber') {
      const key = chamberBandKey(Number(properties.diameter) || 0);
      const value = chambers.get(key) ?? agg();
      value.count += 1;
      value.cost += Number(properties.cost) || 0;
      chambers.set(key, value);
    } else if (properties.object_type === 'variant_summary') {
      summary = properties;
    }
  }
  return {
    base,
    special,
    chambers,
    tieInCount: Number(summary?.existing_chamber_tie_in_count) || 0,
    tieInCost: Number(summary?.existing_chamber_tie_in_cost) || 0,
    penalty: Number(summary?.unconnected_penalty) || 0,
    totalCost: Number(summary?.calculated_cost) || 0,
    totalLength: Number(summary?.new_network_length) || 0,
    score: Number(summary?.score) || 0,
    rank: Number(summary?.rank) || 0,
  };
}

function sumAgg(map: Map<number, Aggregate>): Aggregate {
  const total = agg();
  map.forEach((value) => {
    total.length += value.length;
    total.cost += value.cost;
    total.count += value.count;
  });
  return total;
}

function unionDiameters(
  variants: string[],
  breakdowns: Record<string, Breakdown>,
  pick: (breakdown: Breakdown) => Map<number, Aggregate>,
): number[] {
  const diameters = new Set<number>();
  variants.forEach((variant) => pick(breakdowns[variant]).forEach((_, d) => diameters.add(d)));
  return Array.from(diameters).sort((a, b) => a - b);
}

function money(value: number): string {
  const millions = value / 1_000_000;
  return `${millions.toLocaleString('ru-RU', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })} млн ₽`;
}

/** Точная сумма (рубли) — для подсказки к сокращённой записи. */
function exactMoney(value: number): string {
  return `${Math.round(value).toLocaleString('ru-RU')} ₽`;
}

function length(value: number): string {
  return `${value.toLocaleString('ru-RU', {
    minimumFractionDigits: 1,
    maximumFractionDigits: 1,
  })} м`;
}

const FIRST_COLUMN = { background: 'var(--mantine-color-gray-0)' } as const;
/** Жирные линии под шапкой и над итогами. */
const BOLD_BORDER = '2px solid var(--mantine-color-gray-5)';
/** Внешняя рамка раскрытой группы подстрок. */
const GROUP_BORDER = '1px solid var(--mantine-color-gray-4)';
/** Жирная внешняя рамка активного варианта. */
const ACTIVE_BORDER = '2px solid var(--mantine-color-indigo-5)';

/** Чип отклонения суммы от лучшего варианта: рост — красный, снижение — зелёный. */
function DeltaChip({ value, reference, unit = 'money' }: {
  value: number;
  reference: number;
  unit?: 'money' | 'count' | 'length';
}) {
  if (Math.abs(value - reference) < 1e-9) {
    return null;
  }
  if (reference === 0) {
    if (value <= 0) {
      return null;
    }
    if (unit === 'money') {
      return (
        <Tooltip label={exactMoney(value)} withArrow>
          <Badge size="xs" variant="light" color="red">{`+${money(value)}`}</Badge>
        </Tooltip>
      );
    }
    const absolute = unit === 'length' ? length(value) : `${value} шт`;
    return <Badge size="xs" variant="light" color="red">{`+${absolute}`}</Badge>;
  }
  const percent = ((value - reference) / reference) * 100;
  const up = percent > 0;
  return (
    <Badge size="xs" variant="light" color={up ? 'red' : 'green'}>
      {`${up ? '+' : '−'}${Math.abs(percent).toFixed(1)}%`}
    </Badge>
  );
}

/** Ячейка категории: крупно сумма, под ней длина/количество (с чипом отклонения). */
function CategoryCell({ cost, secondary, secondaryDelta, color, delta }: {
  cost: number;
  secondary: string;
  secondaryDelta?: ReactNode;
  color?: string;
  delta?: ReactNode;
}) {
  return (
    <Stack gap={0} align="flex-end">
      <Group gap={4} justify="flex-end" wrap="nowrap">
        {delta}
        <Tooltip label={exactMoney(cost)} withArrow>
          <Text size="sm" fw={600} c={color}>{money(cost)}</Text>
        </Tooltip>
      </Group>
      <Group gap={4} justify="flex-end" wrap="nowrap">
        {secondaryDelta}
        <Text size="xs" c="dimmed">{secondary}</Text>
      </Group>
    </Stack>
  );
}

/** Ячейка подстроки: крупно сумма, под ней длина/количество и тариф. */
function SubCell({ value, kind }: { value: Aggregate | undefined; kind: 'length' | 'count' }) {
  const units = value ? (kind === 'length' ? value.length : value.count) : 0;
  if (!value || units === 0) {
    return <Text size="xs" ta="right" c="dimmed">—</Text>;
  }
  const price = value.cost / units;
  const unitLabel = kind === 'length' ? length(value.length) : `${value.count} шт`;
  const tariff = kind === 'length'
    ? `${Math.round(price).toLocaleString('ru-RU')} ₽/м`
    : `${Math.round(price).toLocaleString('ru-RU')} ₽`;
  return (
    <Stack gap={0} align="flex-end">
      <Tooltip label={exactMoney(value.cost)} withArrow>
        <Text size="sm" fw={600}>{money(value.cost)}</Text>
      </Tooltip>
      <Text size="xs" c="dimmed">{unitLabel} · {tariff}</Text>
    </Stack>
  );
}

export function VariantSummaryPanel() {
  const result = useStore((state) => state.result);
  const variants = useStore((state) => state.variants);
  const activeVariant = useStore((state) => state.activeVariant);
  const setActiveVariant = useStore((state) => state.setActiveVariant);
  const [expanded, setExpanded] = useState<{ trace: boolean; chambers: boolean }>({
    trace: false,
    chambers: false,
  });

  const breakdowns = useMemo(() => {
    const map: Record<string, Breakdown> = {};
    if (result) {
      variants.forEach((variant) => {
        map[variant] = buildBreakdown(result, variant);
      });
    }
    return map;
  }, [result, variants]);

  if (!result || variants.length === 0) {
    return (
      <Stack gap="xs">
        <Divider label="сводка" labelPosition="center" />
        <DatasetDiagnostics />
        <Text size="sm" c="dimmed">
          Загрузите результат расчёта, чтобы увидеть сводку по вариантам.
        </Text>
      </Stack>
    );
  }

  // Колонки — варианты по рангу, лучший слева.
  const ordered = [...variants].sort(
    (a, b) => (breakdowns[a]?.rank ?? 0) - (breakdowns[b]?.rank ?? 0),
  );
  const bestVariant = ordered[0];
  const best = breakdowns[bestVariant];
  const bestNormal = sumAgg(best.base);
  const bestSpecial = sumAgg(best.special);
  const referenceTrace = bestNormal.cost + bestSpecial.cost;
  const referenceTraceLength = bestNormal.length + bestSpecial.length;
  const referenceScore = best.score;
  let referenceChambers = best.tieInCost;
  let referenceChambersCount = best.tieInCount;
  best.chambers.forEach((value) => {
    referenceChambers += value.cost;
    referenceChambersCount += value.count;
  });

  const baseDiameters = unionDiameters(ordered, breakdowns, (b) => b.base);
  const specialDiameters = unionDiameters(ordered, breakdowns, (b) => b.special);
  const chamberBands = CHAMBER_BANDS.filter((band) =>
    ordered.some((variant) => (breakdowns[variant].chambers.get(band.key)?.count ?? 0) > 0));
  const hasSpecial = specialDiameters.length > 0;

  const traceRows: Array<{
    key: string;
    label: string;
    color?: string;
    get: (breakdown: Breakdown) => Aggregate | undefined;
  }> = [
    ...baseDiameters.map((diameter) => ({
      key: `base-${diameter}`,
      label: `Ду ${diameter} · обычная`,
      color: 'dimmed',
      get: (breakdown: Breakdown) => breakdown.base.get(diameter),
    })),
    ...specialDiameters.map((diameter) => ({
      key: `special-${diameter}`,
      label: `Ду ${diameter} · спец.`,
      color: 'orange',
      get: (breakdown: Breakdown) => breakdown.special.get(diameter),
    })),
  ];
  const chamberRows: Array<{
    key: string;
    label: string;
    get: (breakdown: Breakdown) => Aggregate | undefined;
  }> = [
    ...chamberBands.map((band) => ({
      key: `chamber-${band.key}`,
      label: band.label,
      get: (breakdown: Breakdown) => breakdown.chambers.get(band.key),
    })),
    {
      key: 'tiein',
      label: 'Врезки',
      get: (breakdown: Breakdown) => ({
        length: 0,
        count: breakdown.tieInCount,
        cost: breakdown.tieInCost,
      }),
    },
  ];

  const toggle = (key: 'trace' | 'chambers') =>
    setExpanded((state) => ({ ...state, [key]: !state[key] }));

  // Жирные боковые границы у колонки активного варианта.
  const activeCol = (variant: string): Record<string, string> =>
    variant === activeVariant
      ? { borderLeft: ACTIVE_BORDER, borderRight: ACTIVE_BORDER }
      : {};

  return (
    <Stack gap="xs">
      <Divider label="сводка" labelPosition="center" />
      <DatasetDiagnostics />
      <Box style={{ overflowX: 'auto' }}>
        <Table withRowBorders={false} fz="xs" style={{ minWidth: 320 }}>
          <Table.Thead>
            <Table.Tr>
              <Table.Th style={{ ...FIRST_COLUMN, borderBottom: BOLD_BORDER }}>S</Table.Th>
              {ordered.map((variant) => (
                <Table.Th
                  key={variant}
                  ta="right"
                  style={{
                    cursor: 'pointer',
                    whiteSpace: 'nowrap',
                    borderBottom: BOLD_BORDER,
                    ...(variant === activeVariant ? { borderTop: ACTIVE_BORDER } : {}),
                    ...activeCol(variant),
                  }}
                  onClick={() => setActiveVariant(variant)}
                >
                  <Group gap={4} justify="flex-end" wrap="nowrap">
                    {variant === bestVariant
                      ? null
                      : <DeltaChip value={breakdowns[variant].score} reference={referenceScore} />}
                    <Text
                      size="sm"
                      fw={700}
                      ta="right"
                      c={variant === activeVariant ? 'indigo' : undefined}
                      title={`S=${breakdowns[variant].score.toFixed(2)}`}
                    >
                      {breakdowns[variant].score.toFixed(2)}
                    </Text>
                  </Group>
                </Table.Th>
              ))}
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            <Table.Tr style={{ cursor: 'pointer' }} onClick={() => toggle('trace')}>
              <Table.Td fw={600} style={{ ...FIRST_COLUMN, whiteSpace: 'nowrap' }}>
                {expanded.trace ? '▾' : '▸'} Трасса
              </Table.Td>
              {ordered.map((variant) => {
                const breakdown = breakdowns[variant];
                const normal = sumAgg(breakdown.base);
                const special = sumAgg(breakdown.special);
                const value = normal.cost + special.cost;
                const traceLength = normal.length + special.length;
                return (
                  <Table.Td key={variant} ta="right" style={activeCol(variant)}>
                    <CategoryCell
                      cost={value}
                      secondary={length(traceLength)}
                      delta={variant === bestVariant
                        ? undefined
                        : <DeltaChip value={value} reference={referenceTrace} />}
                      secondaryDelta={variant === bestVariant
                        ? undefined
                        : <DeltaChip value={traceLength} reference={referenceTraceLength} unit="length" />}
                    />
                  </Table.Td>
                );
              })}
            </Table.Tr>
            {expanded.trace && traceRows.map((row, index) => (
              <Table.Tr key={row.key}>
                <Table.Td
                  c={row.color}
                  style={{
                    ...FIRST_COLUMN,
                    borderLeft: GROUP_BORDER,
                    borderTop: index === 0 ? GROUP_BORDER : undefined,
                    borderBottom: index === traceRows.length - 1 ? GROUP_BORDER : undefined,
                  }}
                >
                  {row.label}
                </Table.Td>
                {ordered.map((variant, column) => (
                  <Table.Td
                    key={variant}
                    ta="right"
                    style={{
                      borderTop: index === 0 ? GROUP_BORDER : undefined,
                      borderBottom: index === traceRows.length - 1 ? GROUP_BORDER : undefined,
                      borderRight: column === ordered.length - 1 ? GROUP_BORDER : undefined,
                      ...activeCol(variant),
                    }}
                  >
                    <SubCell value={row.get(breakdowns[variant])} kind="length" />
                  </Table.Td>
                ))}
              </Table.Tr>
            ))}

            <Table.Tr style={{ cursor: 'pointer' }} onClick={() => toggle('chambers')}>
              <Table.Td fw={600} style={{ ...FIRST_COLUMN, whiteSpace: 'nowrap' }}>
                {expanded.chambers ? '▾' : '▸'} Камеры
              </Table.Td>
              {ordered.map((variant) => {
                const breakdown = breakdowns[variant];
                let count = 0;
                let cost = 0;
                breakdown.chambers.forEach((value) => {
                  count += value.count;
                  cost += value.cost;
                });
                const value = cost + breakdown.tieInCost;
                const totalCount = count + breakdown.tieInCount;
                return (
                  <Table.Td key={variant} ta="right" style={activeCol(variant)}>
                    <CategoryCell
                      cost={value}
                      secondary={`новых ${count} + врезок ${breakdown.tieInCount}`}
                      delta={variant === bestVariant
                        ? undefined
                        : <DeltaChip value={value} reference={referenceChambers} />}
                      secondaryDelta={variant === bestVariant
                        ? undefined
                        : <DeltaChip
                            value={totalCount}
                            reference={referenceChambersCount}
                            unit="count"
                          />}
                    />
                  </Table.Td>
                );
              })}
            </Table.Tr>
            {expanded.chambers && chamberRows.map((row, index) => (
              <Table.Tr key={row.key}>
                <Table.Td
                  c="dimmed"
                  style={{
                    ...FIRST_COLUMN,
                    borderLeft: GROUP_BORDER,
                    borderTop: index === 0 ? GROUP_BORDER : undefined,
                    borderBottom: index === chamberRows.length - 1 ? GROUP_BORDER : undefined,
                  }}
                >
                  {row.label}
                </Table.Td>
                {ordered.map((variant, column) => (
                  <Table.Td
                    key={variant}
                    ta="right"
                    style={{
                      borderTop: index === 0 ? GROUP_BORDER : undefined,
                      borderBottom: index === chamberRows.length - 1 ? GROUP_BORDER : undefined,
                      borderRight: column === ordered.length - 1 ? GROUP_BORDER : undefined,
                      ...activeCol(variant),
                    }}
                  >
                    <SubCell value={row.get(breakdowns[variant])} kind="count" />
                  </Table.Td>
                ))}
              </Table.Tr>
            ))}

            <Table.Tr>
              <Table.Td fw={600} style={FIRST_COLUMN}>Штраф</Table.Td>
              {ordered.map((variant) => {
                const penalty = breakdowns[variant].penalty;
                return (
                  <Table.Td key={variant} ta="right" style={activeCol(variant)}>
                    <Group gap={4} justify="flex-end" wrap="nowrap">
                      {variant === bestVariant
                        ? null
                        : <DeltaChip value={penalty} reference={best.penalty} />}
                      <Tooltip label={exactMoney(penalty)} withArrow>
                        <Text size="sm" fw={penalty > 0 ? 700 : 400} c={penalty > 0 ? 'red' : 'dimmed'}>
                          {money(penalty)}
                        </Text>
                      </Tooltip>
                    </Group>
                  </Table.Td>
                );
              })}
            </Table.Tr>

            <Table.Tr>
              <Table.Td fw={700} style={{ ...FIRST_COLUMN, borderTop: BOLD_BORDER }}>Итого</Table.Td>
              {ordered.map((variant) => {
                const breakdown = breakdowns[variant];
                return (
                  <Table.Td
                    key={variant}
                    ta="right"
                    style={{
                      borderTop: BOLD_BORDER,
                      ...(variant === activeVariant ? { borderBottom: ACTIVE_BORDER } : {}),
                      ...activeCol(variant),
                    }}
                  >
                    <Stack gap={0} align="flex-end">
                      <Group gap={4} justify="flex-end" wrap="nowrap">
                        {variant === bestVariant
                          ? null
                          : <DeltaChip value={breakdown.totalCost} reference={best.totalCost} />}
                        <Tooltip label={exactMoney(breakdown.totalCost)} withArrow>
                          <Text size="sm" fw={700}>{money(breakdown.totalCost)}</Text>
                        </Tooltip>
                      </Group>
                      <Text size="xs" c="dimmed">
                        {length(breakdown.totalLength)}
                      </Text>
                    </Stack>
                  </Table.Td>
                );
              })}
            </Table.Tr>
          </Table.Tbody>
        </Table>
      </Box>

      <Text size="xs" c="dimmed">
        Нажмите на «Трасса» или «Камеры», чтобы раскрыть детализацию
        {hasSpecial ? '; спецпроходы учитываются отдельно' : ''}.
      </Text>
    </Stack>
  );
}
