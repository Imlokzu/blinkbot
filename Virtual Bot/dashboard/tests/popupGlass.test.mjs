import assert from 'node:assert/strict';
import test from 'node:test';
import { applyPopupGlassTargets, createPopupGlassStore, DEFAULT_POPUP_GLASS_TARGETS, LEGACY_POPUP_STORAGE_KEY,
  normalizePopupGlassTargets, POPUP_GLASS_KINDS, POPUP_GLASS_STORAGE_KEY, popupGlassKind, readPopupGlassTargets,
} from '../src/hooks/popupGlassPreferences.ts';

function memoryStorage(initial = {}) {
  const values = new Map(Object.entries(initial));
  const writes = [];
  let failReads = false;
  let failWrites = false;
  return {
    getItem(key) {
      if (failReads) throw new Error('Storage is denied');
      return values.get(key) ?? null;
    },
    setItem(key, value) {
      if (failWrites) throw new Error('Storage is full');
      values.set(key, value);
      writes.push([key, value]);
    },
    values,
    writes,
    set failReads(value) { failReads = value; },
    set failWrites(value) { failWrites = value; },
  };
}

test('popup glass defaults to none and migrates only a missing preference from the old glass switch', () => {
  assert.deepEqual(readPopupGlassTargets(null, null), []);
  assert.deepEqual(readPopupGlassTargets(null, 'solid'), []);
  assert.deepEqual(readPopupGlassTargets(null, 'unknown'), []);
  assert.deepEqual(readPopupGlassTargets(null, 'glass'), POPUP_GLASS_KINDS);
  // Clearing every checkbox must survive a reload even when the legacy switch was glass.
  assert.deepEqual(readPopupGlassTargets('[]', 'glass'), []);
  assert.deepEqual(readPopupGlassTargets('["context"]', 'glass'), ['context']);
});

test('malformed explicit preferences never revive the legacy all-popup choice', () => {
  for (const raw of ['', '{', 'null', '{}', '"models"', 'true', '42']) {
    assert.deepEqual(readPopupGlassTargets(raw, 'glass'), [], raw);
  }
  for (const value of [undefined, null, {}, true, 12, 'models']) {
    assert.equal(normalizePopupGlassTargets(value), DEFAULT_POPUP_GLASS_TARGETS);
  }
  assert.deepEqual(normalizePopupGlassTargets(['menus', 'unknown', 'models', 'menus', 5, null]), ['models', 'menus']);
  assert.equal(Object.isFrozen(normalizePopupGlassTargets(['models'])), true);
});

test('all, none and a subset round trip through one canonical preference key', () => {
  const storage = memoryStorage({ [LEGACY_POPUP_STORAGE_KEY]: 'glass' });
  const store = createPopupGlassStore(() => storage);
  assert.deepEqual(store.getSnapshot(), POPUP_GLASS_KINDS);
  assert.equal(store.setTargets([]), true);
  assert.deepEqual(createPopupGlassStore(() => storage).getSnapshot(), []);
  assert.equal(store.setTargets(['menus', 'models']), true);
  assert.deepEqual(createPopupGlassStore(() => storage).getSnapshot(), ['models', 'menus']);
  assert.equal(store.setTargets(POPUP_GLASS_KINDS), true);
  assert.deepEqual(createPopupGlassStore(() => storage).getSnapshot(), POPUP_GLASS_KINDS);
  assert.equal(storage.writes.every(([key]) => key === POPUP_GLASS_STORAGE_KEY), true);
  assert.equal(storage.getItem(LEGACY_POPUP_STORAGE_KEY), 'glass');
});

test('a failed write preserves the previous snapshot and sends no false change to mounted consumers', () => {
  const storage = memoryStorage({ [POPUP_GLASS_STORAGE_KEY]: '["models"]' });
  const store = createPopupGlassStore(() => storage);
  const previous = store.getSnapshot();
  let changes = 0;
  const unsubscribe = store.subscribe(() => { changes++; });
  storage.failWrites = true;
  assert.equal(store.setTargets(['context']), false);
  assert.equal(store.getSnapshot(), previous);
  assert.equal(changes, 0);
  assert.equal(storage.getItem(POPUP_GLASS_STORAGE_KEY), '["models"]');
  unsubscribe();
});

test('unavailable storage starts safely and later read failures preserve a saved selection', () => {
  const unavailable = createPopupGlassStore(() => null);
  assert.deepEqual(unavailable.getSnapshot(), []);
  assert.equal(unavailable.setTargets(['models']), false);
  const storage = memoryStorage({ [POPUP_GLASS_STORAGE_KEY]: '["context"]' });
  const store = createPopupGlassStore(() => storage);
  const previous = store.getSnapshot();
  storage.failReads = true;
  store.refresh();
  assert.equal(store.getSnapshot(), previous);
  const denied = createPopupGlassStore(() => { throw new Error('Storage access is denied'); });
  assert.deepEqual(denied.getSnapshot(), []);
  assert.equal(denied.setTargets(['models']), false);
});

