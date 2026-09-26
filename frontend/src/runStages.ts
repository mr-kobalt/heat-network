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

export const STATUS_LABELS: Record<RunStatus, string> = {
  IDLE: '',
  PENDING: 'В очереди',
  RUNNING: 'Выполняется',
  DONE: 'Успешно',
  PARTIAL: 'Успешно (есть неподключённые)',
  FAILED: 'Ошибка',
};
