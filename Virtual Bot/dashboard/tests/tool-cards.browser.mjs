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
const repository = path.resolve(directory, '../../..');
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
const port = 18139;
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
    [path.join(repository, 'reports/repository-audit-2026-10-09/probes.py')], {encoding: 'utf8', env: environment, timeout: 10000}));
  await fs.writeFile(browserConfig, '{}');
  await fs.symlink(path.join(dashboard, 'node_modules'), path.join(fixture, 'node_modules'));
  await fs.writeFile(path.join(fixture, 'package.json'), '{"type":"module"}');
  await compile('src/panels/chat/interactiveToolData.ts', 'data.js');
  await compile('src/panels/chat/InteractiveToolCard.tsx', 'card.js', {
    './interactiveToolData': './data.js', './chatSubmission': './submission.js', './useToolInteraction': './interaction.js', './InteractiveSessionContext': './context.js', '../../vendor/solar-icons/compat.ts': './mocks.js', '@/lib/i18n': './i18n.js',
  });
  await compile('src/panels/chat/chatSubmission.ts', 'submission.js');
  await compile('src/panels/chat/InteractiveSessionContext.ts', 'context.js');
  await compile('src/panels/chat/useToolInteraction.ts', 'interaction.js', {'@/lib/api': './mocks.js', './InteractiveSessionContext': './context.js'});
  await compile('src/components/shell/BotUiOverlay.tsx', 'overlay.js', {'@/panels/chat/chatSubmission': './submission.js', '../../vendor/solar-icons/compat.ts': './mocks.js', '@/hooks/useBotEvents': './mocks.js', '@/lib/i18n': './i18n.js', '@/app/useRoute': './mocks.js'});
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
  assert(bridge && bridge.includes('chat.send'), 'Expected actual message bridge effect');
  const bridgeCode = (await transformWithOxc(bridge, 'bridge.ts')).code;
  await fs.writeFile(path.join(fixture, 'mocks.js'), `
import {useEffect,useRef} from 'react';
export {useQuery,useQueryClient} from '@tanstack/react-query';
export const useRoute = () => ['overview'];
export const useBotEvents = callback => {const ref=useRef(callback);ref.current=callback;useEffect(()=>{window.fireUi=event=>ref.current(event);return ()=>{delete window.fireUi;};},[]);};
export const calls = [], mutations = [], states = new Map();
export let failSave = false;
export const setFailSave = value => {failSave = value;};
const empty = () => ({revision:0, done:{}, answer:null});
const state = url => {if(!states.has(url)) states.set(url,empty()); return structuredClone(states.get(url));};
const toast = {error: (...args) => mutations.push({toast:args})};
export const Check = () => null, Send = () => null, X = () => null;
export const t = key => key, localizeUploadError = () => null, cleanEmotionTag = text => text, typingLeaveMs = () => 0, useToast = () => toast;
export const useExternalStoreRuntime = configuration => configuration;
export const get = async url => url.includes('/interactions/') ? state(url) : url === '/api/sessions' ? {sessions:[]} : {id:'fixture-session',messages:[]};
export const post = async (url,action) => {
  mutations.push({url,action});
  if(failSave) throw Error('Synthetic failed save');
  const current = state(url);
  if(action.action === 'answer') return current;
  if(current.revision !== action.expected_revision) throw Error('Synthetic revision conflict');
  current.done[action.item_id]=action.done; current.revision++;
  states.set(url,current); return structuredClone(current);
};
export const streamChat = (payload,handlers,signal) => new Promise(resolve => {
  calls.push({payload,handlers,signal,resolve}); signal.addEventListener('abort',resolve,{once:true});
});
export const finish = (index,success=true,persist=true) => {
  const call=calls[index], id='message-'+index;
  if(success) {
    if(persist && call.payload.tool_answer) {
      const answer=call.payload.tool_answer;
      const url='/api/sessions/fixture-session/interactions/'+encodeURIComponent(answer.call_id);
      const current=state(url);
      current.answer={message_id:id,option_id:answer.option_id,value:answer.value}; states.set(url,current);
    }
    call.handlers.onDone({reply:'Fixture reply',emotion:'idle',session_id:'fixture-session',mode:'demo',model:'',tool_results:[],steps:[],user_message_id:persist?id:'',assistant_message_id:'reply-'+index});
  } else call.handlers.onError('Synthetic rejected request');
  call.resolve();
};
`);
  const entry = `
import React,{useEffect,useState} from 'react';
import {createRoot} from 'react-dom/client';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {InteractiveToolCard} from './card.js';
import {BotUiOverlay} from './overlay.js';
import {InteractiveSessionContext} from './context.js';
import {interactiveToolData} from './data.js';
import {useChatRuntime} from './runtime.js';
import {calls,mutations,states,finish,setFailSave} from './mocks.js';
const backend=${JSON.stringify(backend)};
const question={id:'fixture-choice',label:'ask_question',status:'done',detail:'',input:{question:'Which fixture format?',options:['PDF','Markdown']}};
const todo={id:'fixture-todo',label:'todo_list',status:'done',detail:'',input:{items:['Inert task']}};
function App(){
  const chat=useChatRuntime();
  const [cards,setCards]=useState(true);
  ${bridgeCode}
  useEffect(()=>{window.audit={chat,calls,mutations,states,finish,setFailSave,setCards,backend,data:key=>interactiveToolData(backend[key].step)};});
  return React.createElement(InteractiveSessionContext.Provider,{value:chat.sessionId},
    React.createElement('main',null,React.createElement(BotUiOverlay),
      React.createElement('div',{id:'free-text'},React.createElement(InteractiveToolCard,{step:backend.free_text_question.step})),
      React.createElement('div',{id:'preview'},React.createElement(InteractiveToolCard,{step:{...todo,id:'preview-call',status:'active'}})),
      cards && React.createElement('div',{id:'fixture-cards'},React.createElement(InteractiveToolCard,{step:question}),React.createElement(InteractiveToolCard,{step:todo}))));
}
const client=new QueryClient({defaultOptions:{queries:{retry:false}}});
createRoot(document.getElementById('root')).render(React.createElement(QueryClientProvider,{client},React.createElement(App)));
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


  await browser('wait','--fn','document.querySelector("#fixture-cards button") && !document.querySelector("#fixture-cards button").disabled');
  assert.equal(await evaluate('document.querySelectorAll("#free-text input").length'),1);
  assert.equal(await evaluate('document.querySelector("#preview input").disabled'),true);
  await evaluate('(() => {void window.audit.chat.send("Start existing-session work");return true;})()');
  await browser('wait','--fn','window.audit.chat.running && window.audit.calls.length===1');
  await browser('click','#fixture-cards [data-interactive-tool="question"] button');
  await browser('wait','--text','Wait for the current reply to finish');
  assert.equal(await evaluate('window.audit.calls.length'),1);
  assert.equal(await evaluate('window.audit.calls[0].signal.aborted'),false);
  assert.equal(await evaluate('document.querySelector("#fixture-cards button").disabled'),false);
  assert.equal(await evaluate('document.querySelector("#fixture-cards").textContent.includes("Answer sent")'),false);
  await evaluate('(() => {window.audit.finish(0);return true;})()');
  await browser('wait','--fn','!window.audit.chat.running');
  await browser('click','#fixture-cards input[type="checkbox"]');
  await browser('wait','--fn','document.querySelector("#fixture-cards input[type=checkbox]").checked');
  await evaluate('(() => {window.audit.setCards(false);return true;})()');
  await browser('wait','--fn','!document.querySelector("#fixture-cards")');
  await evaluate('(() => {window.audit.setCards(true);return true;})()');
  await browser('wait','--fn','document.querySelector("#fixture-cards input[type=checkbox]")?.checked');
  await evaluate('(() => {window.audit.setFailSave(true);return true;})()');
  await browser('click','#fixture-cards input[type="checkbox"]');
  await browser('wait','--text','Changes were not saved');
  assert.equal(await evaluate('document.querySelector("#fixture-cards input[type=checkbox]").checked'),true);
  await evaluate('(() => {window.audit.setFailSave(false);return true;})()');
  await browser('click','#fixture-cards [data-interactive-tool="question"] button');
  await browser('wait','--fn','window.audit.calls.length===2');
  assert.equal(await evaluate('document.querySelector("#fixture-cards").textContent.includes("Answer sent")'),false);
  await evaluate('(() => {window.audit.finish(1,false);return true;})()');
  await browser('wait','--text','Answer was not sent');
  assert.equal(await evaluate('document.querySelector("#fixture-cards button").disabled'),false);
  await evaluate('(() => {const b=document.querySelector("#fixture-cards [data-interactive-tool=question] button");b.click();b.click();return true;})()');
  await browser('wait','--fn','window.audit.calls.length===3');
  assert.deepEqual(await evaluate('window.audit.calls[2].payload.tool_answer'),{owner_id:'',call_id:'fixture-choice',option_id:'option-0',value:'PDF',expected_revision:0});
  await evaluate('(() => {window.audit.finish(2);return true;})()');
  await browser('wait','--text','Answer sent');
  await evaluate('(() => {window.audit.setCards(false);return true;})()');
  await browser('wait','--fn','!document.querySelector("#fixture-cards")');
  await evaluate('(() => {window.audit.setCards(true);return true;})()');
  await browser('wait','--fn','document.querySelector("#fixture-cards [data-interactive-tool=question] button")?.disabled');
  assert.equal(await evaluate('document.querySelector("#fixture-cards").textContent.includes("Answer sent")'),true);
  const wrongSession=await evaluate('(async()=>await window.__vbotSendMessage("Wrong destination","another-session"))()');
  assert.deepEqual(wrongSession,{accepted:false,reason:'stale'});
  assert.equal(await evaluate('window.audit.calls.length'),3);
  await evaluate('(() => {window.fireUi({type:"ui",kind:"question",data:{id:"old",question:"Old question",options:["Old"]}});return true;})()');
  await browser('wait','--text','Old question');
  await evaluate('(() => {Array.from(document.querySelectorAll(".bot-ui-overlay button")).find(b=>b.textContent.trim()==="Old").click();return true;})()');
  await browser('wait','--fn','window.audit.calls.length===4');
  await evaluate('(() => {window.fireUi({type:"ui",kind:"question",data:{id:"new",question:"Replacement question",options:["New"]}});window.audit.finish(3);return true;})()');
  await browser('wait','--fn','!window.audit.chat.running');
  assert.equal(await evaluate('document.querySelector(".bot-ui-overlay").textContent.includes("Replacement question")'),true);
  console.log('Browser regressions passed: free text, inert preview, busy/rejected/duplicate/stale answer, confirmed remount, saved and failed checklist writes');

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
