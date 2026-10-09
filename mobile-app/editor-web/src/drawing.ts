import { convertToExcalidrawElements } from '@excalidraw/excalidraw';
import type { ExcalidrawElementSkeleton } from '@excalidraw/excalidraw/data/transform';
import type { ExcalidrawInitialDataState } from '@excalidraw/excalidraw/types';

export type Scene = ExcalidrawInitialDataState;
export function readDrawing(source: string): Scene {
  const data: unknown = JSON.parse(source);
  if (!data || typeof data !== 'object') throw new Error('invalid_drawing');
  const raw = Array.isArray(data) ? data : (data as { elements?: unknown }).elements;
  if (!Array.isArray(raw) || raw.length > 20_000 || raw.some((item) => !item || typeof item !== 'object' || typeof item.type !== 'string')) {
    throw new Error('invalid_drawing');
  }
  const full = raw.filter((item) => 'version' in item);
  const skeleton = raw.filter((item) => !('version' in item));
  // Never reconvert full elements: doing so breaks the bindings of saved arrows.
  const elements = skeleton.length
    ? [...full, ...convertToExcalidrawElements(skeleton as ExcalidrawElementSkeleton[], { regenerateIds: false })]
    : raw;
  const scene = (Array.isArray(data) ? {} : data) as Scene;
  return { ...scene, elements, appState: { ...scene.appState, collaborators: new Map() } };
}

export async function readMermaid(source: string): Promise<Scene> {
  const { parseMermaidToExcalidraw } = await import('@excalidraw/mermaid-to-excalidraw');
  const { elements, files } = await parseMermaidToExcalidraw(source, { themeVariables: { fontSize: '16px' } });
  const lineBreaks = (value: unknown) => typeof value === 'string' ? value.replace(/<br\s*\/?>/gi, '\n') : value;
  for (const element of elements as { label?: { text?: unknown }; text?: unknown }[]) {
    if (element.label) element.label.text = lineBreaks(element.label.text);
    if ('text' in element) element.text = lineBreaks(element.text);
  }
  return { elements: convertToExcalidrawElements(elements), files: files as Scene['files'] };
}
