import type { RunStatus } from './store';

/** Подписи этапов расчёта (ADR-0057), приходящих с бэкенда ключами. */
export const STAGE_LABELS: Record<string, string> = {
  ingest: 'Загрузка данных',
  graph: 'Построение сети',
  obstacles: 'Запретные зоны',
  special: 'Спецпроходы',
  exits: 'Точки выхода',
  generate: 'Поиск трасс',
  write: 'Запись результата',
  trace: 'Сохранение этапов',
  done: 'Готово',
};

export function stageLabel(stage: string | null | undefined): string {
  if (!stage) {
    return 'Подготовка';
  }
  return STAGE_LABELS[stage] ?? stage;
}

/** Форматирует длительность как `мм:сс` (для таймера расчёта). */
export function formatDuration(ms: number): string {
  const totalSeconds = Math.max(0, Math.floor(ms / 1000));
  const minutes = Math.floor(totalSeconds / 60);
  const seconds = totalSeconds % 60;
  return `${String(minutes).padStart(2, '0')}:${String(seconds).padStart(2, '0')}`;
}

export const STATUS_LABELS: Record<RunStatus, string> = {
  IDLE: '',
  PENDING: 'В очереди',
  RUNNING: 'Выполняется',
  DONE: 'Успешно',
  PARTIAL: 'Успешно (есть неподключённые)',
  FAILED: 'Ошибка',
};
