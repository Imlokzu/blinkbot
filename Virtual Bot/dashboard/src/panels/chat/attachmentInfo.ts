export interface AttachmentInfo {
  url: string; name: string; type: string; size?: number;
}

/** Only our authenticated upload route can become an attachment preview. */
export function attachmentInfo(value: unknown): AttachmentInfo | null {
  if (!value || typeof value !== 'object') return null;
  const item = value as Record<string, unknown>;
  if (typeof item.url !== 'string' || item.url !== item.url.trim() || !/^\/uploads\/[A-Za-z0-9_.-]{1,220}$/.test(item.url) || /\/\.{1,2}$/.test(item.url)) return null;
  const displayName = typeof item.name === 'string' ? item.name.replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, 180) : '';
  const name = displayName || item.url.split('/').pop()!;
  const type = typeof item.type === 'string' ? item.type.toLowerCase() : '';
  return { url: item.url, name, type, ...(typeof item.size === 'number' && Number.isFinite(item.size) && item.size >= 0 ? { size: item.size } : {}) };
}

export const isAttachmentImage = (file: AttachmentInfo) => /\.(png|jpe?g|webp|gif)$/i.test(file.url);
export function attachmentFormat(file: AttachmentInfo): string {
  return (file.url.split('.').pop() ?? '').toUpperCase();
}
