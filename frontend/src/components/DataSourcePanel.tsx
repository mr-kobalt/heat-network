import { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Divider,
  Group,
  Progress,
  SegmentedControl,
  Stack,
  Text,
} from '@mantine/core';
import { Dropzone } from '@mantine/dropzone';
import { IconDownload, IconFileDownload, IconFileImport, IconUpload } from '@tabler/icons-react';
import { useQuery } from '@tanstack/react-query';
import { calculationModeLabel, parseFeatureCollection } from '../types';
import { useStore } from '../store';
import { fetchAlgorithms, fetchResultBlob } from '../api/client';
import { runDataset } from '../runDataset';
import { formatDuration, stageLabel, STATUS_LABELS } from '../runStages';

const ACCEPT = ['.geojson', '.json', 'application/geo+json'];

export function DataSourcePanel() {
  const setInput = useStore((state) => state.setInput);
  const setResult = useStore((state) => state.setResult);
  const setAlgorithms = useStore((state) => state.setAlgorithms);
  const runBusy = useStore((state) => state.runBusy);
  const runStage = useStore((state) => state.runStage);
  const runProgress = useStore((state) => state.runProgress);
  const runStatus = useStore((state) => state.runStatus);
  const runStartedAt = useStore((state) => state.runStartedAt);
  const runFinishedAt = useStore((state) => state.runFinishedAt);
  const runError = useStore((state) => state.runError);
  const runId = useStore((state) => state.runId);
  const result = useStore((state) => state.result);
  const calculationMode = useStore((state) => state.calculationMode);
  const setCalculationMode = useStore((state) => state.setCalculationMode);
  const lastRunMode = useStore((state) => state.lastRunMode);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  // Тикаем таймер раз в секунду, пока идёт расчёт (иначе — фиксируем итог).
  useEffect(() => {
    if (!runBusy) {
      return undefined;
    }
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [runBusy]);

  const elapsed = runStartedAt ? (runFinishedAt ?? now) - runStartedAt : 0;

  const { data: fetchedAlgorithms, error: algorithmsError } = useQuery({
    queryKey: ['algorithms'],
    queryFn: fetchAlgorithms,
    // Бэкенд может подниматься позже фронтенда (devenv dev) — повторяем.
    retry: 6,
    retryDelay: (attempt) => Math.min(1000 * 2 ** attempt, 5000),
  });

  useEffect(() => {
    if (fetchedAlgorithms) {
      setAlgorithms(fetchedAlgorithms);
    }
  }, [fetchedAlgorithms, setAlgorithms]);

  const loadFile = async (
    file: File | null,
    setter: (value: ReturnType<typeof parseFeatureCollection>) => void,
  ) => {
    if (!file) {
      return;
    }
    setError(null);
    try {
      const parsed = parseFeatureCollection(JSON.parse(await file.text()));
      setter(parsed);
      setStatus(`Загружен файл: ${file.name}`);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : String(exception));
    }
  };

  const runFile = async (file: File | null) => {
    if (!file) {
      return;
    }
    setError(null);
    setStatus('');
    await runDataset(file);
  };

  /** Скачивание выходного GeoJSON: файл запуска (формат заказчика) либо текущий результат. */
  const downloadResult = async () => {
    try {
      let blob: Blob;
      let filename = 'result.geojson';
      if (runId) {
        blob = await fetchResultBlob(runId);
        filename = `result-${runId}.geojson`;
      } else if (result) {
        blob = new Blob([JSON.stringify(result)], { type: 'application/geo+json' });
      } else {
        return;
      }
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = filename;
      link.click();
      URL.revokeObjectURL(url);
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : String(exception));
    }
  };

  return (
    <Stack gap="xs">
      <Divider label="визуализировать" labelPosition="center" />

      <Group grow align="stretch" gap="xs">
        <Dropzone
          accept={ACCEPT}
          multiple={false}
          onDrop={(files) => loadFile(files[0] ?? null, setInput)}
          onReject={() => setError('Неподдерживаемый файл')}
          p="xs"
          style={{ minHeight: 76 }}
        >
          <Stack align="center" gap={4}>
            <IconFileImport size={20} />
            <Text size="xs" ta="center">Исходные данные</Text>
          </Stack>
        </Dropzone>
        <Dropzone
          accept={ACCEPT}
          multiple={false}
          onDrop={(files) => loadFile(files[0] ?? null, setResult)}
          onReject={() => setError('Неподдерживаемый файл')}
          p="xs"
          style={{ minHeight: 76 }}
        >
          <Stack align="center" gap={4}>
            <IconFileDownload size={20} />
            <Text size="xs" ta="center">Результат</Text>
          </Stack>
        </Dropzone>
      </Group>

      <Divider label="рассчитать" labelPosition="center" my={4} />
      <Group gap={6} wrap="nowrap" align="center" justify="space-between">
        <Group gap={6} wrap="nowrap" align="center">
          <SegmentedControl
            size="xs"
            disabled={runBusy}
            data={[
              { value: '2d', label: '2D' },
              { value: 'depth', label: 'Глубина' },
            ]}
            value={calculationMode}
            onChange={(value) => setCalculationMode(value as typeof calculationMode)}
          />
          <Text size="sm">Режим</Text>
        </Group>
        {lastRunMode && (
          <Badge
            variant="light"
            color={lastRunMode === 'depth' ? 'teal' : 'gray'}
            size="sm"
            radius="sm"
          >
            {calculationModeLabel(lastRunMode)}
          </Badge>
        )}
      </Group>
      {algorithmsError && (
        <Text size="sm" c="red">
          Не удалось получить список алгоритмов: {String(algorithmsError)}
        </Text>
      )}

      {runStartedAt != null && (
        <Stack gap={4}>
          <Group justify="space-between">
            <Text size="sm" fw={600}>
              {runBusy ? stageLabel(runStage) : STATUS_LABELS[runStatus]}
            </Text>
            <Text size="sm" c="dimmed">
              {Math.round(runProgress)}% · {formatDuration(elapsed)}
            </Text>
          </Group>
          <Progress
            value={runProgress}
            animated={runBusy}
            size="md"
            radius="sm"
            color={runStatus === 'FAILED' ? 'red' : undefined}
          />
        </Stack>
      )}

      {!runBusy && (
        <Group grow align="stretch" gap="xs">
          <Dropzone
            accept={ACCEPT}
            multiple={false}
            onDrop={(files) => runFile(files[0] ?? null)}
            onReject={() => setError('Неподдерживаемый файл')}
            p="sm"
          >
            <Stack align="center" gap={4}>
              <IconUpload size={22} />
              <Text size="sm" fw={600}>Загрузить и рассчитать</Text>
            </Stack>
          </Dropzone>
          {(runId || result) && (
            <Button
              variant="light"
              h="100%"
              p="sm"
              onClick={() => { void downloadResult(); }}
            >
              <Stack align="center" gap={4}>
                <IconDownload size={22} />
                <Text size="sm" fw={600}>Скачать результат</Text>
              </Stack>
            </Button>
          )}
        </Group>
      )}

      {status && (
        <Text size="sm" c="dimmed">
          {status}
        </Text>
      )}
      {(error ?? runError) && (
        <Alert color="red" title="Ошибка" p="xs">
          {error ?? runError}
        </Alert>
      )}
    </Stack>
  );
}
