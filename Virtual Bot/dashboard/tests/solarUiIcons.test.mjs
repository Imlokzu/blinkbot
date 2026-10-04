import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import test from 'node:test';
import { SOLAR_COMPAT_DATA, SOLAR_UI_REVISION } from '../src/vendor/solar-icons/compat-data.ts';
import * as glyphs from '../src/vendor/solar-icons/compat.ts';

test('every dashboard alias preserves genuine local Solar source geometry', () => {
  assert.equal(SOLAR_UI_REVISION, '44017167688b49109d88ae6a98979b23d8950db0');
  assert.equal(Object.keys(glyphs).length, 135);
  for (const [name, data] of Object.entries(SOLAR_COMPAT_DATA)) {
    assert.ok(glyphs[name], name);
    let count = 0;
    for (const source of data.sources) {
      const raw = readFileSync(new URL(`../src/vendor/solar-icons/originals/ui/${source.path}`, import.meta.url));
      assert.equal(createHash('sha256').update(raw).digest('hex'), source.sha256, name);
      const primitives = [...raw.toString().matchAll(/<(path|circle|ellipse)\b([^>]*)\/>/g)];
      for (const selected of source.select) {
        const primitive = primitives[selected];
        assert.ok(primitive, `${name}: selected primitive exists`);
        const node = data.nodes[count++];
        assert.equal(node[0], primitive[1]);
        for (const [, attribute, value] of primitive[2].matchAll(/([\w-]+)="([^"]*)"/g)) {
          if (['stroke', 'stroke-width', 'fill'].includes(attribute)) continue;
          const camel = attribute.replace(/-([a-z])/g, (_all, letter) => letter.toUpperCase());
          assert.equal(node[1][camel], value, `${name}: preserve ${attribute}`);
        }
        if (source.transform) assert.equal(node[1].transform, source.transform);
      }
    }
    assert.equal(data.nodes.length, count, `${name}: no invented primitives`);
  }
});

test('Solar aliases retain component properties, theme color and accessibility', () => {
  for (const [name, Icon] of Object.entries(glyphs)) {
    const html = renderToStaticMarkup(createElement(Icon, {
      size: 20, strokeWidth: 1.5, color: '#123456', 'aria-hidden': true, className: 'fixture-icon',
    }));
    assert.match(html, /width="20"/);
    assert.match(html, /stroke-width="1.5"/);
    assert.match(html, /style="color:#123456"/);
    assert.match(html, /fixture-icon/);
    assert.match(html, /aria-hidden="true"/);
    assert.match(html, /data-solar-icon="[a-z0-9-]+"/);
    assert.doesNotMatch(html, /<img|<use|<script|<foreignObject|(?:href|src)="https?:\/\//, name);
  }
  const html = renderToStaticMarkup(createElement(glyphs.Search, { color: '#123456', style: { color: '#abcdef' } }));
  assert.match(html, /style="color:#abcdef"/, 'an explicit style remains authoritative');
});

test('off-state icons remain distinct and ReactBits checks remain path data', () => {
  for (const [on, off] of [['Camera', 'CameraOff'], ['Image', 'ImageOff'], ['Pin', 'PinOff']]) {
    assert.ok(SOLAR_COMPAT_DATA[off].nodes.length > SOLAR_COMPAT_DATA[on].nodes.length, off);
    assert.notDeepEqual(SOLAR_COMPAT_DATA[on].nodes, SOLAR_COMPAT_DATA[off].nodes);
  }
  assert.equal(SOLAR_COMPAT_DATA.Check.nodes[0][0], 'path');
  assert.ok(SOLAR_COMPAT_DATA.Check.nodes[0][1].d);
  for (const name of ['Heart', 'ThumbsUp']) {
    assert.ok(SOLAR_COMPAT_DATA[name].nodes.every(([tag]) => tag === 'path'));
    assert.equal(SOLAR_COMPAT_DATA[name].nodes[0][1].stroke, 'none');
    assert.equal(SOLAR_COMPAT_DATA[name].nodes[0][1].fill, 'currentColor');
  }
});

test('generic app imports use Solar while renderer code keeps its existing dependency', () => {
  const root = fileURLToPath(new URL('../src/', import.meta.url));
  const findings = [];
  function visit(folder) {
    for (const entry of readdirSync(folder, { withFileTypes: true })) {
      const file = join(folder, entry.name);
      if (entry.isDirectory()) visit(file);
      else if (/\.[jt]sx?$/.test(file) && !file.includes('/vendor/solar-icons/')) {
        if (/\bfrom\s*['"]lucide-react['"]/.test(readFileSync(file, 'utf8'))) findings.push(file);
      }
    }
  }
  visit(root);
  assert.deepEqual(findings, [], 'every app glyph import goes through the Solar adapter');
});
