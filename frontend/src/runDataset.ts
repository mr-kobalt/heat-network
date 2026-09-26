import { notifications } from '@mantine/notifications';
import { useStore } from './store';
import type { RunStatus } from './store';
import { parseFeatureCollection } from './types';
import { fetchStages, runAndFetch } from './api/client';
import type { RunResponse } from './api/client';
import { stageLabel, STATUS_LABELS } from './runStages';

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
  store.beginRun();
  notifications.show({
    id: RUN_NOTICE_ID,
    title: 'Расчёт',
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
    const run = await runAndFetch(file, store.selectedAlgorithm, {
      onRun: (report: RunResponse) => {
        useStore.getState().updateRun(
          report.status as RunStatus,
          report.stage ?? null,
          report.progress ?? 0,
        );
        showRunNotice(report);
      },
    });
    useStore.getState().setResult(run.result);
    if (run.traced && run.runId) {
      try {
        const manifest = await fetchStages(run.runId);
        useStore.getState().setStages(run.runId, manifest);
      } catch (stageError) {
        console.warn('Не удалось загрузить этапы расчёта', stageError);
      }
    }
    useStore.getState().finishRun(run.status as RunStatus);
  } catch (exception) {
    const message = exception instanceof Error ? exception.message : String(exception);
    useStore.getState().finishRun('FAILED', message);
    notifications.update({
      id: RUN_NOTICE_ID,
      title: 'Ошибка расчёта',
      message,
      color: 'red',
      loading: false,
      autoClose: false,
      withCloseButton: true,
    });
  }
}

function showRunNotice(report: RunResponse): void {
  const loading = report.status === 'PENDING' || report.status === 'RUNNING';
  const failed = report.status === 'FAILED';
  const succeeded = report.status === 'DONE' || report.status === 'PARTIAL';
  const message = report.status === 'PENDING'
    ? STATUS_LABELS.PENDING
    : report.status === 'RUNNING'
      ? `${stageLabel(report.stage)} · ${Math.round(report.progress ?? 0)}%`
      : failed
        ? (report.error ?? 'Расчёт завершился ошибкой')
        : STATUS_LABELS[report.status as RunStatus];
  notifications.update({
    id: RUN_NOTICE_ID,
    title: succeeded ? 'Расчёт завершён' : failed ? 'Ошибка расчёта' : 'Расчёт',
    message,
    loading,
    color: failed ? 'red' : succeeded ? 'green' : 'indigo',
    autoClose: loading || failed ? false : 5000,
    withCloseButton: !loading,
  });
}
