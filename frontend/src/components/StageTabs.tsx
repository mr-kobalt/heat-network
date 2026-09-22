import { Fragment } from 'react';
import { Box, Button, Group, Menu, Text } from '@mantine/core';
import { RESULT_STAGE, useStore } from '../store';

/**
 * Переключатель этапов алгоритма над картой (ADR-0036/0037/0038): «Вход → Сеть →
 * Ограничения → Выходы → Сетка → Деревья → Переподключение → Refine → Итог». Заголовок
 * «Деревья» — выпадающий список проходов («Деревья #1», «Деревья #2», …).
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
        {tabs.map((stage, index) => {
          const isTrees = stage.id === 'trees';
          const showPasses = isTrees && passes.length > 0;
          const label = showPasses ? `${stage.title} #${treePass}` : stage.title;
          const button = (
            <Button
              size="compact-xs"
              variant={activeStage === stage.id ? 'filled' : 'subtle'}
              color={activeStage === stage.id ? 'blue' : 'gray'}
              disabled={!stage.available}
              onClick={() => setActiveStage(stage.id)}
            >
              {label}
            </Button>
          );
          return (
            <Fragment key={stage.id}>
              {index > 0 && (
                <Text size="xs" c="dimmed" aria-hidden>
                  →
                </Text>
              )}
              {showPasses ? (
                <Menu withinPortal position="bottom-start">
                  <Menu.Target>{button}</Menu.Target>
                  <Menu.Dropdown>
                    {passes.map((pass) => (
                      <Menu.Item
                        key={pass}
                        onClick={() => {
                          setTreePass(pass);
                          setActiveStage('trees');
                        }}
                      >
                        {`${stage.title} #${pass}`}
                      </Menu.Item>
                    ))}
                  </Menu.Dropdown>
                </Menu>
              ) : (
                button
              )}
            </Fragment>
          );
        })}
      </Group>
    </Box>
  );
}