test('same-tab consumers share stable snapshots and functional updates compose immediately', () => {
  const storage = memoryStorage();
  const store = createPopupGlassStore(() => storage);
  const first = [];
  const second = [];
  const stopFirst = store.subscribe(() => first.push(store.getSnapshot()));
  const stopSecond = store.subscribe(() => second.push(store.getSnapshot()));
  assert.equal(store.getSnapshot(), store.getSnapshot());
  store.setTargets(previous => [...previous, 'models']);
  store.setTargets(previous => [...previous, 'context']);
  assert.deepEqual(first, [['models'], ['models', 'context']]);
  assert.deepEqual(second, first);
  assert.equal(first[1], second[1]);
  store.setTargets(['context', 'models', 'models']);
  assert.equal(first.length, 2);
  stopFirst();
  store.setTargets([]);
  assert.equal(first.length, 2);
  assert.deepEqual(second[2], []);
  stopSecond();
});

test('external changes and clearing storage refresh every subscriber with one shared listener', () => {
  const storage = memoryStorage();
  let refreshExternal;
  let starts = 0;
  let stops = 0;
  const store = createPopupGlassStore(() => storage, callback => {
    starts++;
    refreshExternal = callback;
    return () => { stops++; };
  });
  const first = [];
  const second = [];
  const stopFirst = store.subscribe(() => first.push(store.getSnapshot()));
  const stopSecond = store.subscribe(() => second.push(store.getSnapshot()));
  assert.equal(starts, 1);
  storage.values.set(POPUP_GLASS_STORAGE_KEY, '["attachments"]');
  refreshExternal();
  assert.deepEqual(first, [['attachments']]);
  assert.deepEqual(second, first);
  storage.values.clear();
  refreshExternal();
  assert.deepEqual(first[1], []);
  stopFirst();
  assert.equal(stops, 0);
  stopSecond();
  assert.equal(stops, 1);
  store.subscribe(() => {})();
  assert.equal(starts, 2);
  assert.equal(stops, 2);
});

test('subscription closes the gap after a value changed between render and mount', () => {
  const storage = memoryStorage();
  const store = createPopupGlassStore(() => storage);
  assert.deepEqual(store.getSnapshot(), []);
  storage.values.set(POPUP_GLASS_STORAGE_KEY, '["context"]');
  let changes = 0;
  const stop = store.subscribe(() => { changes++; });
  assert.equal(changes, 1);
  assert.deepEqual(store.getSnapshot(), ['context']);
  stop();
});

// A minimal element facade lets classification use the same selector/ancestor API as a browser.
function surface(classes = [], attributes = {}, parent = null) {
  return {
    attributes,
    parent,
    matches(selectors) {
      return selectors.split(',').some(selector => {
        const trimmed = selector.trim();
        const className = /^\.([\w-]+)/.exec(trimmed)?.[1];
        if (className && !classes.includes(className)) return false;
        const attribute = /\[([\w-]+)(?:="([^"]*)")?\]/.exec(trimmed);
        if (attribute && (!(attribute[1] in attributes) || (attribute[2] !== undefined && attributes[attribute[1]] !== attribute[2]))) return false;
        return Boolean(className || attribute);
      });
    },
    getAttribute(name) { return attributes[name] ?? null; },
    closest(selector) {
      for (let element = this; element; element = element.parent) if (element.matches(selector)) return element;
      return null;
    },
  };
}

test('only existing popup surfaces and explicit roots can be classified for glass', () => {
  assert.equal(popupGlassKind(surface([], { 'data-popup-kind': 'models' })), null);
  assert.equal(popupGlassKind(surface([], { role: 'dialog' })), null);
  assert.equal(popupGlassKind(surface(['popup-shell'])), 'menus');
  assert.equal(popupGlassKind(surface(['prompt-bar__menu'])), 'attachments');
  assert.equal(popupGlassKind(surface([], { 'data-popup-root': '', 'data-popup-kind': 'attachments' })), 'attachments');
  assert.equal(popupGlassKind(surface([], { 'data-popup-root': '' })), null);
});

test('specific categories never fall through to other menus when they are unselected', () => {
  for (const className of ['model-menu', 'model-effort-menu', 'effort-menu']) {
    assert.equal(popupGlassKind(surface(['popup-shell', className])), 'models');
  }
  for (const dataKind of ['model', 'effort']) {
    assert.equal(popupGlassKind(surface(['prompt-bar__menu'], { 'data-kind': dataKind })), 'models');
  }
  assert.equal(popupGlassKind(surface(['popup-shell', 'context-popover'])), 'context');
  const markedModel = surface(['popup-shell'], { 'data-popup-kind': 'models' });
  assert.equal(['menus'].includes(popupGlassKind(markedModel)), false);
  assert.equal(popupGlassKind(surface(['popup-shell'], { 'data-popup-kind': 'unknown' })), null);
});

test('the nearest category marker wins over the surrounding popup category', () => {
  const attachments = surface([], { 'data-popup-kind': 'attachments' });
  assert.equal(popupGlassKind(surface(['popup-shell'], {}, attachments)), 'attachments');
  assert.equal(popupGlassKind(surface(['popup-shell'], { 'data-popup-kind': 'context' }, attachments)), 'context');
});

test('the document CSS gate follows any selection while retaining the exact selected categories', () => {
  const root = { dataset: { popup: 'glass' } };
  applyPopupGlassTargets(root, []);
  assert.equal(root.dataset.popup, undefined);
  assert.equal(root.dataset.popupGlassTargets, '');
  applyPopupGlassTargets(root, ['models', 'context']);
  assert.equal(root.dataset.popup, 'glass');
  assert.equal(root.dataset.popupGlassTargets, 'models context');
});
