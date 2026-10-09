/** Real React components/hooks in a synthetic browser; no bot or provider API. */
import assert from 'node:assert/strict';
import { execFile, execFileSync } from 'node:child_process';
import fs from 'node:fs/promises';
import net from 'node:net';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const repository = path.resolve(directory, '../..');
const dashboard = path.join(repository, 'Virtual Bot/dashboard');
const require = createRequire(path.join(dashboard, 'package.json'));
const { createServer, transformWithOxc } = await import(require.resolve('vite'));
const { parse } = createRequire(require.resolve('vite'))('@babel/parser');
const fixture = await fs.mkdtemp(path.join(os.tmpdir(), 'blink-ui-audit-20261009-'));
const environment = Object.fromEntries(['PATH', 'TMPDIR', 'LANG', 'LC_ALL'].filter(key => process.env[key])
  .map(key => [key, process.env[key]]));
const session = `blink-ui-audit-${process.pid}-${path.basename(fixture).slice(-6)}`;
const browserConfig = path.join(fixture, 'agent-browser.json');
const browserFlags = ['--config', browserConfig, '--namespace', session, '--session', session,
  '--allowed-domains', '127.0.0.1', '--json'];
const port = 18119;
const origin = `http://127.0.0.1:${port}`;
const run = (file, args, options) => new Promise((resolve, reject) => {
  const {input, ...settings} = options;
  const child = execFile(file, args, settings, (error, stdout, stderr) => {
    if (error) reject(error);
    else resolve({stdout, stderr});
  });
  child.stdin.end(input ?? '');
});
let server;
let browserStarted = false;
const browser = async (...args) => (await run('agent-browser', [...browserFlags, ...args], {
  encoding: 'utf8', env: environment, cwd: fixture, timeout: 40000,
})).stdout;
const evaluate = async (code) => JSON.parse((await run('agent-browser', [...browserFlags, 'eval', '--stdin'], {
  input: code, encoding: 'utf8', env: environment, cwd: fixture, timeout: 40000,
})).stdout).data.result;

