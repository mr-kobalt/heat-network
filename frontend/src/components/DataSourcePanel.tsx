import { useEffect, useState } from 'react';
import { Alert, Button, Divider, FileButton, Select, Stack, Text } from '@mantine/core';
import { useQuery } from '@tanstack/react-query';
import { parseFeatureCollection } from '../types';
import { useStore } from '../store';
import { fetchAlgorithms, fetchStages, runAndFetch } from '../api/client';

export function DataSourcePanel() {
  const setInput = useStore((state) => state.setInput);
  const setResult = useStore((state) => state.setResult);
  const algorithms = useStore((state) => state.algorithms);
  const selectedAlgorithm = useStore((state) => state.selectedAlgorithm);
  const setAlgorithms = useStore((state) => state.setAlgorithms);
  const setSelectedAlgorithm = useStore((state) => state.setSelectedAlgorithm);
  const setStages = useStore((state) => state.setStages);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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

  const loadFile = async (file: File | null, setter: (value: ReturnType<typeof parseFeatureCollection>) => void) => {
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

  const runViaApi = async (file: File | null) => {
    if (!file) {
      return;
    }
    setBusy(true);
    setError(null);
    try {
      // Показываем входные данные (ОКС, существующая сеть) вместе с результатом.
      const parsedInput = parseFeatureCollection(JSON.parse(await file.text()));
      setInput(parsedInput);
      const run = await runAndFetch(file, selectedAlgorithm, setStatus);
      setResult(run.result);
      if (run.traced && run.runId) {
        try {
          const manifest = await fetchStages(run.runId);
          setStages(run.runId, manifest);
        } catch (stageError) {
          console.warn('Не удалось загрузить этапы расчёта', stageError);
        }
      }
      setStatus('Готово');
    } catch (exception) {
      setError(exception instanceof Error ? exception.message : String(exception));
    } finally {
      setBusy(false);
    }
  };

  return (
    <Stack gap="xs">
      <Text fw={600} size="sm">
        Данные
      </Text>
      <FileButton accept=".geojson,.json,application/geo+json" onChange={(file) => loadFile(file, setResult)}>
        {(props) => (
          <Button {...props} size="xs" variant="light">
            Открыть результат (GeoJSON)
          </Button>
        )}
      </FileButton>
      <FileButton accept=".geojson,.json,application/geo+json" onChange={(file) => loadFile(file, setInput)}>
        {(props) => (
          <Button {...props} size="xs" variant="light">
            Открыть исходные данные (контекст)
          </Button>
        )}
      </FileButton>

      <Divider label="через сервис" labelPosition="center" my={4} />
      <Select
        label="Алгоритм трассировки"
        size="xs"
        placeholder={algorithms.length === 0 ? 'Загрузка…' : 'Выберите алгоритм'}
        data={algorithms.map((algorithm) => ({
          value: algorithm.id,
          label: `${algorithm.id} — ${algorithm.description}`,
        }))}
        value={selectedAlgorithm}
        onChange={setSelectedAlgorithm}
        allowDeselect={false}
        disabled={algorithms.length === 0}
      />
      {algorithmsError && (
        <Text size="xs" c="red">
          Не удалось получить список алгоритмов: {String(algorithmsError)}
        </Text>
      )}
      <FileButton accept=".geojson,.json,application/geo+json" onChange={runViaApi}>
        {(props) => (
          <Button {...props} size="xs" loading={busy} disabled={busy}>
            Загрузить и рассчитать
          </Button>
        )}
      </FileButton>

      {status && (
        <Text size="xs" c="dimmed">
          {status}
        </Text>
      )}
      {error && (
        <Alert color="red" title="Ошибка" p="xs">
          {error}
        </Alert>
      )}
    </Stack>
  );
}
