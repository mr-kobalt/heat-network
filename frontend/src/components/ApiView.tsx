import { useState } from 'react';
import { Anchor, Box, Group, Loader, Text } from '@mantine/core';
import { swaggerUiUrl } from '../api/client';

/**
 * Вкладка Swagger UI (ADR-0072): встроенный интерфейс OpenAPI backend-сервиса.
 * В dev/preview проксируются `/swagger-ui` и `/v3`, в Docker — через nginx.
 */
export function ApiView() {
  const url = swaggerUiUrl();
  const [loaded, setLoaded] = useState(false);

  return (
    <Box style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
      <Group
        justify="space-between"
        px="md"
        py="xs"
        wrap="nowrap"
        style={{ borderBottom: '1px solid var(--mantine-color-gray-3)' }}
      >
        <Text size="sm" c="dimmed">
          OpenAPI / Swagger UI сервиса
        </Text>
        <Anchor href={url} target="_blank" rel="noreferrer" size="sm">
          Открыть в новой вкладке
        </Anchor>
      </Group>
      {!loaded && (
        <Group gap="xs" px="md" py="sm">
          <Loader size="sm" />
          <Text size="sm" c="dimmed">Загрузка Swagger UI…</Text>
        </Group>
      )}
      <iframe
        title="Swagger UI"
        src={url}
        onLoad={() => setLoaded(true)}
        style={{ flex: 1, width: '100%', border: 0, minHeight: 0 }}
      />
    </Box>
  );
}
