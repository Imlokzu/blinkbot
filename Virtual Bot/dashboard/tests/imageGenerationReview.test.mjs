import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import vm from 'node:vm';
import { moduleRunnerTransform, transformWithOxc } from 'vite';
import { generationView, imageOnlyDelivery } from '../src/panels/chat/imageGenerationState.ts';

const step = { id: 'paint', label: 'image_generate', detail: '', status: 'active', input: { prompt: 'A blue crab' } };
const output = { images: [{ url: '/uploads/local-review.png' }] };
const element = (type, props, key) => ({ type, props, key });

/** Execute the actual components with controlled hooks and browser callbacks. */
async function component(name, modules, environment = {}) {
  const file = new URL(`../src/panels/chat/${name}.tsx`, import.meta.url);
  const source = readFileSync(file, 'utf8');
  const transformed = await transformWithOxc(source, file.pathname, { jsx: { runtime: 'automatic' } });
  const compiled = await moduleRunnerTransform(transformed.code, null, file.pathname, source);
  const exports = {};
  await vm.runInNewContext(`(async () => { ${compiled.code}\n })()`, {
    __vite_ssr_exportName__: (name, get) => Object.defineProperty(exports, name, { get }),
    async __vite_ssr_import__(id) {
      if (id === 'react/jsx-runtime') return { jsx: element, jsxs: element };
      if (Object.hasOwn(modules, id)) return modules[id];
      throw new Error(`Unexpected component dependency: ${id}`);
    },
    ...environment,
  }, { filename: file.pathname });
  return exports[name];
}

function find(node, predicate) {
  if (!node || typeof node !== 'object') return undefined;
  if (predicate(node)) return node;
  for (const child of [node.props?.children].flat(Infinity)) {
    const match = find(child, predicate);
    if (match) return match;
  }
}

async function cardHarness() {
  let resource = { src: '', failed: false };
  let currentStep = step;
  let stateIndex = 0;
  let effectIndex = 0;
  let reloads = 0;
  let gallery;
  let opened;
  const state = [];
  const effects = [];
  const listeners = new Set();
  const query = {
    matches: false,
    addEventListener: (type, callback) => { assert.equal(type, 'change'); listeners.add(callback); },
    removeEventListener: (type, callback) => { assert.equal(type, 'change'); listeners.delete(callback); },
  };
  const Card = await component('ImageGenerationCard', {
    react: {
      useMemo: (factory) => factory(),
      useState(initial) {
        const index = stateIndex++;
        if (!(index in state)) state[index] = typeof initial === 'function' ? initial() : initial;
        return [state[index], (value) => { state[index] = typeof value === 'function' ? value(state[index]) : value; }];
      },
      useEffect(callback, dependencies) {
        const index = effectIndex++;
        if (effects[index]?.dependencies.every((value, at) => Object.is(value, dependencies[at]))) return;
        effects[index]?.cleanup?.();
        effects[index] = { dependencies, cleanup: callback() };
      },
    },
    '../../vendor/solar-icons/compat.ts': { Check: 'Check', CircleAlert: 'CircleAlert', ImageOff: 'ImageOff', RotateCcw: 'RotateCcw' },
    // The installed Motion hook snapshots its initial value rather than subscribing.
    'motion/react': { AnimatePresence: 'AnimatePresence', motion: { img: 'motion.img', div: 'motion.div', span: 'motion.span' }, useReducedMotion: () => false },
    '@/components/ui/Button': { Button: 'Button' },
    '@/locales/imageGeneration': { t: (key) => key },
    '@/locales/chat': { t: (key) => key },
    './imageGenerationState': { generationView },
    './ImageGenerationDither': { ImageGenerationDither: 'ImageGenerationDither' },
    './usePrivateImage': { usePrivateImageResource: () => ({ ...resource, reload: () => { reloads += 1; } }) },
    './Gallery': { useGalleryGroup: (images, enabled) => { gallery = { images, enabled }; return { node: {}, open: (index) => { opened = index; } }; } },
    './image-generation.css': {},
  }, { window: { matchMedia: () => query } });
  return {
    render() { stateIndex = 0; effectIndex = 0; return Card({ step: currentStep }); },
    resource: (value) => { resource = value; },
    step: (value) => { currentStep = value; },
    preference(value) { query.matches = value; for (const callback of listeners) callback({ matches: value }); },
    unmount() { for (const effect of effects) effect.cleanup?.(); },
    get listeners() { return listeners.size; },
    get gallery() { return gallery; },
    get opened() { return opened; },
    get reloads() { return reloads; },
  };
}

