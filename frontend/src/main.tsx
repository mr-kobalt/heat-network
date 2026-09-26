import { createRoot } from 'react-dom/client';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import '@mantine/core/styles.css';
import '@mantine/notifications/styles.css';
import '@mantine/dropzone/styles.css';
import '@fontsource/inter/400.css';
import '@fontsource/inter/500.css';
import '@fontsource/inter/600.css';
import '@fontsource/inter/700.css';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { App } from './App';
import { theme } from './theme';
import './app.css';

const queryClient = new QueryClient();

// Без StrictMode: двойное монтирование в dev пересоздаёт карту MapLibre и
// приводит к «пустым» тайлам (в production-сборке эффекты одиночные).
createRoot(document.getElementById('root') as HTMLElement).render(
  <MantineProvider theme={theme} defaultColorScheme="light">
    <Notifications
      position="top-right"
      limit={3}
      classNames={{ root: 'app-notifications-root' }}
    />
    <QueryClientProvider client={queryClient}>
      <App />
    </QueryClientProvider>
  </MantineProvider>,
);
