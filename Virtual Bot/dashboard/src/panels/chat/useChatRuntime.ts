import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useExternalStoreRuntime, type AppendMessage, type ThreadMessageLike } from '@assistant-ui/react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { get, post } from '@/lib/api';
import { cleanEmotionTag, streamChat, type AgentStatus, type ChatAttachment } from '@/lib/chatStream';
import { t } from '@/lib/i18n';
import { t as chatT } from '@/locales/chat';
import { t as uploadT, localizeUploadError } from '@/locales/attachments';
import { updateActivity, finishActivity, restoreActivity } from './activity';
import {
  answerText, applyBreak, applyDelta, applyNote, applyStep, restoreParts, toParts, type LiveEntry,
} from './replyParts';
import { typingLeaveMs } from './Bubbles';
import { useToast } from '@/components/ui/Toaster';
import { estimateTokens } from './tokens';
import type { ChatMessage, SessionDetail, SessionSummary, ToolStep } from './types';
import { readChatSelection, rememberChatSelection } from './chatNavigation';
import { isImageGeneration } from './imageGenerationState';

/*
 * Зшивання нашого бекенда з assistant-ui.
 *
 * Береться саме useExternalStoreRuntime, а не useLocalRuntime: історію
 * розмов тримає бекенд (/api/sessions), і при перемиканні розмови масив
 * повідомлень має підмінюватись цілком. Локальний рантайм веде свій власний
 * список і такого не дозволяє.
 */

let localId = 0;
const nextId = () => `local-${++localId}`;