test('pending images cannot open the viewer; owned decoded images register once', async () => {
  const harness = await cardHarness();
  let tree = harness.render();
  assert.equal(tree.props['data-state'], 'generating');
  assert.equal(tree.props['aria-busy'], true);
  assert.equal(find(tree, (node) => node.type === 'motion.img'), undefined);
  assert.equal(harness.gallery.enabled, false);

  harness.step({ ...step, status: 'done', result: output });
  tree = harness.render();
  assert.equal(tree.props['data-state'], 'loading');
  assert.equal(harness.gallery.enabled, false, 'the URL alone is not delivery');
  harness.resource({ src: 'blob:decoded', failed: false });
  tree = harness.render();
  assert.equal(find(tree, (node) => node.type === 'button').props.disabled, true);
  find(tree, (node) => node.type === 'motion.img').props.onLoad({ currentTarget: { naturalWidth: 640, naturalHeight: 480 } });
  tree = harness.render();
  assert.equal(tree.props['data-state'], 'complete');
  assert.equal(tree.props['aria-busy'], false);
  assert.equal(find(tree, (node) => node.type === 'ImageGenerationDither'), undefined);
  assert.equal(harness.gallery.enabled, true);
  assert.equal(harness.gallery.images[0].originalSrc, '/uploads/local-review.png', 'downloads retain the owned URL');
  find(tree, (node) => node.type === 'button').props.onClick();
  assert.equal(harness.opened, 0);
  harness.unmount();
});

test('an undecodable image offers file reload rather than a paid generation retry', async () => {
  const harness = await cardHarness();
  harness.step({ ...step, status: 'done', result: output });
  harness.resource({ src: 'blob:broken', failed: false });
  let tree = harness.render();
  find(tree, (node) => node.type === 'motion.img').props.onError();
  tree = harness.render();
  assert.equal(tree.props['data-state'], 'failed');
  assert.equal(tree.props['aria-busy'], false);
  assert.equal(harness.gallery.enabled, false);
  find(tree, (node) => node.type === 'Button').props.onClick();
  assert.equal(harness.reloads, 1);
  harness.resource({ src: 'blob:retry', failed: false });
  assert.equal(harness.render().props['data-state'], 'loading');
  harness.unmount();
});

test('changing reduced motion while mounted stops motion and releases the listener', async () => {
  const harness = await cardHarness();
  harness.render();
  let tree = harness.render();
  assert.equal(find(tree, (node) => node.type === 'ImageGenerationDither').props.reduced, false);
  harness.preference(true);
  tree = harness.render();
  assert.equal(find(tree, (node) => node.type === 'ImageGenerationDither').props.reduced, true);
  const mark = find(tree, (node) => node.props?.className === 'image-generation-mark');
  assert.equal(mark.props.animate.rotate, 0);
  assert.equal(mark.props.transition.duration, 0, 'changing the preference stops the spinner immediately');
  assert.equal(mark.props.transition.repeat, 0);
  harness.preference(false);
  assert.equal(find(harness.render(), (node) => node.type === 'ImageGenerationDither').props.reduced, false);
  assert.equal(harness.listeners, 1, 'rerenders do not accumulate listeners');
  harness.unmount();
  assert.equal(harness.listeners, 0);
});

async function ditherHarness(reduced) {
  let cleanup;
  let draws = 0;
  let frameId = 0;
  let color = '#aabbcc';
  const frames = new Map();
  const observers = [];
  const handlers = new Map();
  const listeners = () => ({
    addEventListener: (type, callback) => handlers.set(type, callback),
    removeEventListener: (type) => handlers.delete(type),
  });
  const context = {
    clearRect: () => { draws += 1; }, setTransform() {}, beginPath() {}, arc() {}, fill() {},
  };
  const canvas = { ...listeners(), getContext: () => context, getBoundingClientRect: () => ({ width: 360, height: 240, left: 0, top: 0 }) };
  const document = { ...listeners(), hidden: false, documentElement: {} };
  function observer(kind) {
    return class {
      constructor(callback) { this.kind = kind; this.callback = callback; this.disconnected = false; observers.push(this); }
      observe() {}
      disconnect() { this.disconnected = true; }
    };
  }
  const Dither = await component('ImageGenerationDither', {
    react: { useRef: () => ({ current: canvas }), useEffect: (effect) => { cleanup = effect(); } },
  }, {
    window: { devicePixelRatio: 3, matchMedia: () => ({ matches: true }) }, document,
    getComputedStyle: () => ({ color }),
    ResizeObserver: observer('size'), IntersectionObserver: observer('intersection'), MutationObserver: observer('theme'),
    requestAnimationFrame: (callback) => { frames.set(++frameId, callback); return frameId; },
    cancelAnimationFrame: (id) => frames.delete(id),
  });
  Dither({ reduced });
  return {
    step(time) { const callbacks = [...frames.values()]; frames.clear(); callbacks.forEach((callback) => callback(time)); },
    visible(value) { observers.find((item) => item.kind === 'intersection').callback([{ isIntersecting: value }]); },
    hidden(value) { document.hidden = value; handlers.get('visibilitychange')(); },
    theme(value) { color = value; observers.find((item) => item.kind === 'theme').callback([]); },
    resize() { observers.find((item) => item.kind === 'size').callback([]); },
    cleanup,
    get draws() { return draws; }, get frames() { return frames.size; },
    get color() { return context.fillStyle; },
    get cleaned() { return observers.every((item) => item.disconnected) && handlers.size === 0; },
    canvas,
  };
}

