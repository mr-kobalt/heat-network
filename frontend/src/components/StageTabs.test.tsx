import { beforeEach, describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { StageTabs } from './StageTabs';
import { RESULT_STAGE, useStore } from '../store';

function renderTabs() {
  return render(
    <MantineProvider>
      <StageTabs />
    </MantineProvider>,
  );
}

describe('StageTabs', () => {
  beforeEach(() => {
    useStore.setState({
      traced: true,
      runId: 'run-1',
      stages: [
        { id: 'input', title: 'Вход', kind: 'input', format: 'input', available: true },
        { id: 'exits', title: 'Выходы', kind: 'exits', format: 'geojson', available: true },
        { id: 'grid', title: 'Сетка', kind: 'grid', format: 'mask', available: true },
        { id: 'refine', title: 'Refine', kind: 'refine', format: 'geojson', available: false },
      ],
      activeStage: RESULT_STAGE,
      treePass: 1,
      stageData: {},
      gridMask: null,
    });
  });

  it('renders tabs with separators and the final tab', () => {
    renderTabs();
    expect(screen.getByText('Вход')).toBeInTheDocument();
    expect(screen.getByText('Выходы')).toBeInTheDocument();
    expect(screen.getByText('Итог')).toBeInTheDocument();
    expect(screen.getAllByText('→')).toHaveLength(4);
  });

  it('switches the active stage on click', () => {
    renderTabs();
    fireEvent.click(screen.getByText('Сетка'));
    expect(useStore.getState().activeStage).toBe('grid');
  });

  it('renders nothing without a traced run', () => {
    useStore.setState({ traced: false, stages: [] });
    const { container } = renderTabs();
    expect(container.querySelectorAll('button')).toHaveLength(0);
  });
});
