import assert from 'node:assert/strict';
import test from 'node:test';
import { arrange, brandOf, byLineup, hostOf, matches, parseRecent, parseSort, remember } from '../src/panels/chat/modelCatalog.ts';

test('old or malformed stored preferences cannot break opening or picking a model', () => {
  for (const value of [null, {}, 3, 'model', false]) assert.deepEqual(parseRecent(value), []);
  assert.deepEqual(parseRecent(['a', null, 'a', '', ' ', {}, 'b', 'c', 'd']), ['a', 'b', 'c']);
  for (const value of [null, {}, ['name'], 'unknown']) assert.equal(parseSort(value), 'maker');
  assert.equal(parseSort('name'), 'name');
  assert.equal(parseSort('context'), 'context');
});

test('capability filters use only reported facts and never promise Jev can do them', () => {
  const models = [
    { id: 'jev', label: 'Jev', auto: true, fast: true, vision: true },
    { id: 'openai/vision', label: 'Vision', vision: true },
    { id: 'regolo/fast', label: 'Fast', fast: true },
    { id: 'openai/unknown', label: 'Unknown' },
  ];
  const ids = (options) => [...new Set(arrange(models, options).flatMap((group) => group.models.map((model) => model.id)))];
  assert.deepEqual(ids({ capability: 'vision', recent: ['regolo/fast', 'openai/vision'] }), ['openai/vision']);
  assert.deepEqual(ids({ capability: 'fast', query: 'regolo' }), ['regolo/fast']);
  assert.deepEqual(ids({ capability: 'fast', query: 'vision' }), []);
  assert.equal(ids({ capability: 'all' }).length, 4);
  assert.equal(arrange(models, { recent: ['regolo/fast', 'regolo/fast'] }).find((group) => group.brand === 'recent').models.length, 1);
});

// The real OpenClaw catalog on 2026-09-24, trimmed to the cases that matter.
const CATALOG = [
  ['nvidia/deepseek-ai/deepseek-v4-pro', 'DeepSeek V4 Pro', 'deepseek'],
  ['nvidia/minimaxai/minimax-m3', 'Minimax M3', 'minimax'],
  ['nvidia/moonshotai/kimi-k2.6', 'Kimi K2.6', 'moonshot'],
  ['nvidia/nemotron-3-super-120b-a12b', 'Nemotron 3 Super 120B', 'nvidia'],
  ['nvidia/openai/gpt-oss-20b', 'GPT-OSS 20B', 'openai'],
  ['nvidia/z-ai/glm-5.2', 'GLM 5.2', 'zhipu'],
  ['omni/claude/claude-opus-4-8', 'Claude Opus 4.8', 'anthropic'],
  ['openai/gpt-6-luna', 'GPT-6 Luna', 'openai'],
  ['openai/gpt-5.4-nano', 'GPT-5.4 Nano', 'openai'],
  ['opencode-go/qwen3.8-max', 'Qwen3.8 Max', 'qwen'],
  ['opencode-go/longcat-2.0', 'LongCat-2.0', 'meituan'],
  ['opencode-go/mimo-v2.6-pro', 'MiMo-V2.6-Pro', 'xiaomi'],
  ['opencode-go/minimax-m2.7', 'MiniMax-M2.7', 'minimax'],
  ['opencode-go/grok-4.7', 'Grok 4.7', 'xai'],
  ['opencode-go/hy3', 'Hy3', 'tencent'],
  ['opencode-go/muse-spark-1.3-contributor', 'Muse Spark 1.3 Contributor', null],
  ['opencode-go/space-bunny-free', 'Space Bunny Free', null],
  ['regolo/gpt-oss-120b', 'GPT-OSS 120B', 'openai'],
  ['regolo/qwen3.5-9b', 'Qwen 3.5 9B', 'qwen'],
].map(([id, label, brand]) => ({ id, label, brand }));

test('the maker comes from the model, never from the host that serves it', () => {
  for (const model of CATALOG) assert.equal(brandOf(model), model.brand, model.id);
});

test('an unknown model gets no logo instead of a guessed one', () => {
  assert.equal(brandOf({ id: 'opencode-go/space-bunny-free', label: 'Space Bunny Free' }), null);
});

test('the host is what tells three copies of one model apart', () => {
  assert.equal(hostOf('omni/opencode-go/kimi-k3'), 'omni/opencode-go');
  assert.equal(hostOf('opencode-go/kimi-k3'), 'opencode-go');
  assert.equal(hostOf('bare-model'), '');
});

test('search takes words in any order and ignores the catalog punctuation', () => {
  const find = (query) => CATALOG.filter((model) => matches(model, query)).map((model) => model.id);
  assert.deepEqual(find('gpt6'), ['openai/gpt-6-luna']);
  assert.deepEqual(find('luna gpt'), ['openai/gpt-6-luna']);
  assert.deepEqual(find('qwen 3.8'), ['opencode-go/qwen3.8-max']);
  // By maker name, which appears in neither the id nor the label.
  assert.deepEqual(find('xai'), ['opencode-go/grok-4.7']);
  // By host, to pick one copy out of several.
  assert.deepEqual(find('regolo qwen'), ['regolo/qwen3.5-9b']);
  assert.equal(find('').length, CATALOG.length);
});