async function compile(source, filename, replacements = {}) {
  const original = await fs.readFile(path.join(dashboard, source), 'utf8');
  const output = (await transformWithOxc(original, source, {jsx: {runtime: 'automatic'}})).code;
  const rewritten = output.replace(/from\s+(['"])([^'"]+)\1/g, (match, quote, specifier) =>
    replacements[specifier] ? `from ${quote}${replacements[specifier]}${quote}` : match);
  await fs.writeFile(path.join(fixture, filename), rewritten);
  return original;
}

try {
  if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true'], {env: environment});
  // Do not let inherited credentials/configuration influence Vite or child tools.
  for (const key of Object.keys(process.env)) delete process.env[key];
  Object.assign(process.env, environment);
  const available = await new Promise(resolve => {
    const socket = net.connect({ host: '127.0.0.1', port });
    socket.once('connect', () => { socket.destroy(); resolve(false); });
    socket.once('error', () => resolve(true));
  });
  assert(available, 'Synthetic fixture port is already occupied');
  const backend = JSON.parse(execFileSync(path.join(repository, 'Virtual Bot/.venv/bin/python'),
    [path.join(directory, 'probes.py')], {encoding: 'utf8', env: environment, timeout: 10000}));
  await fs.writeFile(browserConfig, '{}');
  await fs.symlink(path.join(dashboard, 'node_modules'), path.join(fixture, 'node_modules'));
  await fs.writeFile(path.join(fixture, 'package.json'), '{"type":"module"}');
  await compile('src/panels/chat/interactiveToolData.ts', 'data.js');
  await compile('src/panels/chat/InteractiveToolCard.tsx', 'card.js', {
    './interactiveToolData': './data.js', '../../vendor/solar-icons/compat.ts': './mocks.js', '@/lib/i18n': './i18n.js',
  });
  await compile('src/lib/i18n.ts', 'i18n.js', {'../locales/product': './product.js'});
  await compile('src/locales/product.ts', 'product.js');
  await compile('src/panels/chat/activity.ts', 'activity.js');
  await compile('src/panels/chat/replyParts.ts', 'parts.js');
  await compile('src/panels/chat/chatNavigation.ts', 'navigation.js');
  await compile('src/panels/chat/tokens.ts', 'tokens.js', {'@/locales/chat': './mocks.js'});
  await compile('src/panels/chat/useChatRuntime.ts', 'runtime.js', {
    '@assistant-ui/react': './mocks.js', '@tanstack/react-query': './mocks.js', '@/lib/api': './mocks.js',
    '@/lib/chatStream': './mocks.js', '@/lib/i18n': './i18n.js', '@/locales/chat': './mocks.js',
    '@/locales/attachments': './mocks.js', '@/components/ui/Toaster': './mocks.js',
    './activity': './activity.js', './replyParts': './parts.js', './Bubbles': './mocks.js',
    './tokens': './tokens.js', './chatNavigation': './navigation.js',
  });

  // Extract the actual effect with a TypeScript AST, rather than reimplementing
  // its behavior. The rest of ChatPanel's layout is outside this fixture.
  const panel = await fs.readFile(path.join(dashboard, 'src/panels/chat/ChatPanel.tsx'), 'utf8');
  const ast = parse(panel, {sourceType: 'module', plugins: ['typescript', 'jsx']});
  let bridge = '';
  function visit(node) {
    if (!node || typeof node !== 'object') return;
    if (node.type === 'CallExpression' && node.callee?.name === 'useEffect') {
      const source = panel.slice(node.start, node.end);
      if (source.includes('window.__vbotSendMessage =')) bridge = source;
    }
    for (const value of Object.values(node)) {
      if (Array.isArray(value)) value.forEach(visit);
      else if (value && typeof value === 'object') visit(value);
    }
  }
  visit(ast);
  assert(bridge && bridge.includes('[chat.send]'), 'Expected actual message bridge effect');
  const bridgeCode = (await transformWithOxc(bridge, 'bridge.ts')).code;
  await fs.writeFile(path.join(fixture, 'mocks.js'), `
import {useMemo} from 'react';
export const calls = [];
export const mutations = [];
const client = {invalidateQueries: async () => {}};
const toast = {error: (...args) => mutations.push({toast: args})};
export const Check = () => null;
export const Send = () => null;
export const t = key => key;
export const localizeUploadError = () => null;
export const cleanEmotionTag = text => text;
export const typingLeaveMs = () => 0;
export const useToast = () => toast;
export const useQueryClient = () => client;
export const useQuery = () => ({data: [], isPending: false});
export const useExternalStoreRuntime = configuration => configuration;
export const get = async url => ({id: 'fixture-session', messages: []});
export const post = async (...args) => { mutations.push(args); return {}; };
export const streamChat = (payload, handlers, signal) => new Promise(resolve => {
  const call = {payload, handlers, resolve, signal};
  calls.push(call);
  signal.addEventListener('abort', resolve, {once: true});
});
`);
  const entry = `
import React, {useEffect, useState} from 'react';
import {createRoot} from 'react-dom/client';
import {InteractiveToolCard} from './card.js';
import {interactiveToolData} from './data.js';
import {useChatRuntime} from './runtime.js';
import {calls, mutations} from './mocks.js';
const backend = ${JSON.stringify(backend)};
const question = {id: 'fixture-choice', label: 'ask_question', status: 'done', detail: '',
  input: {question: 'Which fixture format?', options: ['PDF', 'Markdown']}};
const todo = {id: 'fixture-todo', label: 'todo_list', status: 'done', detail: '', input: {items: ['Inert task']}};
function App() {
  const chat = useChatRuntime();
  const [cards, setCards] = useState(true);
  ${bridgeCode}
  useEffect(() => { window.audit = {chat, calls, mutations, backend, setCards,
    data: key => interactiveToolData(backend[key].step)}; });
  return React.createElement('main', null,
    React.createElement('div', {id: 'free-text'}, React.createElement(InteractiveToolCard, {step: backend.free_text_question.step})),
    cards && React.createElement('div', {id: 'fixture-cards'},
      React.createElement(InteractiveToolCard, {step: chat.steps[0] ?? question}), React.createElement(InteractiveToolCard, {step: todo})));
}
createRoot(document.getElementById('root')).render(React.createElement(App));
`;
  await fs.writeFile(path.join(fixture, 'entry.js'), entry);
  await fs.writeFile(path.join(fixture, 'index.html'), '<!doctype html><html lang="en"><head><meta charset="utf-8"></head><body><div id="root"></div><script type="module" src="/entry.js"></script></body></html>');
  server = await createServer({root: fixture, envDir: fixture, configFile: false,
    cacheDir: path.join(fixture, 'cache'), clearScreen: false, logLevel: 'error',
    server: {host: '127.0.0.1', port, strictPort: true}, publicDir: false});
  await server.listen();
  browserStarted = true;
  await browser('open', origin);
  await browser('wait', '--fn', 'Boolean(window.audit)');
  await evaluate('(async () => { await window.audit.chat.openSession("fixture-session"); return true; })()');
  await browser('wait', '--fn', 'window.audit.chat.sessionId === "fixture-session"');

  const freeText = await evaluate('({backendOk: window.audit.backend.free_text_question.tool_ok, cardCount: document.querySelectorAll("#free-text section").length, inputCount: document.querySelectorAll("#free-text input").length})');
  assert.deepEqual(freeText, {backendOk: true, cardCount: 0, inputCount: 0});
  await evaluate('(() => { void window.audit.chat.send("Ask the synthetic question"); return true; })()');
  await browser('wait', '--fn', 'window.audit.chat.running && window.audit.calls.length === 1');
  await evaluate(`(() => { window.audit.calls[0].handlers.onTool({type: 'tool_start',
    step: {id: 'fixture-choice', label: 'ask_question', status: 'active', detail: '',
      input: {question: 'Which fixture format?', options: ['PDF', 'Markdown']}}}); return true; })()`);
  await browser('click', '#fixture-cards [data-interactive-tool="question"] button');
  await browser('wait', '#fixture-cards [role="status"]');
  const delivery = await evaluate('({running: window.audit.chat.running, requestCount: window.audit.calls.length, answerSentLabel: document.querySelector("#fixture-cards [role=status]").textContent, sourceAborted: window.audit.calls[0].signal.aborted})');
  assert.deepEqual(delivery, {running: true, requestCount: 1, answerSentLabel: 'Answer sent', sourceAborted: false});
  await browser('click', '#fixture-cards input[type="checkbox"]');
  assert.equal(await evaluate('document.querySelector("#fixture-cards input[type=checkbox]").checked'), true);
  await evaluate('(() => { window.audit.setCards(false); return true; })()');
  await browser('wait', '--fn', '!document.querySelector("#fixture-cards")');
  await evaluate('(() => { window.audit.setCards(true); return true; })()');
  await browser('wait', '#fixture-cards');
  const remount = await evaluate('({todoChecked: document.querySelector("#fixture-cards input[type=checkbox]").checked, sentStatusCount: document.querySelectorAll("#fixture-cards [role=status]").length, answerDisabled: document.querySelector("#fixture-cards [data-interactive-tool=question] button").disabled, mutationCount: window.audit.mutations.length})');
  assert.deepEqual(remount, {todoChecked: false, sentStatusCount: 0, answerDisabled: false, mutationCount: 0});
  const normalization = await evaluate('({publishedChoiceLength: window.audit.backend.choice_normalization.published.data.options[0].label.length, inlineChoiceLength: window.audit.data("choice_normalization").options[0].label.length, publishedTodoLength: window.audit.backend.todo_normalization.published.data.items[0].text.length, inlineTodoLength: window.audit.data("todo_normalization").items[0].text.length})');
  assert.deepEqual(normalization, {publishedChoiceLength: 80, inlineChoiceLength: 100, publishedTodoLength: 180, inlineTodoLength: 120});
  await evaluate('(async () => { await window.audit.chat.cancel(); return true; })()');
  const observations = {freeText, delivery, remount, normalization};
  console.log(JSON.stringify(observations, null, 2));
} finally {
  try {
    if (browserStarted) await browser('close');
  } finally {
    try {
      if (server) await server.close();
    } finally {
      await fs.rm(fixture, {recursive: true, force: true});
    }
  }
}
