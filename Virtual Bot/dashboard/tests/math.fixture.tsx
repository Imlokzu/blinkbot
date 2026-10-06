import { useMemo, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { AssistantRuntimeProvider, MessagePrimitive, ThreadPrimitive, useExternalStoreRuntime } from '@assistant-ui/react';
import { Markdown } from '../src/panels/chat/Markdown';

const example = String.raw`# Mathematics

The fraction $\frac{1}{2}$ equals one half. Price $5 or $10.

$$
\frac{-b\pm\sqrt{b^2-4ac}}{2a}
$$

$$
\int_0^1 x^2\,dx=\frac{1}{3}
$$

\[\begin{pmatrix}1&2\\3&4\end{pmatrix}\]

| Formula | Value |
| --- | --- |
| $\sqrt{9}$ | 3 |

Code stays literal: ${'`'}$x^2$${'`'}. [Source](https://example.org).
`;

function Assistant() {
  return <MessagePrimitive.Root><MessagePrimitive.Parts components={{ Text: Markdown }} /></MessagePrimitive.Root>;
}
function Fixture() {
  const [text, setText] = useState(example);
  Object.assign(window, { mathFixture: { setText, example } });
  const messages = useMemo(() => [{ id: 'math-answer', role: 'assistant' as const,
    content: [{ type: 'text' as const, text }], status: { type: 'complete' as const, reason: 'stop' as const } }], [text]);
  const runtime = useExternalStoreRuntime({ messages, convertMessage: message => ({ ...message, metadata: { custom: {} } }), isRunning: false, onNew: async () => undefined });
  return <AssistantRuntimeProvider runtime={runtime}>
    <main data-source={text} style={{ maxWidth: 680, padding: 24, margin: 'auto', fontFamily: 'sans-serif' }}>
      <ThreadPrimitive.Root><ThreadPrimitive.Messages components={{ AssistantMessage: Assistant }} /></ThreadPrimitive.Root>
    </main>
  </AssistantRuntimeProvider>;
}
createRoot(document.getElementById('root')!).render(<Fixture />);