export function useChatRuntime(project = '') {
  const imageReplyId = useRef('');
  const replySettled = useRef(true);
  const client = useQueryClient();
  const toast = useToast();

  const [sessionId, setSessionIdState] = useState<string>('');
  const [sessionLoading, setSessionLoading] = useState(false);
  const [composerEpoch, setComposerEpoch] = useState(0);
  const sessionIdRef = useRef('');
  const restoration = useRef<Promise<SessionDetail> | null>(null);
  const queuedTicket = useRef<symbol | null>(null);
  const [queuedSend, setQueuedSend] = useState(false);
  const setSessionId = useCallback((id: string, remember = true) => {
    if (remember) rememberChatSelection(client, id, project);
    sessionIdRef.current = id;
    setSessionIdState(id);
  }, [client, project]);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  // Відповідь, яка ще пишеться. Окремо від messages, бо її текст міняється
  // на кожен чанк, а історія — ні.
  const [draft, setDraft] = useState<string | null>(null);
  // A reaction-only reply has no bubble to absorb the typing pill, so the
  // draft stays up long enough for the pill to collapse instead of vanishing.
  const [settling, setSettling] = useState(false);
  const settleTimer = useRef<number | null>(null);
  const [steps, setSteps] = useState<ToolStep[]>([]);
  const stepsRef = useRef<ToolStep[]>([]);
  // The reply being written, in order: narration, tools, answer bubbles.
  const [timeline, setTimeline] = useState<LiveEntry[]>([]);
  const timelineRef = useRef<LiveEntry[]>([]);
  const draftRef = useRef('');
  const generation = useRef(0);
  const [turnId, setTurnId] = useState(0);
  // Whether the last painted frame was showing the typing pill. A reply that
  // arrives in the same chunk as `done` never paints on the draft, so the
  // saved message has to open out of the pill itself.
  const pillOnScreen = useRef(false);
  const [agentStatus, setAgentStatus] = useState<AgentStatus>('connecting');
  const [streamModel, setStreamModel] = useState('');
  // Скільки реплік сховано за переказом. 0 — розмову не стискали.
  const [compactedFrom, setCompactedFrom] = useState(0);
  const abortRef = useRef<AbortController | null>(null);

  // Only the request-scoped chat stream may contribute to this transcript.
  // The global bot event bus also contains other sessions and background work.
  useEffect(() => () => {
    generation.current += 1;
    abortRef.current?.abort();
    if (settleTimer.current) window.clearTimeout(settleTimer.current);
  }, []);

  // After each painted frame, remember whether the typing pill was actually
  // up. `done` often shares a chunk with the answer, so this ref — not the
  // timeline the chunk just wrote — is what decides the opening bubble.
  useEffect(() => {
    if (draft === null || settling) return;
    const parts = toParts(timeline);
    const last = parts[parts.length - 1];
    const active = last?.type === 'steps'
      && steps.some((step) => step.status === 'active' && last.ids.includes(step.id));
    pillOnScreen.current = !last || (last.type === 'text' ? Boolean(last.note) : !active);
  }, [draft, settling, timeline, steps]);

  const sessions = useQuery({
    queryKey: ['sessions'],
    queryFn: async () => (await get<{ sessions: SessionSummary[] }>('/api/sessions')).sessions ?? [],
  });

  /** Open only an explicitly selected conversation, never a guessed list item. */
  const openSession = useCallback(
    async (id: string, remember = true, resetComposer = true) => {
      if (resetComposer) setComposerEpoch((epoch) => epoch + 1);
      const version = ++generation.current;
      abortRef.current?.abort();
      abortRef.current = null;
      queuedTicket.current = null;
      setQueuedSend(false);
      setSessionId(id, remember);
      setMessages([]);
      setSessionLoading(Boolean(id));
      if (settleTimer.current) window.clearTimeout(settleTimer.current);
      setSettling(false);
      setDraft(null);
      setSteps([]);
      stepsRef.current = [];
      setTimeline([]);
      timelineRef.current = [];
      draftRef.current = '';
      setCompactedFrom(0);
      if (!id) {
        restoration.current = null;
        setMessages([]);
        return;
      }
      const request = get<SessionDetail>(`/api/sessions/${encodeURIComponent(id)}`);
      restoration.current = request;
      try {
        const data = await request;
        if (version !== generation.current) return;
        // Переказ завжди стоїть першим і єдиним — саме так його пише
        // chat_store.compact.
        setCompactedFrom(Number(data.messages?.[0]?.compacted_from ?? 0));
        setMessages(
          (data.messages ?? []).map((message, index) => {
            const assistant = message.role === 'assistant';
            const content = assistant ? cleanEmotionTag(message.content ?? '') : message.content ?? '';
            const steps = restoreActivity(message.steps);
            return {
              id: `${id}-${index}`,
              // Saved before ids existed: the server resolves `idx:N`.
              serverId: message.id || `idx:${index}`,
              role: assistant ? 'assistant' : 'user',
              content,
              ts: message.ts,
              attachments: message.attachments,
              steps,
              ...(assistant ? { parts: restoreParts(message.parts, content, steps), reactions: message.reactions } : {}),
              ...(!assistant && message.reaction ? { reaction: message.reaction } : {}),
            };
          }),
        );
      } catch (error) {
        if (version === generation.current) {
          setSessionId('');
          toast.error(t('chat.openError'), (error as Error).message);
        }
      } finally {
        if (restoration.current === request) restoration.current = null;
        if (version === generation.current) setSessionLoading(false);
      }
    },
    [toast, setSessionId],
  );

  const newSession = useCallback(() => {
    setComposerEpoch((epoch) => epoch + 1);
    generation.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    restoration.current = null;
    queuedTicket.current = null;
    setQueuedSend(false);
    setSessionId('');
    setSessionLoading(false);
    setMessages([]);
    if (settleTimer.current) window.clearTimeout(settleTimer.current);
    setSettling(false);
    setDraft(null);
    setSteps([]);
    stepsRef.current = [];
    setTimeline([]);
    timelineRef.current = [];
    draftRef.current = '';
    setCompactedFrom(0);
  }, [setSessionId]);

  useEffect(() => {
    // The QueryClient identifies this loaded page. No browser storage is used,
    // so fresh visits stay empty while section navigation retains selection.
    const selected = readChatSelection(client, project);
    // Merely visiting an untouched project must not erase the main selection.
    void openSession(selected, Boolean(selected));
    // Restore on mount or project change, not when a callback rerenders.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, project]);

  const send = useCallback(
    async (text: string, attachments: unknown[] = []) => {
      const trimmed = text.trim() || (attachments.length ? uploadT('upload.filePrompt') : '');
      if (!trimmed || abortRef.current) return;
      if (queuedTicket.current) { toast.error(chatT('composer.waitHistory')); return; }
      const pendingHistory = restoration.current;
      const selectedGeneration = generation.current;
      if (pendingHistory) {
        const ticket = Symbol('queued-send');
        queuedTicket.current = ticket;
        setQueuedSend(true);
        try {
          await pendingHistory;
          if (queuedTicket.current !== ticket || selectedGeneration !== generation.current || abortRef.current) return;
        } catch { return; }
        finally {
          if (queuedTicket.current === ticket) {
            queuedTicket.current = null;
            setQueuedSend(false);
          }
        }
      }
      const targetSession = sessionIdRef.current;
      setSessionLoading(false);

      const controller = new AbortController();
      abortRef.current = controller;
      const version = ++generation.current;
      const isCurrent = () => version === generation.current && !controller.signal.aborted;
      setTurnId(version);

      const safeAttachments = attachments.filter(
        (item): item is ChatAttachment => Boolean(item && typeof item === 'object' && 'url' in item),
      );
      const userId = nextId();
      imageReplyId.current = '';
      replySettled.current = false;
      setMessages((current) => [...current, {
        id: userId, role: 'user', content: trimmed, attachments: safeAttachments,
      }]);
      if (settleTimer.current) window.clearTimeout(settleTimer.current);
      setSettling(false);
      setDraft('');
      pillOnScreen.current = true;
      setSteps([]);
      stepsRef.current = [];
      setTimeline([]);
      timelineRef.current = [];
      draftRef.current = '';
      setAgentStatus('connecting');
      setStreamModel('');

      let terminal = false;
      const updateTimeline = (next: LiveEntry[]) => {
        timelineRef.current = next;
        setTimeline(next);
        draftRef.current = answerText(next);
        setDraft(draftRef.current);
      };
      const setUserMessage = (patch: Partial<ChatMessage>) => {
        setMessages((current) => current.map((item) => (item.id === userId ? { ...item, ...patch } : item)));
      };
      const preserveInterrupted = () => {
        if (!isCurrent() || terminal) return;
        terminal = true;
        replySettled.current = true;
        const finished = finishActivity(stepsRef.current);
        const parts = toParts(timelineRef.current);
        if (parts.length || finished.length) {
          const replyId = finished.some(isImageGeneration) ? (imageReplyId.current ||= nextId()) : nextId();
          setMessages((current) => [...current, {
            id: replyId, role: 'assistant', content: draftRef.current, steps: finished, parts,
          }]);
        }
        setDraft(null);
        setSteps(finished);
      };

      await streamChat(
        {
          message: trimmed,
          session_id: targetSession || undefined,
          attachments: safeAttachments,
          // reasoning_effort тут більше не шлемо. Він діяв лише на прямий
          // виклик Omni (картинки), а в чаті відповідає OpenClaw, і глибину
          // думання йому задає власний конфіг — див. /api/brain/thinking.
          // Поле, яке нічого не міняє в тому шляху, яким іде відповідь,
          // створювало б ілюзію керування.
        },
        {
          onDelta: (chunk) => {
            if (!isCurrent() || terminal) return;
            updateTimeline(applyDelta(timelineRef.current, chunk));
          },
          onBreak: () => {
            if (!isCurrent() || terminal) return;
            updateTimeline(applyBreak(timelineRef.current));
          },
          onNote: (id, bubbles) => {
            if (!isCurrent() || terminal) return;
            updateTimeline(applyNote(timelineRef.current, id, bubbles));
          },
          onReaction: (emoji) => {
            if (!isCurrent() || terminal) return;
            setUserMessage({ reaction: emoji });
          },
          onTool: (event) => {
            if (!isCurrent() || terminal) return;
            stepsRef.current = updateActivity(stepsRef.current, event);
            if (stepsRef.current.some(isImageGeneration)) imageReplyId.current ||= nextId();
            setSteps(stepsRef.current);
            const id = event.step?.id ?? event.call_id;
            if (id) updateTimeline(applyStep(timelineRef.current, id));
          },
          onStatus: (status) => {
            if (isCurrent() && !terminal) setAgentStatus(status);
          },
          onModel: (model) => {
            if (isCurrent() && !terminal) setStreamModel(model);
          },
          onSession: (id) => {
            if (isCurrent() && !terminal) setSessionId(id);
          },
          onDone: (result) => {
            if (!isCurrent() || terminal) return;
            terminal = true;
            replySettled.current = true;
            const finished = result.steps ?? finishActivity(stepsRef.current);
            const parts = restoreParts(result.parts, result.reply, finished);
            const textBubbles = parts.filter((part) => part.type === 'text').length;
            const fromTyping = pillOnScreen.current && textBubbles > 0 ? textBubbles - 1 : undefined;
            pillOnScreen.current = false;
            const replyId = finished.some(isImageGeneration) ? (imageReplyId.current ||= nextId()) : nextId();
            setMessages((current) => [
              ...current.map((item) => (item.id === userId ? {
                ...item,
                serverId: result.user_message_id || item.serverId,
                reaction: result.reaction || item.reaction,
              } : item)),
              { id: replyId, role: 'assistant', content: result.reply, steps: finished, parts,
                serverId: result.assistant_message_id || undefined,
                model: result.model || streamModel, fromTyping },
            ]);
            setSteps(finished);
            // No bubble grew out of the typing pill (a bare reaction). Keep
            // the draft mounted, but no longer "running", so the pill can
            // collapse before it goes away.
            if (!parts.length) {
              setSettling(true);
              if (settleTimer.current) window.clearTimeout(settleTimer.current);
              settleTimer.current = window.setTimeout(() => {
                setSettling(false);
                if (generation.current === version) setDraft(null);
              }, typingLeaveMs() + 40);
            } else {
              setDraft(null);
            }
            if (result.model) setStreamModel(result.model);
            // Each assistant message owns its activity, including saved history.
            // Бекенд міг створити нову розмову й дати їй назву у фоні.
            if (result.session_id && result.session_id !== targetSession) setSessionId(result.session_id);
            void client.invalidateQueries({ queryKey: ['sessions'] });
            void client.invalidateQueries({ queryKey: ['models'] });
          },
          onError: (message) => {
            if (!isCurrent() || terminal) return;
            preserveInterrupted();
            toast.error(t('chat.replyError'), localizeUploadError(message) ?? message);
          },
        },
        controller.signal,
      ).catch((error: unknown) => {
        if (!isCurrent() || terminal) return;
        preserveInterrupted();
        if ((error as Error)?.name !== 'AbortError') toast.error(t('chat.connectionError'), (error as Error).message);
      });

      if (abortRef.current === controller) abortRef.current = null;
    },
    [client, sessionId, toast, setSessionId],
  );

  /**
   * Ask the last question again.
   *
   * The failed or unwanted reply is dropped along with the question that
   * produced it, and the question is sent afresh — otherwise the model would
   * see its own rejected answer in the context and tend to repeat it. The
   * attachments go back with it: a retry without the picture is a different
   * question.
   */
  const retry = useCallback(() => {
    if (abortRef.current) return;
    const lastUser = messages.map((item) => item.role).lastIndexOf('user');
    if (lastUser === -1) return;
    const question = messages[lastUser];
    setMessages(messages.slice(0, lastUser));
    setSteps([]);
    stepsRef.current = [];
    void send(question.content, question.attachments ?? []);
  }, [messages, send]);

  const cancel = useCallback(async () => {
    if (queuedTicket.current) {
      queuedTicket.current = null;
      setQueuedSend(false);
      return; // Cancel the queued submission, while history continues loading.
    }
    if (!abortRef.current || replySettled.current) return;
    replySettled.current = true;
    generation.current += 1;
    abortRef.current?.abort();
    abortRef.current = null;
    const finished = finishActivity(stepsRef.current);
    const content = draftRef.current;
    const parts = toParts(timelineRef.current);
    if (parts.length || finished.length) {
      const replyId = finished.some(isImageGeneration) ? (imageReplyId.current ||= nextId()) : nextId();
      setMessages((list) => [...list, { id: replyId, role: 'assistant', content, steps: finished, parts }]);
    }
    setDraft(null);
    setSteps(finished);
  }, []);

  /**
   * React to one bubble of a bot reply (null removes the reaction).
   *
   * Optimistic: a reaction that waits for a round trip feels broken. A reply
   * still being written has no server id yet, so it cannot be reacted to.
   */
  const react = useCallback(
    async (messageId: string, bubble: number, emoji: string | null) => {
      const target = messages.find((item) => item.id === messageId);
      if (!target?.serverId || !sessionId) return;
      const before = target.reactions ?? {};
      const next = { ...before };
      if (emoji) next[String(bubble)] = emoji;
      else delete next[String(bubble)];
      const apply = (reactions: Record<string, string>) =>
        setMessages((list) => list.map((item) => (item.id === messageId ? { ...item, reactions } : item)));
      apply(next);
      try {
        await post(`/api/sessions/${encodeURIComponent(sessionId)}/reactions`, {
          message_id: target.serverId, bubble, emoji,
        });
      } catch (error) {
        apply(before);
        toast.error(chatT('reaction.failed'), (error as Error).message);
      }
    },
    [messages, sessionId, toast],
  );

  // Скільки контексту зʼїла розмова. Рахуємо по видимій історії плюс те,
  // що зараз друкується, — саме це поїде наступним запитом.
  const usedTokens = useMemo(
    () => estimateTokens(draft !== null ? [...messages, { content: draft }] : messages),
    [messages, draft],
  );

  // A generated-media surface must remain mounted when the draft becomes
  // the saved answer; otherwise its private download and reveal restart.
  const liveReplyId = steps.some(isImageGeneration) ? imageReplyId.current : 'draft';
  const visible = useMemo<ChatMessage[]>(
    () => (draft !== null
      ? [...messages, { id: liveReplyId, role: 'assistant', content: draft, steps, parts: toParts(timeline) }]
      : messages),
    [messages, draft, steps, timeline, liveReplyId],
  );

  const runtime = useExternalStoreRuntime<ChatMessage>({
    isRunning: draft !== null && !settling,
    isLoading: sessionLoading,
    messages: visible,
    convertMessage: (message): ThreadMessageLike => ({
      id: message.id,
      role: message.role,
      content: [{ type: 'text', text: message.content }],
      metadata: { custom: { steps: message.steps ?? [], running: draft !== null && message.id === liveReplyId && !settling,
        agentStatus: draft !== null && message.id === liveReplyId ? agentStatus : undefined,
        model: message.model || (draft !== null && message.id === liveReplyId ? streamModel : undefined),
        parts: message.parts, reaction: message.reaction, reactions: message.reactions,
        reactable: Boolean(message.serverId && sessionId),
        fromTyping: message.fromTyping, attachments: message.attachments } },
    }),
    onNew: async (message: AppendMessage) => {
      const text = message.content
        .filter((part): part is { type: 'text'; text: string } => part.type === 'text')
        .map((part) => part.text)
        .join('');
      await send(text);
    },
    onCancel: cancel,
  });

  return {
    runtime,
    sessions: sessions.data ?? [],
    sessionsLoading: sessions.isPending,
    sessionId,
    openSession,
    newSession,
    steps,
    running: draft !== null && !settling,
    queuedSend,
    composerEpoch,
    compactedFrom,
    usedTokens,
    // Сира історія — для панелі витрат (вхідні/вихідні рахуються окремо).
    messages,
    visibleMessages: visible,
    turnId,
    // PromptBar володіє власним текстом, тож надсилання й зупинка потрібні
    // назовні напряму, повз композер assistant-ui.
    send,
    cancel,
    retry,
    react,
  };
}
