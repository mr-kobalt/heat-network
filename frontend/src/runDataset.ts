import { notifications } from '@mantine/notifications';
import { useStore } from './store';
import type { RunStatus } from './store';
import { calculationModeLabel, parseFeatureCollection } from './types';
import { fetchStages, runAndFetch } from './api/client';
import type { DatasetResponse, RunResponse } from './api/client';
import { formatDuration, stageLabel, STATUS_LABELS } from './runStages';

const RUN_NOTICE_ID = 'calculation-run';

/**
 * Загрузка набора на сервис и расчёт (ADR-0057/0059). Общая точка входа для
 * дропзоны «Загрузить и рассчитать» и кнопки вверху боковой полоски.
 */
export async function runDataset(file: File): Promise<void> {
  const store = useStore.getState();
  if (store.runBusy) {
    return;
  }
  const modeLabel = calculationModeLabel(store.calculationMode);
  store.beginRun();
  notifications.show({
    id: RUN_NOTICE_ID,
    title: `Расчёт · ${modeLabel}`,
    message: 'В очереди',
    color: 'indigo',
    loading: true,
    autoClose: false,
    withCloseButton: false,
  });

  try {
    // Показываем входные данные (ОКС, существующая сеть) вместе с результатом.
    const parsedInput = parseFeatureCollection(JSON.parse(await file.text()));
    useStore.getState().setInput(parsedInput);
    const run = await runAndFetch(file, store.selectedAlgorithm, store.calculationMode, {
      onDataset: (dataset: DatasetResponse) => {
        useStore.getState().setDatasetInfo(dataset);
      },
      onRun: (report: RunResponse) => {
        useStore.getState().updateRun(
          report.status as RunStatus,
          report.stage ?? null,
          report.progress ?? 0,
        );
        showRunningNotice(report);
      },
    });
    useStore.getState().setResult(run.result);
    useStore.getState().setLastRunMode(run.mode);
    if (run.traced && run.runId) {
      try {
        const manifest = await fetchStages(run.runId);
        useStore.getState().setStages(run.runId, manifest);
      } catch (stageError) {
        console.warn('Не удалось загрузить этапы расчёта', stageError);
        notifications.show({
          title: 'Этапы недоступны',
          message: 'Для этого запуска постадийная трассировка недоступна.',
          color: 'yellow',
        });
      }
    }
    useStore.getState().finishRun(run.status as RunStatus);
    showFinishedNotice(run.status as RunStatus);
  } catch (exception) {
    const message = exception instanceof Error ? exception.message : String(exception);
    useStore.getState().finishRun('FAILED', message);
    notifications.update({
      id: RUN_NOTICE_ID,
      title: `Ошибка расчёта · ${modeLabel}`,
      message: `${message} · ${elapsedLabel()}`,
      color: 'red',
      loading: false,
      autoClose: false,
      withCloseButton: true,
    });
  }
}

/** Прошло времени с начала текущего расчёта. */
function elapsedLabel(): string {
  const { runStartedAt } = useStore.getState();
  return formatDuration(Date.now() - (runStartedAt ?? Date.now()));
}

/** Обновление уведомления в процессе расчёта: этап, процент и таймер. */
function showRunningNotice(report: RunResponse): void {
  const loading = report.status === 'PENDING' || report.status === 'RUNNING';
  if (!loading) {
    return;
  }
  const message = report.status === 'PENDING'
    ? `${STATUS_LABELS.PENDING} · ${elapsedLabel()}`
    : `${stageLabel(report.stage)} · ${Math.round(report.progress ?? 0)}% · ${elapsedLabel()}`;
  notifications.update({
    id: RUN_NOTICE_ID,
    title: `Расчёт · ${calculationModeLabel(useStore.getState().calculationMode)}`,
    message,
    loading: true,
    color: 'indigo',
    autoClose: false,
    withCloseButton: false,
  });
}

/** Итоговое уведомление с общим временем расчёта. */
function showFinishedNotice(status: RunStatus): void {
  const { runStartedAt, runFinishedAt } = useStore.getState();
  const total = formatDuration((runFinishedAt ?? Date.now()) - (runStartedAt ?? Date.now()));
  notifications.update({
    id: RUN_NOTICE_ID,
    title: `Расчёт завершён · ${calculationModeLabel(useStore.getState().calculationMode)}`,
    message: `${STATUS_LABELS[status]} · ${total}`,
    color: 'green',
    loading: false,
    autoClose: 5000,
    withCloseButton: true,
  });
}
