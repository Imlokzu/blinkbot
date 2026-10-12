/** Actual screen functions with synthetic recognition, clock, media and HTTP. */
import assert from 'node:assert/strict';
import {execFileSync} from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import {createRequire} from 'node:module';
import {fileURLToPath, pathToFileURL} from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const repository = path.resolve(directory, '../..');
const screen = path.join(repository, 'Virtual Bot/static/screen');
const environment = Object.fromEntries(['PATH', 'TMPDIR', 'LANG', 'LC_ALL'].filter(key => process.env[key])
  .map(key => [key, process.env[key]]));
if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true'], {env: environment});
for (const key of Object.keys(process.env)) delete process.env[key];
Object.assign(process.env, environment);
const require = createRequire(path.join(repository, 'Virtual Bot/dashboard/package.json'));
const {parse} = createRequire(require.resolve('vite'))('@babel/parser');
const {VoiceTurnDetector} = await import(pathToFileURL(path.join(screen, 'voice-turn.js')));
const {parseWake, findWake, isStopCommand} = await import(pathToFileURL(path.join(screen, 'wake.js')));
const source = fs.readFileSync(path.join(screen, 'screen.js'), 'utf8');
const ast = parse(source, {sourceType: 'module'});
const functions = new Map(ast.program.body.filter(node => node.type === 'FunctionDeclaration')
  .map(node => [node.id.name, source.slice(node.start, node.end)]));

function deferred() {
  let resolve;
  const promise = new Promise(yes => {resolve = yes;});
  return {promise, resolve};
}
async function settle() { for (let index = 0; index < 40; index++) await Promise.resolve(); }

function environmentFor(mode = 'open') {
  const ctx = vm.createContext({Blob, FormData, AbortController, VoiceTurnDetector, parseWake, findWake, isStopCommand,
    clock: 0, sent: [], requests: [], recorders: [], captions: [], rendered: [], titles: [], timers: new Map(),
    voiceMode: mode, listening: true, wakeWord: 'claude', wakeArmed: false, wakeCuePendingFinal: false,
    wakeTimer: 0, followUpPending: false, WAKE_ARM_MS: 8000, FOLLOW_UP_MS: 8000,
    activeChat: null, botSpeaking: false, replyInterrupted: false, sessionId: 'fixture-old', SESSION_KEY: 'fixture-session',
    micEpoch: 1, micStream: {getTracks: () => []}, meter: null, mediaRec: null,
    asrAvailable: true, asrController: null, partialController: null, partialBusy: false, partialsOn: false,
    spoke: false, recStartAt: 0, recTimer: 0, PARTIAL_MS: 5000, REC_MAX_MS: 15000,
    voiceDetector: new VoiceTurnDetector(), voiceAudio: {speechEchoes: []},
    crab: {setAudioLevel() {}, setEmotion() {}}, setVoiceMode() {}, clearBusy() {}, wake() {},
    acknowledgeWake() {}, handleWakeInterim() {}, finishPhrase() {}, writePref() {},
    goTile() {}, chatTile: () => 0, sessionsPanel: {classList: {add() {}}},
  });
  ctx.Date = {now: () => ctx.clock};
  ctx.Math = {random: () => 0.5};
  let nextTimer = 0;
  ctx.setTimeout = callback => {ctx.timers.set(++nextTimer, callback); return nextTimer;};
  ctx.clearTimeout = token => ctx.timers.delete(token);
  ctx.t = key => key;
  ctx.showLive = () => {};
  ctx.showCaption = (...args) => ctx.captions.push(args);
  ctx.setListening = on => {ctx.listening = on;};
  ctx.setSessionTitle = title => ctx.titles.push(title);
  ctx.renderHistory = messages => ctx.rendered.push(messages);
  ctx.speechReset = () => {ctx.botSpeaking = false;};
  ctx.sendChat = text => ctx.sent.push({text, sessionId: ctx.sessionId});
  ctx.fetch = (url, options) => {
    const response = deferred();
    ctx.requests.push({url, options, response});
    return response.promise;
  };
  ctx.MediaRecorder = class {
    constructor() {this.state = 'inactive'; this.mimeType = 'audio/webm'; ctx.recorders.push(this);}
    start() {this.state = 'recording';}
    stop() {
      this.state = 'inactive';
      this.stoppedAt = ctx.clock;
      this.ondataavailable?.({data: new Blob(['inert fixture audio'], {type: this.mimeType})});
      this.onstop?.();
    }
  };
  ctx.window = {MediaRecorder: ctx.MediaRecorder};
  const names = ['onMicLevel', 'bargeIn', 'armWake', 'disarmWake', 'isPlaybackEcho', 'handleFinalText',
    'startRecorder', 'sendPartial', 'sendToAsr', 'startNewChat', 'openSession'];
  vm.runInContext(names.map(name => {
    assert(functions.has(name), `Missing production function ${name}`);
    return functions.get(name);
  }).join('\n'), ctx);
  return ctx;
}

