import type { FeatureCollection, GridMask, StageManifest } from '../types';
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
  algorithm?: string;
  traced?: boolean;
  stage?: string | null;
  progress?: number | null;
  summary?: Record<string, unknown>;
  error?: string;
}

export interface AlgorithmResponse {
  id: string;
  description: string;
  defaultAlgorithm: boolean;
}

async function ensureOk(response: Response): Promise<Response> {
  if (!response.ok) {
    const text = await response.text();
    throw new Error(`${response.status}: ${text || response.statusText}`);
  }
  return response;
}

export async function fetchAlgorithms(): Promise<AlgorithmResponse[]> {
  const response = await ensureOk(await fetch(`${baseUrl}/api/v1/algorithms`));
  return response.json();
}

export async function uploadDataset(file: File): Promise<DatasetResponse> {
  const form = new FormData();
  form.append('file', file);
  const response = await fetch(`${baseUrl}/api/v1/datasets`, { method: 'POST', body: form });
  await ensureOk(response);
  return response.json();
}

export async function createRun(
  datasetId: string,
  algorithm?: string | null,
  trace = false,
): Promise<RunResponse> {
  const params = new URLSearchParams();
  if (algorithm) {
    params.set('algorithm', algorithm);
  }
  if (trace) {
    params.set('trace', 'true');
  }
  const query = params.toString() ? `?${params.toString()}` : '';
  const response = await fetch(`${baseUrl}/api/v1/datasets/${datasetId}/runs${query}`, {
    method: 'POST',
  });
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

/** Сырой файл результата (формат заказчика) для скачивания. */
export async function fetchResultBlob(runId: string): Promise<Blob> {
  const response = await ensureOk(await fetch(`${baseUrl}/api/v1/runs/${runId}/result`));
  return response.blob();
}

export async function fetchStages(runId: string): Promise<StageManifest> {
  const response = await ensureOk(await fetch(`${baseUrl}/api/v1/runs/${runId}/stages`));
  return response.json();
}

export async function fetchStage(runId: string, stageId: string): Promise<FeatureCollection> {
  const response = await ensureOk(
    await fetch(`${baseUrl}/api/v1/runs/${runId}/stages/${encodeURIComponent(stageId)}`),
  );
  return parseFeatureCollection(await response.json());
}

export async function fetchGridMask(runId: string): Promise<GridMask> {
  const response = await ensureOk(
    await fetch(`${baseUrl}/api/v1/runs/${runId}/stages/grid`),
  );
  return response.json();
}

export interface RunFetchResult {
  runId: string;
  result: FeatureCollection;
  traced: boolean;
  status: RunResponse['status'];
}

/** Хуки хода расчёта (ADR-0057). */
export interface RunHooks {
  /** Началась загрузка набора на сервис. */
  onUpload?: () => void;
  /** Очередное состояние запуска (создание и опрос). */
  onRun?: (run: RunResponse) => void;
  /** Расчёт завершён, загружается результат. */
  onResultLoading?: () => void;
}

export async function runAndFetch(
  file: File,
  algorithm: string | null | undefined,
  hooks: RunHooks = {},
): Promise<RunFetchResult> {
  hooks.onUpload?.();
  const dataset = await uploadDataset(file);
  const created = await createRun(dataset.id, algorithm, true);
  let run = created;
  hooks.onRun?.(run);
  while (run.status === 'PENDING' || run.status === 'RUNNING') {
    await new Promise((resolve) => setTimeout(resolve, 700));
    run = await getRun(created.id);
    hooks.onRun?.(run);
  }
  if (run.status === 'FAILED') {
    throw new Error(run.error ?? 'Расчёт завершился ошибкой');
  }
  hooks.onResultLoading?.();
  const result = await fetchResult(run.id);
  return { runId: run.id, result, traced: Boolean(run.traced), status: run.status };
}
