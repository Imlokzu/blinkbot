import { StrictMode, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AssistantRuntimeProvider, useExternalStoreRuntime } from '@assistant-ui/react';
import { Thread } from '../src/panels/chat/Thread';
import { ToastProvider } from '../src/components/ui/Toaster';
import { InteractiveSessionContext, ToolOwnerContext } from '../src/panels/chat/InteractiveSessionContext';
import type { ToolStep } from '../src/panels/chat/types';
import '../src/index.css';

const html = `<div class="numbers"><label for="bill">Bill total (€)</label><input id="bill" type="number" min="0" value="120"><label for="people">People</label><input id="people" type="range" min="1" max="12" value="3"><p id="count"></p><p class="result"><output id="share"></output><span> per person</span></p></div><button id="follow">Explain this split</button>`;
const javascript = `const bill = document.getElementById('bill'), people = document.getElementById('people');
function update(){document.getElementById('share').textContent = '€' + (Number(bill.value)/Number(people.value)).toFixed(2);document.getElementById('count').textContent = people.value + ' people';}
bill.addEventListener('input',update); people.addEventListener('input',update); update();
document.getElementById('follow').addEventListener('click',()=>BlinkUI.requestFollowUp('Explain splitting €' + bill.value + ' between ' + people.value + ' people.'));
try { parent.document.body.dataset.compromised = 'yes'; } catch {}
fetch('/api/private-probe').then(()=>parent.postMessage({probe:'network-escaped'},'*')).catch(()=>{});
`;
const payload = JSON.stringify({ version: 1, html, javascript, css: '.numbers{display:grid;gap:10px}.result{margin:12px 0 20px}output{font-size:42px;font-weight:600;letter-spacing:-.03em}.result span{color:var(--muted)}#follow{width:100%}' });
const hash = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(payload))), byte => byte.toString(16).padStart(2, '0')).join('');
const id = 'a'.repeat(32);
const fixtureState = { fetches: [] as string[], posts: [] as { text: string; session?: string }[], busy: false, corrupt: false };
const receipt = { ok: true, ui: { id, version: 1, kind: 'intelligent_ui', data: {
  title: 'Split the bill', summary: 'Change the total or group size. Everyone pays an equal share.',
  path: `sessions/intelligent-fixture/interactive/${id}.json`, sha256: hash, height: 360,
} } };
window.fetch = async input => {
  const url = String(input); fixtureState.fetches.push(url);
  if (!url.startsWith('/api/workspace/file?')) throw new Error('Unexpected fixture network request');
  return new Response(JSON.stringify({ content: fixtureState.corrupt ? payload + ' ' : payload }), { headers: { 'Content-Type': 'application/json' } });
};
window.__vbotSendMessage = async (text, session) => {
  if (fixtureState.busy) return { accepted: false, reason: 'busy' };
  fixtureState.posts.push({ text, session });
  return { accepted: true, messageId: 'fixture-followup', sessionId: session || '' };
};
const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
function Fixture() {
  const [status, setStatus] = useState<ToolStep['status']>('done');
  const [visible, setVisible] = useState(true);
  const [session, setSession] = useState('intelligent-fixture');
  const [owner, setOwner] = useState('fixture-owner');
  Object.assign(window, { intelligentFixture: { ...fixtureState, state: fixtureState, setStatus, setVisible, setSession, setOwner,
    reload: () => client.resetQueries({ queryKey: ['intelligent-ui'] }),
  } });
  const messages = useMemo(() => [{ id: 'answer', role: 'assistant' as const, content: [{ type: 'text' as const, text: 'A calculator you can adjust.' }],
    metadata: { custom: { steps: [{ id: 'calculator-call', label: 'tools__render_interactive_ui', detail: '', status, result: receipt }],
      parts: [{ type: 'steps', ids: ['calculator-call'] }], reactable: false } },
  }], [status]);
  const runtime = useExternalStoreRuntime({ messages, convertMessage: message => message, isRunning: status === 'active', onNew: async () => undefined });
  return <QueryClientProvider client={client}><ToastProvider><ToolOwnerContext value={owner}><InteractiveSessionContext value={session}>
    <AssistantRuntimeProvider runtime={runtime}>
      <main style={{ maxWidth: 820, margin: '40px auto', minHeight: 600 }}>
        {visible ? <Thread compactedFrom={0} composer={null} retryId="" onRetry={() => undefined} onReact={() => undefined} /> : null}
      </main>
    </AssistantRuntimeProvider>
  </InteractiveSessionContext></ToolOwnerContext></ToastProvider></QueryClientProvider>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><Fixture /></StrictMode>);
