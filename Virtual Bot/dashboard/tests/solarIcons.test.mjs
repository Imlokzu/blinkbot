import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import test from 'node:test';
import { SOLAR_ICON_DATA, SOLAR_REVISION } from '../src/vendor/solar-icons/data.ts';
import * as icons from '../src/vendor/solar-icons/index.ts';

test('Solar uses a local subset of genuine pinned original artwork', () => {
  assert.equal(SOLAR_REVISION, '44017167688b49109d88ae6a98979b23d8950db0');
  assert.equal(Object.keys(SOLAR_ICON_DATA).length, 18);
  for (const [id, icon] of Object.entries(SOLAR_ICON_DATA)) {
    const original = readFileSync(new URL(`../src/vendor/solar-icons/originals/${id}.svg`, import.meta.url));
    assert.equal(createHash('sha256').update(original).digest('hex'), icon.sha256, id);
    const source = original.toString();
    assert.match(source, /viewBox="0 0 24 24"/);
    for (const [tag, attributes] of icon.nodes) {
      assert.ok(['path', 'circle', 'line', 'rect', 'polygon', 'polyline', 'ellipse'].includes(tag));
      if (tag === 'path') assert.ok(source.includes(`d="${attributes.d}"`), `${id}: preserve original paths`);
      assert.equal(attributes.strokeWidth, undefined, `${id}: theme controls stroke width`);
      assert.ok(!Object.values(attributes).includes('black'), `${id}: theme controls ink`);
    }
  }
});

test('Solar renders direct decorative SVG geometry and preserves filled punctuation', () => {
  for (const [name, Icon] of Object.entries(icons)) {
    const svg = renderToStaticMarkup(createElement(Icon, { strokeWidth: 1.75, 'aria-hidden': true }));
    assert.match(svg, /data-solar-icon="[a-z]+"/);
    assert.match(svg, /stroke-width="1.75"/);
    assert.match(svg, /aria-hidden="true"/);
    assert.doesNotMatch(svg, /<img|<use|<script|<foreignObject|(?:href|src)="https?:\/\//, name);
    if (name === 'SolarQuestion' || name === 'SolarAlert') {
      assert.match(svg, /<circle[^>]*r="1"[^>]*fill="currentColor"[^>]*stroke="none"/);
    }
  }
});

test('visible Settings attribution links to the shipped notice with author and license', () => {
  const notice = readFileSync(new URL('../public/solar-icons-notice.txt', import.meta.url), 'utf8');
  assert.match(notice, /480 Design/);
  assert.match(notice, /https:\/\/creativecommons.org\/licenses\/by\/4\.0\//);
  assert.match(notice, /Adaptations/);
  const settings = readFileSync(new URL('../src/panels/chat/ChatAppearance.tsx', import.meta.url), 'utf8');
  assert.match(settings, /data-solar-credit/);
  assert.match(settings, /solar-icons-notice\.txt/);
});
