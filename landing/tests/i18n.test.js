// Run with `npm test` (node --test). No browser needed: the catalogue and the
// prerender are plain modules.

import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { catalogue, detectLang, glue, t } from "../src/i18n.js";
import { prerender } from "../scripts/prerender-i18n.js";

const read = (relative) => readFileSync(fileURLToPath(new URL(relative, import.meta.url)), "utf8");
const html = read("../index.html");
// Cyrillic examples live in a fixture, so this file stays English-only.
const glueCases = JSON.parse(read("./fixtures/glue.json"));
const CYRILLIC = /[\u0400-\u04ff]/;
const uk = catalogue("uk");
const en = catalogue("en");

function keysUsedInHtml(source) {
  const keys = new Set([...source.matchAll(/data-i18n="([^"]+)"/g)].map((m) => m[1]));
  for (const [, spec] of source.matchAll(/data-i18n-attr="([^"]+)"/g)) {
    for (const pair of spec.split(";")) keys.add(pair.split(":")[1].trim());
  }
  return keys;
}

test("both languages carry the same keys, none of them empty", () => {
  assert.deepEqual(Object.keys(uk).sort(), Object.keys(en).sort());
  for (const [lang, table] of [["uk", uk], ["en", en]]) {
    for (const [key, value] of Object.entries(table)) {
      assert.ok(value.trim(), `${lang}: ${key} is empty`);
    }
  }
});

test("English strings contain no Cyrillic", () => {
  for (const [key, value] of Object.entries(en)) {
    assert.doesNotMatch(value, CYRILLIC, key);
  }
});

test("every key the page uses exists", () => {
  for (const key of keysUsedInHtml(html)) {
    assert.ok(key in en, `index.html uses unknown key ${key}`);
  }
});

test("translatable elements are written empty, so the prerender can fill them", () => {
  // <tag data-i18n="..."> must be followed directly by its closing tag.
  for (const match of html.matchAll(/<([a-z][a-z0-9-]*)\b[^>]*\sdata-i18n="([^"]+)"[^>]*>([\s\S]*?)<\/\1>/g)) {
    assert.equal(match[3], "", `data-i18n="${match[2]}" has content in index.html`);
  }
});

test("the prerender fills text and attributes in the default language", () => {
  const out = prerender(html, (key) => t(key, "en"));
  assert.match(out, /<title data-i18n="meta.title">Blink Bot — an AI companion that lives on your desk<\/title>/);
  assert.match(out, /name="description" data-i18n-attr="content:meta.description" content="An open-source AI companion/);
  assert.doesNotMatch(out, /data-i18n="[^"]+"><\//, "an element was left empty");
});

test("the prerender escapes text and refuses unknown keys", () => {
  const filled = prerender('<p data-i18n="x"></p>', () => 'a < b & "c"');
  assert.equal(filled, '<p data-i18n="x">a &lt; b &amp; &quot;c&quot;</p>');
  assert.throws(() => prerender('<p data-i18n="nope"></p>', (key) => key), /nope/);
});

test("language: ?lang wins, then the saved choice, then the browser", () => {
  assert.equal(detectLang("?lang=uk", "en", ["en-US"]), "uk");
  assert.equal(detectLang("", "uk", ["en-US"]), "uk");
  assert.equal(detectLang("", null, ["de-DE", "uk-UA"]), "uk");
  assert.equal(detectLang("", null, ["ru-RU"]), "en");
  assert.equal(detectLang("?lang=fr", "nope", []), "en");
});

test("Ukrainian one-letter words stick to the next word", () => {
  for (const { lang, input, expected } of glueCases) {
    assert.equal(glue(input, lang), expected);
  }
});
