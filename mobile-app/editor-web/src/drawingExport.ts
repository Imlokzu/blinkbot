export const MAX_EXPORT_DIMENSION = 4096;
export const MAX_EXPORT_DATA_URL_BYTES = 2_000_000;
const PREFIX = 'data:image/png;base64,';

/** Bound the encoded message before allocating a base64 copy of a large PNG. */
export async function pngDataUrl(blob: Blob): Promise<string> {
  if (blob.type !== 'image/png' || blob.size === 0) throw new Error('export_failed');
  if (Math.ceil(blob.size / 3) * 4 + PREFIX.length > MAX_EXPORT_DATA_URL_BYTES) throw new Error('export_too_large');
  return await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('export_failed'));
    reader.onload = () => {
      const value = reader.result;
      if (typeof value !== 'string' || !value.startsWith(PREFIX)) reject(new Error('export_failed'));
      else if (value.length > MAX_EXPORT_DATA_URL_BYTES) reject(new Error('export_too_large'));
      else resolve(value);
    };
    reader.readAsDataURL(blob);
  });
}
