import { t } from '@/lib/i18n';
import { t as inferenceText } from '@/locales/inference';
import { t as controlText } from '@/locales/control';

const SECTION_LABELS: Record<string, () => string> = {
  overview: () => t('nav.overview'),
  chat: () => t('nav.chat'),
  memory: () => t('nav.memory'),
  files: () => t('nav.files'),
  browser: () => t('nav.browser'),
  vision: () => t('nav.vision'),
  services: () => t('nav.services'),
  inference: () => inferenceText('nav'),
  agents: () => controlText('nav.agents'),
  sessions: () => controlText('nav.sessions'),
  automation: () => controlText('nav.automation'),
  channels: () => controlText('nav.channels'),
  logs: () => t('nav.logs'),
  settings: () => t('nav.settings'),
} as const;

export function mobileSectionLabel(id: string): string | null {
  return SECTION_LABELS[id]?.() ?? null;
}