test('canvas pauses offscreen and in hidden documents, resumes once and cancels on unmount', async () => {
  const harness = await ditherHarness(false);
  assert.equal(harness.canvas.width, 720, 'device pixel ratio is capped at two');
  assert.equal(harness.canvas.height, 480);
  harness.step(40);
  assert.equal(harness.frames, 1);
  harness.visible(false);
  const paused = harness.draws;
  harness.step(80);
  assert.equal(harness.draws, paused);
  assert.equal(harness.frames, 0);
  harness.visible(true);
  harness.visible(true);
  assert.equal(harness.frames, 1);
  harness.hidden(true);
  harness.step(120);
  assert.equal(harness.draws, paused);
  harness.hidden(false);
  harness.step(160);
  assert.ok(harness.draws > paused);
  harness.cleanup();
  assert.equal(harness.frames, 0);
  assert.equal(harness.cleaned, true);
});

test('reduced canvas stays static while theme and size changes redraw the field', async () => {
  const harness = await ditherHarness(true);
  const initial = harness.draws;
  harness.step(40);
  assert.equal(harness.frames, 0);
  assert.equal(harness.draws, initial);
  harness.theme('#112233');
  assert.equal(harness.color, '#112233');
  assert.ok(harness.draws > initial);
  const themed = harness.draws;
  harness.resize();
  assert.ok(harness.draws > themed);
  assert.equal(harness.frames, 0);
  harness.cleanup();
  assert.equal(harness.cleaned, true);
});

test('terminal traces and other prose never manufacture a delivered image', () => {
  for (const status of ['failed', 'interrupted']) {
    assert.equal(generationView({ ...step, status, result: output }).url, '');
  }
  for (const suffix of ['\n', '\r', '\r\n', '\u2028']) {
    assert.equal(generationView({ ...step, status: 'done', result: { images: [{ url: '/uploads/local-review.png' + suffix }] } }).status, 'failed');
  }
  const delivered = new Set(['/uploads/local-review.png']);
  assert.equal(imageOnlyDelivery('![Crab](/uploads/local-review.png)', delivered), true);
  assert.equal(imageOnlyDelivery('Caption\n![Crab](/uploads/local-review.png)', delivered), false);
  assert.equal(imageOnlyDelivery('![Crab](/uploads/other.png)', delivered), false);
});

test('delivered images with titles or grouped Markdown leave no empty reply bubble', () => {
  const delivered = new Set(['/uploads/local-review.png', '/uploads/local-second.jpg']);
  for (const text of [
    '![Crab](/uploads/local-review.png "caption")',
    "![Crab](/uploads/local-review.png 'caption')",
    '![A \\] crab](/uploads/local-review.png)',
    '![A [blue] crab](/uploads/local-review.png)',
    '![First](/uploads/local-review.png)\n\n![Second](/uploads/local-second.jpg)',
  ]) assert.equal(imageOnlyDelivery(text, delivered), true, text);
  for (const text of [
    '![First](/uploads/not-delivered.png)\n![Second](/uploads/local-second.jpg)',
    '![First](/uploads/local-review.png)\nA caption\n![Second](/uploads/local-second.jpg)',
    '![Unclosed [label](/uploads/local-review.png)',
    '![Crab](/uploads/local-review.png "caption")\n[Download](/uploads/local-review.png)',
  ]) assert.equal(imageOnlyDelivery(text, delivered), false, text);
});
