import type { FeatureCollection } from '../types';
import { parseFeatureCollection } from '../types';

/**
 * Базовый адрес API. Пути ниже уже содержат префикс /api, поэтому если
 * VITE_API_BASE_URL задан с суффиксом /api (частая ошибка), его убираем,
 * чтобы не получить /api/api/...
 */
function resolveBaseUrl(): string {
  const raw = ((import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '').replace(/\/+$/, '');
  return raw.endsWith('/api') ? raw.slice(0, -4) : raw;
}

const baseUrl = resolveBaseUrl();

export interface DatasetResponse {
  id: string;
  status: string;
  originalFilename?: string;
  objectCounts?: Record<string, number>;
  bbox?: string;
  warnings?: unknown[];
}

export interface RunResponse {
  id: string;
  datasetId: string;
  status: 'PENDING' | 'RUNNING' | 'DONE' | 'PARTIAL' | 'FAILED';
  summary?: Record<string, unknown>;
  error?: string;
}

async function ensureOk(response: Response): Promise<Response> {
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status}: ${text || response.statusText}`);
  }
  return response;
}

export async function uploadDataset(file: File): Promise<DatasetResponse> {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch(`${baseUrl}/api/v1/datasets`, { method: 'POST', body: form });
  await ensureOk(response);
  return response.json();
}

export async function createRun(datasetId: string): Promise<RunResponse> {
  const response = await fetch(`${baseUrl}/api/v1/datasets/${datasetId}/runs`, { method: 'POST' });
  await ensureOk(response);
  return response.json();
}

export async function getRun(runId: string): Promise<RunResponse> {
  const response = await ensureOk(await fetch(`${baseUrl}/api/v1/runs/${runId}`));
  return response.json();
}

export async function fetchResult(runId: string): Promise<FeatureCollection> {
  const response = await ensureOk(await fetch(`${baseUrl}/api/v1/runs/${runId}/result`));
  return parseFeatureCollection(await response.json());
}

export async function runAndFetch(
  file: File,
  onStatus: (status: string) => void,
): Promise<FeatureCollection> {
  onStatus('Загрузка набора…');
  const dataset = await uploadDataset(file);
  onStatus('Запуск расчёта…');
  const created = await createRun(dataset.id);
  let run = created;
  while (run.status === 'PENDING' || run.status === 'RUNNING') {
    await new Promise((resolve) => setTimeout(resolve, 1000));
    run = await getRun(created.id);
    onStatus(`Расчёт: ${run.status}`);
  }
  if (run.status === 'FAILED') {
    throw new Error(run.error ?? 'Расчёт завершился ошибкой');
  }
  onStatus('Загрузка результата…');
  return fetchResult(run.id);
}
