import {
  LayoutGrid,
  Brain,
  FolderTree,
  Globe,
  MessageSquare,
  ScrollText,
  Settings,
  Sliders,
  Eye,
  ChartNoAxesCombined,
  Bot,
  CalendarClock,
  MessagesSquare,
  Radio,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { t as inferenceText } from '@/locales/inference';
import { t as controlText } from '@/locales/control';

/** Dashboard sections, in the same order as the dock and mobile navigation. */
export interface SectionDef {
  id: string;
  label: string;
  icon: LucideIcon;
  /** Ключовий розділ: у вузькому доку лишається на видноті першим. */
  primary?: boolean;
}

export const SECTIONS: SectionDef[] = [
  { id: 'overview', label: 'Огляд', icon: LayoutGrid, primary: true },
  { id: 'chat', label: 'Чат', icon: MessageSquare, primary: true },
  { id: 'memory', label: "Пам'ять", icon: Brain, primary: true },
  { id: 'files', label: 'Файли', icon: FolderTree, primary: true },
  { id: 'browser', label: 'Браузер', icon: Globe },
  { id: 'vision', label: 'Зір', icon: Eye },
  { id: 'services', label: 'Сервіси', icon: Sliders },
  { id: 'inference', get label() { return inferenceText('nav'); }, icon: ChartNoAxesCombined },
  { id: 'agents', get label() { return controlText('nav.agents'); }, icon: Bot },
  { id: 'sessions', get label() { return controlText('nav.sessions'); }, icon: MessagesSquare },
  { id: 'automation', get label() { return controlText('nav.automation'); }, icon: CalendarClock },
  { id: 'channels', get label() { return controlText('nav.channels'); }, icon: Radio },
  { id: 'logs', label: 'Логи', icon: ScrollText },
  { id: 'settings', label: 'Налаштування', icon: Settings, primary: true },
];

export const SECTION_IDS = SECTIONS.map((section) => section.id);
export const DEFAULT_SECTION = 'overview';

export function findSection(id: string): SectionDef {
  return SECTIONS.find((section) => section.id === id) ?? SECTIONS[0];
}