async function wakeIntent(busy) {
  const ctx = environmentFor('wake');
  const original = new AbortController();
  ctx.activeChat = busy ? original : null;
  ctx.botSpeaking = busy;
  ctx.startRecorder(true);
  for (const stamp of [0, 100, 200]) {ctx.clock = stamp; ctx.onMicLevel(0.3);}
  const capturedArmed = ctx.mediaRec.wakeArmed;
  ctx.mediaRec.stop();
  assert.equal(ctx.requests.length, 1);
  ctx.requests[0].response.resolve({ok: true, json: async () => ({text: 'This sentence is for someone else'})});
  await settle();
  return {capturedArmed, originalAborted: original.signal.aborted, sent: Array.from(ctx.sent, value => value.text)};
}

async function recognitionOverlap() {
  const ctx = environmentFor();
  ctx.startRecorder(true);
  ctx.spoke = true;
  ctx.mediaRec.stop();
  const first = ctx.requests[0];
  ctx.spoke = true;
  ctx.mediaRec.stop();
  const second = ctx.requests[1];
  assert(first && second && first.options.signal.aborted);
  first.response.resolve({ok: true, json: async () => ({text: 'First separate sentence'})});
  second.response.resolve({ok: true, json: async () => ({text: 'Second separate sentence'})});
  await settle();
  const output = {uploads: ctx.requests.length, firstAborted: first.options.signal.aborted,
    submitted: Array.from(ctx.sent, value => value.text)};
  assert.deepEqual(output, {uploads: 2, firstAborted: true, submitted: ['Second separate sentence']});
  return output;
}

async function capSplitRecognition() {
  const ctx = environmentFor();
  ctx.startRecorder(true);
  // No silence or explicit stop: the real level handler rolls over at the cap.
  for (let stamp = 0; stamp <= 30400; stamp += 200) {
    ctx.clock = stamp;
    ctx.onMicLevel(0.3);
  }
  const [first, second] = ctx.requests;
  assert.equal(ctx.requests.length, 2);
  assert(first.options.signal.aborted);
  first.response.resolve({ok: true, json: async () => ({text: 'Set the timer for'})});
  second.response.resolve({ok: true, json: async () => ({text: 'twelve minutes'})});
  await settle();
  const output = {uploads: ctx.requests.length, firstAborted: first.options.signal.aborted,
    clipEndMs: Array.from(ctx.recorders.slice(0, 2), recorder => recorder.stoppedAt),
    submitted: Array.from(ctx.sent, value => value.text)};
  assert.deepEqual(output, {uploads: 2, firstAborted: true, clipEndMs: [15200, 30400],
    submitted: ['twelve minutes']});
  return output;
}

async function navigationRecognition() {
  const ctx = environmentFor();
  const sourceSession = ctx.sessionId;
  ctx.startRecorder(true);
  ctx.spoke = true;
  ctx.mediaRec.stop();
  const request = ctx.requests[0];
  ctx.startNewChat();
  const selectedSession = ctx.sessionId;
  request.response.resolve({ok: true, json: async () => ({text: 'Speech captured for the old conversation'})});
  await settle();
  const output = {sourceSession, selectedSession, recognitionAborted: request.options.signal.aborted,
    submittedSession: ctx.sent[0]?.sessionId, submissions: ctx.sent.length};
  assert.deepEqual(output, {sourceSession: 'fixture-old', selectedSession: 'screen-8', recognitionAborted: false,
    submittedSession: 'screen-8', submissions: 1});
  return output;
}

async function historyRace() {
  const ctx = environmentFor();
  const first = ctx.openSession('fixture-A', 'A');
  const second = ctx.openSession('fixture-B', 'B');
  ctx.requests[1].response.resolve({json: async () => ({title: 'B', messages: [{content: 'B history'}]})});
  await second;
  ctx.requests[0].response.resolve({json: async () => ({title: 'A', messages: [{content: 'A history'}]})});
  await first;
  const output = {selectedSession: ctx.sessionId, finalTitle: ctx.titles.at(-1),
    renderedHistory: ctx.rendered.at(-1)[0].content};
  assert.deepEqual(output, {selectedSession: 'fixture-B', finalTitle: 'A', renderedHistory: 'A history'});
  return output;
}

const idle = await wakeIntent(false);
const busy = await wakeIntent(true);
assert.deepEqual(idle, {capturedArmed: false, originalAborted: false, sent: []});
assert.deepEqual(busy, {capturedArmed: true, originalAborted: true, sent: ['This sentence is for someone else']});
console.log(JSON.stringify({wakeIntent: {idle, busy}, recognitionOverlap: await recognitionOverlap(),
  capSplitRecognition: await capSplitRecognition(), navigationRecognition: await navigationRecognition(),
  historyRace: await historyRace()}, null, 2));