test('grouping by maker keeps "other" last and the flagship line on top', () => {
  const groups = arrange(CATALOG);
  const names = groups.map((group) => group.brand);
  assert.equal(names.at(-1), 'other');
  assert.equal(names[0], 'anthropic');
  const openai = groups.find((group) => group.brand === 'openai').models.map((model) => model.label);
  // GPT-6 above 5.4, and the heavier OSS model above the 20B one.
  assert.deepEqual(openai, ['GPT-6 Luna', 'GPT-5.4 Nano', 'GPT-OSS 120B', 'GPT-OSS 20B']);
});

test('the lineup is 6 Astra, Sol, Luna, then 5.6 Sol, Terra, Luna, then lighter models', () => {
  const models = [
    ['openai/gpt-5.4-nano', 'GPT-5.4 Nano'],
    ['openai/gpt-5.6-luna', 'GPT-5.6 Luna'],
    ['regolo/gpt-oss-20b', 'GPT-OSS 20B'],
    ['openai/gpt-6-luna', 'GPT-6 Luna'],
    ['openai/gpt-5.6-sol', 'GPT-5.6 Sol'],
    ['openai/gpt-6-astra', 'GPT-6 Astra'],
    ['openai/gpt-5.5', 'GPT-5.5'],
    ['openai/gpt-5.4-pro', 'GPT-5.4 Pro'],
    ['regolo/qwen3.5-9b', 'Qwen 3.5 9B'],
    ['openai/gpt-6-sol', 'GPT-6 Sol'],
    ['openai/gpt-5.6-terra', 'GPT-5.6 Terra'],
    ['openai/gpt-5.5-pro', 'GPT-5.5 Pro'],
    ['regolo/gpt-oss-120b', 'GPT-OSS 120B'],
    ['regolo/qwen3.5-122b', 'Qwen 3.5 122B'],
  ].map(([id, label]) => ({ id, label }));
  assert.deepEqual([...models].sort(byLineup).map((model) => model.id), [
    'openai/gpt-6-astra',
    'openai/gpt-6-sol',
    'openai/gpt-6-luna',
    'openai/gpt-5.6-sol',
    'openai/gpt-5.6-terra',
    'openai/gpt-5.6-luna',
    'openai/gpt-5.5-pro',
    'openai/gpt-5.5',
    'openai/gpt-5.4-pro',
    'openai/gpt-5.4-nano',
    'regolo/qwen3.5-122b',
    'regolo/gpt-oss-120b',
    'regolo/gpt-oss-20b',
    'regolo/qwen3.5-9b',
  ]);
});

test('recent picks lead the list, but step aside while searching', () => {
  const recent = ['regolo/qwen3.5-9b', 'openai/gpt-6-luna', 'gone/removed-model'];
  const groups = arrange(CATALOG, { recent });
  assert.equal(groups[0].brand, 'recent');
  // A model that left the catalog is skipped, not shown as a ghost row.
  assert.deepEqual(groups[0].models.map((model) => model.id), recent.slice(0, 2));
  assert.equal(arrange(CATALOG, { recent, query: 'grok' })[0].brand, 'xai');
});

test('context sort puts the biggest window first and unknown sizes last', () => {
  const models = [
    { id: 'a/x', label: 'X', context: 128000 },
    { id: 'a/y', label: 'Y' },
    { id: 'a/z', label: 'Z', context: 1000000 },
  ];
  assert.deepEqual(arrange(models, { sort: 'context' })[0].models.map((m) => m.id), ['a/z', 'a/x', 'a/y']);
});

test('remembering a pick moves it to the front without repeats', () => {
  assert.deepEqual(remember(['a', 'b', 'c'], 'c'), ['c', 'a', 'b']);
  assert.deepEqual(remember(['a', 'b', 'c'], 'd'), ['d', 'a', 'b']);
});

test('a query matches the start of a word, not the middle of one', () => {
  const find = (query) => CATALOG.filter((model) => matches(model, query)).map((model) => model.id);
  // "xai" sits inside "minimaxai"; that is not a match.
  assert.ok(!find('xai').includes('nvidia/minimaxai/minimax-m3'));
  assert.deepEqual(find('oss 120'), ['regolo/gpt-oss-120b']);
  assert.deepEqual(find('hy3'), ['opencode-go/hy3']);
});

test('Jev, the automatic pick, leads the list in every sort and stays out of recent', () => {
  const jev = { id: 'jev/auto', label: 'Jev', auto: true };
  const models = [...CATALOG, jev];
  for (const sort of ['maker', 'name', 'context']) {
    const groups = arrange(models, { sort, recent: ['jev/auto', 'openai/gpt-6-luna'] });
    assert.equal(groups[0].brand, 'auto', sort);
    assert.deepEqual(groups[0].models.map((model) => model.id), ['jev/auto'], sort);
    const rest = groups.slice(1).flatMap((group) => group.models.map((model) => model.id));
    assert.ok(!rest.includes('jev/auto'), sort);
  }
  assert.equal([...models].sort(byLineup)[0].id, 'jev/auto');
});

test('searching for Jev finds it, and a query that does not name it hides it', () => {
  const jev = { id: 'jev/auto', label: 'Jev', auto: true };
  assert.equal(arrange([...CATALOG, jev], { query: 'jev' })[0].brand, 'auto');
  assert.ok(!arrange([...CATALOG, jev], { query: 'luna' }).some((group) => group.brand === 'auto'));
});
