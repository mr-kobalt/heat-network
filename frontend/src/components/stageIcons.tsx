import type { ComponentType } from 'react';
import {
  IconAdjustments,
  IconAlertTriangle,
  IconBuilding,
  IconFileImport,
  IconFlagCheck,
  IconGridDots,
  IconLogout,
  IconNetwork,
  IconPlugConnected,
  IconRefresh,
  IconRoute,
  IconTrees,
  IconWand,
} from '@tabler/icons-react';
import { RESULT_STAGE } from '../store';

const STAGE_ICONS: Record<string, ComponentType<{ size?: number | string }>> = {
  input: IconFileImport,
  network: IconNetwork,
  restrictions: IconAlertTriangle,
  exits: IconLogout,
  ties: IconPlugConnected,
  grid: IconGridDots,
  trees: IconTrees,
  relink: IconRefresh,
  contract: IconAdjustments,
  refine: IconWand,
  chambers: IconBuilding,
  [RESULT_STAGE]: IconFlagCheck,
};

/** Иконка этапа по его идентификатору (общая для панели и свёрнутой полоски). */
export function stageIcon(id: string): ComponentType<{ size?: number | string }> {
  return STAGE_ICONS[id] ?? IconRoute;
}
