import type { ToolStep } from './types';
import { resultRecord } from './interactiveToolData.ts';
import { failedResult } from './intelligentUiData.ts';

export type MapData = { id: string; title: string; latitude: number; longitude: number; zoom: number };
const record = (value: unknown): Record<string, unknown> => value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {};
const coordinate = (value: unknown, limit: number): value is number => typeof value === 'number' && Number.isFinite(value) && Math.abs(value) <= limit;
export const isMapTool = (step: ToolStep) => step.label.replace(/^tools__/, '') === 'show_map';

export function mapData(step: ToolStep): MapData | null {
  if (!isMapTool(step) || step.status !== 'done' || failedResult(step.result)) return null;
  const result = resultRecord(step.result), ui = record(result?.ui), data = record(ui.data);
  if (result?.ok !== true || ui.version !== 1 || ui.kind !== 'map' || typeof ui.id !== 'string' || !/^[a-f0-9]{32}$/.test(ui.id)
    || typeof data.title !== 'string' || !data.title.trim() || Array.from(data.title).length > 120
    || !coordinate(data.latitude, 85) || !coordinate(data.longitude, 180)
    || typeof data.zoom !== 'number' || !Number.isInteger(data.zoom) || data.zoom < 2 || data.zoom > 18) return null;
  return { id: ui.id, title: data.title, latitude: data.latitude, longitude: data.longitude, zoom: data.zoom };
}

export function mapLink(data: MapData): string {
  return `https://www.openstreetmap.org/?mlat=${data.latitude}&mlon=${data.longitude}#map=${data.zoom}/${data.latitude}/${data.longitude}`;
}
