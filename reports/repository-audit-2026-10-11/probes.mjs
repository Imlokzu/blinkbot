/** Execute the real timer functions in Node; DOM/audio APIs remain inert. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const directory = path.dirname(fileURLToPath(import.meta.url));
const repository = path.resolve(directory, '../..');
const require = createRequire(path.join(repository, 'Virtual Bot/dashboard/package.json'));
const { parse } = createRequire(require.resolve('vite'))('@babel/parser');
const environment = Object.fromEntries(['PATH', 'TMPDIR', 'LANG', 'LC_ALL'].filter(key => process.env[key])
  .map(key => [key, process.env[key]]));
const backend = JSON.parse(execFileSync(path.join(repository, 'Virtual Bot/.venv/bin/python'),
  [path.join(directory, 'probes.py')], {encoding: 'utf8', env: environment, timeout: 10000}));
const source = fs.readFileSync(path.join(repository, 'Virtual Bot/static/screen/screen.js'), 'utf8');
const ast = parse(source, {sourceType: 'module'});
const wanted = new Set(['timerLeft', 'setTimers', 'ringTimerDone', 'stopRing', 'tickTimers']);
const functions = ast.program.body.filter(node => node.type === 'FunctionDeclaration' && wanted.has(node.id.name))
  .map(node => source.slice(node.start, node.end));
assert.equal(functions.length, wanted.size);
const clock = {now: 1000};
class FixtureDate extends Date { static now() { return clock.now * 1000; } }
const observations = {rings: 0};
const context = vm.createContext({Date: FixtureDate, clock,
  renderTimers() {}, noticesChanged() {}, wake() {}, setEmotion() {}, showCaption() {}, speechSay() {},
  alarmBeep() { observations.rings += 1; }, t: key => key,
  setInterval: () => 1, clearInterval() {},
});
vm.runInContext(`let timers = [], timerSkew = 0; const timerRung = new Set();
let ringUntil = 0, ringTimer = 0; const RING_MS = 30000, RING_EVERY_MS = 2500;
${functions.join('\n')}`, context);

function receive(timer, now) {
  clock.now = now;
  context.incoming = timer;
  vm.runInContext('setTimers([incoming], clock.now);', context);
}
receive(backend.timer_restart.initial, 1000);
clock.now = 1002;
vm.runInContext('tickTimers();', context);
assert.equal(observations.rings, 1);
const firstRingUntil = vm.runInContext('ringUntil', context);
assert(firstRingUntil > clock.now * 1000);
vm.runInContext('stopRing();', context);
assert.equal(vm.runInContext('ringUntil', context), 0);
receive(backend.timer_restart.restarted, 1002);
clock.now = 1062;
assert(clock.now * 1000 >= firstRingUntil);
vm.runInContext('tickTimers();', context);
assert.equal(observations.rings, 1);
const fired = vm.runInContext('Array.from(timerRung)', context);
assert(fired.includes(backend.timer_restart.restarted.id));
console.log(JSON.stringify({first_deadline_rings: 1, restarted_deadline_new_rings: 0,
  original_ring_silenced: true, restarted_deadline_after_original_ring_window: true,
  same_timer_id: backend.timer_restart.initial.id === backend.timer_restart.restarted.id,
  running_restarted_timer_still_marked_rung: true}, null, 2));
