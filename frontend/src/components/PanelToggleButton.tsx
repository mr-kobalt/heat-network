import { Button, Divider, Tooltip } from '@mantine/core';
import { IconChevronLeft, IconChevronRight } from '@tabler/icons-react';

/**
 * Нижний блок панели (ADR-0059): тонкая линия и кнопка сворачивания/
 * разворачивания. В свёрнутом виде (узкая полоска) — только иконка.
 */
export function PanelToggleButton({
  collapsed,
  onClick,
}: {
  collapsed: boolean;
  onClick: () => void;
}) {
  const label = collapsed ? 'Показать панель' : 'Скрыть панель';
  const button = (
    <Button
      variant="subtle"
      color="gray"
      onClick={onClick}
      justify="center"
      h={38}
      m={8}
      leftSection={collapsed ? undefined : <IconChevronLeft size={16} />}
      aria-label={label}
      title={label}
    >
      {collapsed ? <IconChevronRight size={20} /> : 'Скрыть панель'}
    </Button>
  );

  return (
    <>
      <Divider w="100%" />
      {collapsed
        ? <Tooltip label={label} position="right" withArrow>{button}</Tooltip>
        : button}
    </>
  );
}
