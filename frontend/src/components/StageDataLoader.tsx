import { useEffect } from 'react';
import { RESULT_STAGE, useStore } from '../store';
import { fetchGridMask, fetchStage } from '../api/client';
import { stageFileKey } from '../types';

/**
 * Загружает данные активного этапа по требованию (ADR-0036). Компонент
 * безвизуальный — только синхронизирует store с API.
 */
export function StageDataLoader() {
  const runId = useStore((state) => state.runId);
  const traced = useStore((state) => state.traced);
  const activeStage = useStore((state) => state.activeStage);
  const stages = useStore((state) => state.stages);
  const treePass = useStore((state) => state.treePass);
  const stageData = useStore((state) => state.stageData);
  const gridMask = useStore((state) => state.gridMask);
  const setStageData = useStore((state) => state.setStageData);
  const setGridMask = useStore((state) => state.setGridMask);

  useEffect(() => {
    if (!traced || !runId || activeStage === RESULT_STAGE || activeStage === 'input') {
      return undefined;
    }
    const descriptor = stages.find((stage) => stage.id === activeStage);
    const key = stageFileKey(descriptor, treePass);
    let cancelled = false;

    if (activeStage === 'grid') {
      if (gridMask) {
        return undefined;
      }
      fetchGridMask(runId)
        .then((mask) => {
          if (!cancelled) {
            setGridMask(mask);
          }
        })
        .catch((error) => console.error('[stages] не удалось загрузить сетку', error));
      return () => {
        cancelled = true;
      };
    }

    if (!key || stageData[key]) {
      return undefined;
    }
    fetchStage(runId, key)
      .then((data) => {
        if (!cancelled) {
          setStageData(key, data);
        }
      })
      .catch((error) => console.error(`[stages] не удалось загрузить этап ${key}`, error));
    return () => {
      cancelled = true;
    };
  }, [traced, runId, activeStage, stages, treePass, stageData, gridMask, setStageData, setGridMask]);

  return null;
}
