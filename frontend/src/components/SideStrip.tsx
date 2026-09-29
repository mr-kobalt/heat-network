import { ActionIcon, Divider, FileButton, ScrollArea, Stack, Text, Tooltip, UnstyledButton } from '@mantine/core';
import { IconUpload } from '@tabler/icons-react';
import { RESULT_STAGE, useStore } from '../store';
import { calculationModeLabel } from '../types';
import { stageIcon } from './stageIcons';
import { runDataset } from '../runDataset';

const ACCEPT = '.geojson,.json,application/geo+json';

/**
 * Узкая полоска слева (ADR-0059): всегда видна. Сверху — загрузка и расчёт
 * набора, ниже — выбор варианта (по показателю S) и этапа; развёрнутый контент
 * выводится рядом с ней.
 */
export function SideStrip() {
  const variants = useStore((state) => state.variants);
  const activeVariant = useStore((state) => state.activeVariant);
  const variantScore = useStore((state) => state.variantScore);
  const setActiveVariant = useStore((state) => state.setActiveVariant);
  const traced = useStore((state) => state.traced);
  const stages = useStore((state) => state.stages);
  const activeStage = useStore((state) => state.activeStage);
  const setActiveStage = useStore((state) => state.setActiveStage);
  const runBusy = useStore((state) => state.runBusy);
  const calculationMode = useStore((state) => state.calculationMode);
  const runTitle = `Загрузить и рассчитать · ${calculationModeLabel(calculationMode)}`;

  const orderedVariants = [...variants].sort(
    (a, b) => (variantScore[a] ?? Number.POSITIVE_INFINITY)
      - (variantScore[b] ?? Number.POSITIVE_INFINITY),
  );
  const stageItems = [
    ...stages.map((stage) => ({
      id: stage.id,
      title: stage.title,
      available: stage.available,
    })),
    { id: RESULT_STAGE, title: 'Итог', available: true },
  ];

  const scoreLabel = (variant: string) => {
    const score = variantScore[variant];
    return score === undefined ? variant : score.toFixed(2);
  };

  return (
    <Stack gap={0} h="100%" align="center" pt={8} style={{ width: '100%' }}>
      <FileButton accept={ACCEPT} onChange={(file) => { if (file) { void runDataset(file); } }}>
        {(props) => (
          <ActionIcon
            {...props}
            variant="light"
            size="lg"
            loading={runBusy}
            disabled={runBusy}
            aria-label={runTitle}
            title={runTitle}
          >
            <IconUpload size={20} />
          </ActionIcon>
        )}
      </FileButton>
      <Divider w="70%" my={8} />
      <ScrollArea style={{ flex: 1, width: '100%' }} type="auto">
        <Stack gap={4} align="center" pb={8}>
          {orderedVariants.length > 0 && (
            <>
              <Text size="11px" c="dimmed" fw={600}>S</Text>
              {orderedVariants.map((variant) => {
                const active = variant === activeVariant;
                const label = scoreLabel(variant);
                return (
                  <Tooltip key={variant} label={`Вариант S=${label}`} position="right" withArrow>
                    <UnstyledButton
                      onClick={() => setActiveVariant(variant)}
                      style={{
                        width: 54,
                        height: 28,
                        borderRadius: 6,
                        fontSize: 11,
                        fontWeight: 700,
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        border: '1px solid var(--mantine-color-gray-4)',
                        background: active ? 'var(--mantine-color-indigo-6)' : 'transparent',
                        color: active ? 'white' : 'var(--mantine-color-gray-7)',
                      }}
                    >
                      {label}
                    </UnstyledButton>
                  </Tooltip>
                );
              })}
            </>
          )}
          {traced && (
            <>
              <Divider w="70%" my={4} />
              <Text size="11px" c="dimmed" fw={600}>Этап</Text>
              {stageItems.map((item) => {
                const Icon = stageIcon(item.id);
                const active = item.id === activeStage;
                return (
                  <Tooltip key={item.id} label={item.title} position="right" withArrow>
                    <ActionIcon
                      size="lg"
                      variant={active ? 'filled' : 'subtle'}
                      color={active ? 'indigo' : 'gray'}
                      disabled={!item.available}
                      onClick={() => setActiveStage(item.id)}
                      aria-label={item.title}
                    >
                      <Icon size={22} />
                    </ActionIcon>
                  </Tooltip>
                );
              })}
            </>
          )}
        </Stack>
      </ScrollArea>
    </Stack>
  );
}
