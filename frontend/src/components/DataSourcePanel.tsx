import { useState } from 'react';
import { Alert, Button, Divider, FileButton, Stack, Text } from '@mantine/core';
import { parseFeatureCollection } from '../types';
import { useStore } from '../store';
import { runAndFetch } from '../api/client';

export function DataSourcePanel() {
  const setInput = useStore((state) => state.setInput);
  const setResult = useStore((state) => state.setResult);
  const [status, setStatus] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

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
      const result = await runAndFetch(file, setStatus);
      setResult(result);
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
