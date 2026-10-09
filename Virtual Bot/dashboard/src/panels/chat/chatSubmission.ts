export type ToolAnswerRequest = { owner_id?: string; call_id: string; option_id: string; value: string; expected_revision: number };

/** Acceptance means a server-persisted user message, never a local insertion. */
export type ChatSubmission =
  | { accepted: true; messageId: string; sessionId: string }
  | { accepted: false; reason: 'busy' | 'failed' | 'stale' | 'empty' };

declare global {
  interface Window {
    __vbotSendMessage?: (text: string, expectedSession?: string, answer?: ToolAnswerRequest) => Promise<ChatSubmission>;
  }
}

export async function submitToolAnswer(text: string, sessionId?: string, answer?: ToolAnswerRequest): Promise<ChatSubmission> {
  if (!text.trim()) return { accepted: false, reason: 'empty' };
  if (!window.__vbotSendMessage) return { accepted: false, reason: 'stale' };
  try { return await window.__vbotSendMessage(text.trim(), sessionId, answer); }
  catch { return { accepted: false, reason: 'failed' }; }
}
