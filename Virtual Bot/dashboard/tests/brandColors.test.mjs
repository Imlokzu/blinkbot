import assert from 'node:assert/strict';
import test from 'node:test';
import { BRAND_ICONS } from '../src/vendor/lobe-icons/index.ts';
import { BRAND_COLORS, brandColor, modelColor } from '../src/panels/chat/brandColors.ts';

test('every vendored maker logo has valid ink for both dashboard themes', () => {
  assert.deepEqual(Object.keys(BRAND_COLORS).sort(), Object.keys(BRAND_ICONS).sort());
  for (const [brand, colors] of Object.entries(BRAND_COLORS)) {
    assert.match(colors.light, /^#[0-9a-f]{6}$/i, `${brand}: light`);
    assert.match(colors.dark, /^#[0-9a-f]{6}$/i, `${brand}: dark`);
    assert.notEqual(colors.light, colors.dark, `${brand} needs theme-specific ink`);
    assert.equal(brandColor(brand), `light-dark(${colors.light}, ${colors.dark})`);
  }
});

test('hosted models keep their maker colour even when the host has its own logo', () => {
  const cases = [
    ['nvidia/deepseek-ai/deepseek-v4-pro', 'DeepSeek V4 Pro', 'deepseek'],
    ['nvidia/openai/gpt-oss-120b', 'GPT-OSS 120B', 'openai'],
    ['regolo/qwen3.5-9b', 'Qwen 3.5 9B', 'qwen'],
    ['omni/claude/claude-opus-4-8', 'Claude Opus 4.8', 'anthropic'],
    ['nvidia/z-ai/glm-5.2', 'GLM 5.2', 'zhipu'],
    ['opencode-go/gemini-3', 'Gemini 3', 'google'],
    ['nvidia/nemotron-3-super', 'Nemotron 3 Super', 'nvidia'],
  ];
  for (const [id, label, brand] of cases) {
    assert.equal(modelColor({ id, label }), brandColor(brand), id);
  }
});

test('unknown models retain neutral ink instead of borrowing a host colour', () => {
  assert.equal(brandColor(null), 'var(--color-ink-3)');
  assert.equal(modelColor({ id: 'openai/unknown', label: 'Unknown' }), brandColor(null));
  assert.equal(modelColor({ id: 'nvidia/space-bunny', label: 'Space Bunny' }), brandColor(null));
});
