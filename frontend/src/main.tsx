import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import '@mantine/core/styles.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';

const queryClient = new QueryClient();

// Без StrictMode: двойное монтирование в dev пересоздаёт карту MapLibre и
// приводит к «пустым» тайлам (в production-сборке эффекты одиночные).
createRoot(document.getElementById('root') as HTMLElement).render(
  <MantineProvider defaultColorScheme="light">
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </MantineProvider>,
);
