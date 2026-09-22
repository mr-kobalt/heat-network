import { Fragment } from 'react';
import { Box, Button, Group, Select, Text } from '@mantine/core';
import { RESULT_STAGE, useStore } from '../store';

/**
 * Переключатель этапов алгоритма над картой (ADR-0036): «Вход → Сеть → … →
 * Итог». Для этапа «Деревья» рядом доступен выбор прохода.
 */
export function StageTabs() {
  const traced = useStore((state) => state.traced);
  const stages = useStore((state) => state.stages);
  const activeStage = useStore((state) => state.activeStage);
  const setActiveStage = useStore((state) => state.setActiveStage);
  const treePass = useStore((state) => state.treePass);
  const setTreePass = useStore((state) => state.setTreePass);

  if (!traced || stages.length === 0) {
    return null;
  }

  const tabs = [
    ...stages.map((stage) => ({ id: stage.id, title: stage.title, available: stage.available })),
    { id: RESULT_STAGE, title: 'Итог', available: true },
  ];
  const passes = stages.find((stage) => stage.id === 'trees')?.passes ?? [];

  return (
    <Box style={{ flex: 1, minWidth: 0, overflowX: 'auto' }}>
      <Group gap={4} wrap="nowrap" align="center">
        {tabs.map((stage, index) => (
          <Fragment key={stage.id}>
            {index > 0 && (
              <Text size="xs" c="dimmed" aria-hidden>
                →
              </Text>
            )}
            <Button
              size="compact-xs"
              variant={activeStage === stage.id ? 'filled' : 'subtle'}
              color={activeStage === stage.id ? 'blue' : 'gray'}
              disabled={!stage.available}
              onClick={() => setActiveStage(stage.id)}
            >
              {stage.title}
            </Button>
            {stage.id === 'trees' && activeStage === 'trees' && passes.length > 1 && (
              <Select
                size="xs"
                w={74}
                aria-label="Проход поиска"
                data={passes.map((pass) => ({ value: String(pass), label: `#${pass}` }))}
                value={String(treePass)}
                onChange={(value) => value && setTreePass(Number(value))}
                allowDeselect={false}
                comboboxProps={{ withinPortal: true }}
              />
            )}
          </Fragment>
        ))}
      </Group>
    </Box>
  );
}
