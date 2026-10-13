import { StrictMode, useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AssistantRuntimeProvider, useExternalStoreRuntime } from '@assistant-ui/react';
import { Thread } from '../src/panels/chat/Thread';
import { ToastProvider } from '../src/components/ui/Toaster';
import { InteractiveSessionContext } from '../src/panels/chat/InteractiveSessionContext';
import type { ToolStep } from '../src/panels/chat/types';
import '../src/index.css';

const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
const initial = { title: 'Kharkiv', latitude: 49.9935, longitude: 36.2304, zoom: 6 };
const tileState = { failed: false, requests: [] as string[] };
const tile = 'data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><rect width="256" height="256" fill="#dee5d7"/><path d="M0 128H256M128 0V256" stroke="#b8c4ad"/><text x="8" y="20" fill="#67755c" font-size="11">TEST TILE</text></svg>');
const imageSource = Object.getOwnPropertyDescriptor(HTMLImageElement.prototype, 'src')!;
// OSM disallows automated tile harvesting. Exercise real Leaflet with local
// decoded tiles; never send regression pan/zoom traffic to its public service.
Object.defineProperty(HTMLImageElement.prototype, 'src', { configurable: true,
  get() { return imageSource.get!.call(this); },
  set(value: string) {
    if (value.startsWith('https://tile.openstreetmap.org/')) {
      tileState.requests.push(value);
      imageSource.set!.call(this, tileState.failed ? 'data:image/png;base64,invalid' : tile);
    } else imageSource.set!.call(this, value);
  },
});
function Fixture() {
  const [data, setData] = useState(initial), [status, setStatus] = useState<ToolStep['status']>('done');
  const [visible, setVisible] = useState(true);
  Object.assign(window, { mapFixture: { setData, setStatus, setVisible, initial, tileState } });
  const messages = useMemo(() => [{ id: 'map-answer', role: 'assistant' as const, content: [{ type: 'text' as const, text: 'Kharkiv is in northeastern Ukraine.' }],
    metadata: { custom: { steps: [{ id: 'map-call', label: 'tools__show_map', detail: '', status,
      result: { ok: true, ui: { version: 1, kind: 'map', id: 'a'.repeat(32), data } } }],
      parts: [{ type: 'steps', ids: ['map-call'] }], reactable: false } },
  }], [data, status]);
  const runtime = useExternalStoreRuntime({ messages, convertMessage: message => message, isRunning: status === 'active', onNew: async () => undefined });
  return <QueryClientProvider client={client}><ToastProvider><InteractiveSessionContext value="map-fixture"><AssistantRuntimeProvider runtime={runtime}>
    <main style={{ maxWidth: 820, margin: '40px auto', minHeight: 600 }}>{visible ? <Thread compactedFrom={0} composer={null} retryId="" onRetry={() => undefined} onReact={() => undefined} /> : null}</main>
  </AssistantRuntimeProvider></InteractiveSessionContext></ToastProvider></QueryClientProvider>;
}
createRoot(document.getElementById('root')!).render(<StrictMode><Fixture /></StrictMode>);
