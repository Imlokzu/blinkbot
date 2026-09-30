"""Exercise the shipped converter model in node, without a browser or network."""

from __future__ import annotations

import json
import re
import shutil
import subprocess
from html.parser import HTMLParser
from pathlib import Path

import pytest

PACKAGE = Path(__file__).resolve().parents[1] / "store" / "packages" / "unit-converter"
NODE = shutil.which("node")
needs_node = pytest.mark.skipif(NODE is None, reason="node is not installed")


def run_model(body: str):
    script = f"const m = require({json.dumps(str(PACKAGE / 'model.js'))});\n{body}"
    result = subprocess.run(
        [NODE, "-e", script], capture_output=True, text=True, timeout=20,
    )
    assert result.returncode == 0, result.stderr or result.stdout
    return json.loads(result.stdout)


@needs_node
@pytest.mark.parametrize("category,source,target,value,expected", [
    ("temperature", "c", "f", "0", 32),
    ("temperature", "f", "c", "32", 0),
    ("temperature", "c", "f", "-40", -40),
    ("temperature", "f", "c", "-40", -40),
    ("temperature", "c", "k", "-273,15", 0),
    ("temperature", "k", "c", "0", -273.15),
    ("temperature", "f", "k", "212", 373.15),
    ("temperature", "k", "f", "373.15", 212),
    ("length", "mile", "m", "1", 1609.344),
    ("length", "inch", "cm", "1", 2.54),
    ("length", "yard", "foot", "1", 3),
    ("mass", "lb", "kg", "1", 0.45359237),
    ("mass", "oz", "g", "1", 28.349523125),
    ("volume", "us_gal", "l", "1", 3.785411784),
    ("volume", "us_fl_oz", "ml", "1", 29.5735295625),
    ("volume", "m3", "l", "1", 1000),
    ("speed", "kmh", "ms", "36", 10),
    ("speed", "mph", "kmh", "60", 96.56064),
    ("speed", "knot", "kmh", "1", 1.852),
])
def test_known_conversions_include_temperature_offsets(category, source, target, value, expected):
    args = json.dumps([category, source, target, value])
    result = run_model(f"console.log(JSON.stringify(m.convert(...{args}))); ")
    assert result["ok"] is True
    assert result["value"] == pytest.approx(expected)


@needs_node
def test_every_unit_pair_roundtrips_and_identity_preserves_small_values():
    # Walking every pair catches inverted factors and omitted offset directions.
    results = run_model("""
const results = [];
for (const [category, definition] of Object.entries(m.categories)) {
  for (const from of Object.keys(definition.units)) {
    for (const to of Object.keys(definition.units)) {
      for (const value of [-40, 0, 0.125, 12345.678]) {
        const forward = m.convert(category, from, to, value);
        const back = m.convert(category, to, from, forward.value);
        results.push({category, from, to, value, forward, back});
      }
    }
  }
}
console.log(JSON.stringify({results, tiny: m.convert('temperature', 'c', 'c', '1e-100')}));
""")
    for pair in results["results"]:
        assert pair["forward"]["ok"] and pair["back"]["ok"], pair
        assert pair["back"]["value"] == pytest.approx(pair["value"], abs=1e-9), pair
    assert results["tiny"] == {"ok": True, "value": 1e-100}


@needs_node
@pytest.mark.parametrize("text,expected", [
    ("-12,5", -12.5), ("  +12.5  ", 12.5), (",25", 0.25),
    ("-.25", -0.25), ("12,", 12), ("-0", 0), ("1e-12", 1e-12),
    ("1000000000000000", 1e15),
])
def test_decimal_comma_and_signed_values(text, expected):
    result = run_model(f"console.log(JSON.stringify(m.parseDecimal({json.dumps(text)}))); ")
    assert result == {"ok": True, "value": expected}


@needs_node
@pytest.mark.parametrize("value", [
    None, True, {}, [], "", "  ", "-", ".", "-,", "NaN", "Infinity",
    "0x10", "1/2", "1 000", "1,2.3", "1,2,3", "2e", "2e+", "1e309",
    "1e-999", "1000000000000001", "9" * 10000,
])
def test_invalid_or_large_input_never_becomes_a_result(value):
    result = run_model(f"console.log(JSON.stringify(m.parseDecimal({json.dumps(value)}))); ")
    assert result["ok"] is False
    assert result["error"] in {"empty", "number", "range"}
    assert "value" not in result


@needs_node
def test_invalid_units_nonfinite_values_and_conversion_overflow():
    result = run_model("""
console.log(JSON.stringify([
  m.convert('length', 'km', 'mm', '1000000000000000'),
  m.convert('length', 'mm', 'km', '5e-324'),
  m.convert('length', 'm', 'm', Infinity),
  m.convert('length', 'm', 'm', NaN),
  m.convert('currency', 'usd', 'eur', '1'),
  m.convert('length', 'm', 'kg', '1'),
  m.convert('length', '__proto__', 'm', '1'),
  m.convert('toString', 'm', 'cm', '1')
]));
""")
    assert all(item["ok"] is False for item in result)
    assert [item["error"] for item in result[:2]] == ["range", "range"]


@needs_node
def test_swap_uses_result_as_new_input_and_is_not_mutating():
    result = run_model("""
const original = {category: 'temperature', from: 'c', to: 'f', value: '-40,5'};
const swapped = m.swapState(original);
const twice = m.swapState(swapped);
const small = m.swapState({category: 'length', from: 'mm', to: 'km', value: '0.000001'});
console.log(JSON.stringify({original, swapped, twice, small,
  empty: m.swapState({category: 'temperature', from: 'c', to: 'f', value: ''}),
  large: m.swapState({category: 'length', from: 'm', to: 'cm', value: '1000000000000001'})}));
""")
    assert result["original"]["value"] == "-40,5"
    assert {key: result["swapped"][key] for key in ("category", "from", "to")} == {
        "category": "temperature", "from": "f", "to": "c",
    }
    assert float(result["swapped"]["value"]) == pytest.approx(-40.9)
    assert float(result["twice"]["value"]) == pytest.approx(-40.5)
    assert float(result["small"]["value"]) == pytest.approx(1e-12)
    assert result["empty"] == {"category": "temperature", "from": "f", "to": "c", "value": ""}
    assert result["large"] == {"category": "length", "from": "cm", "to": "m", "value": "1000000000000001"}


@needs_node
def test_saved_state_is_validated_without_reusing_unknown_fields():
    result = run_model("""
const defaults = [null, [], 'garbage', 3, {}, {category: '__proto__'}, {category: 'currency'}].map(m.normalizeState);
const repaired = ['NaN', 'Infinity', '1,2.3', '1e999', '9'.repeat(1000), {}, 12].map(value =>
  m.normalizeState({category: 'mass', from: 'm', to: '__proto__', value, unexpected: true}));
const valid = ['', '-', ',', '-,', '-12,5'].map(value =>
  m.normalizeState({category: 'temperature', from: 'k', to: 'c', value}));
console.log(JSON.stringify({defaults, repaired, valid}));
""")
    assert all(state == {"category": "length", "from": "m", "to": "cm", "value": "1"} for state in result["defaults"])
    assert all(state == {"category": "mass", "from": "kg", "to": "lb", "value": "1"} for state in result["repaired"])
    assert [state["value"] for state in result["valid"]] == ["", "-", ",", "-,", "-12,5"]
    assert all(state["from"] == "k" and state["to"] == "c" for state in result["valid"])


@needs_node
def test_swap_keeps_invalid_live_edits_and_full_result_precision():
    # Swap must not turn a mistyped value into 1 or round an offset away.
    result = run_model("""
const invalid = ['1e-', '1e', '1000000000000001'].map(value => {
  const state = {category: 'temperature', from: 'c', to: 'f', value};
  return {value, swapped: m.swapState(state)};
});
const tiny = {category: 'temperature', from: 'c', to: 'f', value: '1e-12'};
const large = {category: 'mass', from: 'kg', to: 'kg', value: '999999999999999'};
const converted = m.convert(tiny.category, tiny.from, tiny.to, tiny.value);
console.log(JSON.stringify({invalid, tiny: m.swapState(tiny), converted,
  large: m.swapState(large), twice: m.swapState(m.swapState(tiny))}));
""")
    for entry in result["invalid"]:
        assert entry["swapped"] == {
            "category": "temperature", "from": "f", "to": "c", "value": entry["value"],
        }
    assert float(result["tiny"]["value"]) == result["converted"]["value"]
    assert result["large"]["value"] == "999999999999999"
    assert float(result["twice"]["value"]) == pytest.approx(1e-12, abs=5e-15, rel=0)


@needs_node
def test_absolute_zero_is_exact_in_both_fahrenheit_kelvin_directions():
    # An unnecessary Celsius intermediate otherwise displays negative Kelvin.
    result = run_model("""
console.log(JSON.stringify({
  kelvin: m.convert('temperature', 'f', 'k', '-459.67'),
  fahrenheit: m.convert('temperature', 'k', 'f', '0'),
  twice: m.swapState(m.swapState({category: 'temperature', from: 'k', to: 'f', value: '0'}))
}));
""")
    assert result["kelvin"] == {"ok": True, "value": 0}
    assert result["fahrenheit"] == {"ok": True, "value": -459.67}
    assert result["twice"]["value"] == "0"


@needs_node
def test_keypad_builds_negative_decimal_and_clear_removes_result():
    result = run_model("""
let value = '';
for (const key of ['sign', '4', '0', ',', '5', '.']) value = m.editValue(value, key);
const back = m.editValue(value, 'backspace');
const clear = m.editValue(value, 'clear');
console.log(JSON.stringify({value, back, clear, noResult: m.convert('temperature', 'c', 'f', clear),
  leading: m.editValue('-0', '3'), decimal: m.editValue('-', ','),
  scientific: m.editValue('1e-12', '2'), unchanged: m.editValue('12', 'unknown'),
  limit: m.editValue('1'.repeat(m.MAX_INPUT_LENGTH), '2').length,
  signLimit: m.editValue('1'.repeat(m.MAX_INPUT_LENGTH), 'sign').length}));
""")
    assert result["value"] == "-40,5"
    assert result["back"] == "-40,"
    assert result["clear"] == ""
    assert result["noResult"] == {"ok": False, "error": "empty"}
    assert result["leading"] == "-3"
    assert result["decimal"] == "-0,"
    assert result["scientific"] == "2"
    assert result["unchanged"] == "12"
    assert result["limit"] == 32
    assert result["signLimit"] == 32


@needs_node
def test_model_loads_as_a_browser_global_without_dom_and_formats_finite_values():
    result = run_model(f"""
const fs = require('node:fs'), vm = require('node:vm');
const context = {{}};
vm.createContext(context);
vm.runInContext(fs.readFileSync({json.dumps(str(PACKAGE / 'model.js'))}, 'utf8'), context);
console.log(JSON.stringify({{browser: context.UnitConverter.convert('length', 'm', 'cm', '2'),
  formats: [-0, 1 / 3, 1e-12, Infinity, NaN].map(m.formatNumber)}}));
""")
    assert result["browser"] == {"ok": True, "value": 200}
    assert result["formats"] == ["0", "0.333333333333", "1e-12", "", ""]


@needs_node
@pytest.mark.parametrize("storage_mode", ["corrupt", "denied", "quota"])
def test_real_converter_ui_survives_storage_failures_and_isolates_picker(storage_mode):
    # Run the shipped handlers: model-only checks miss broken startup or modal
    # focus that leaves the keypad active underneath the picker.
    result = run_model(f"""
const fs = require('node:fs'), vm = require('node:vm'), assert = require('node:assert/strict');
const html = fs.readFileSync({json.dumps(str(PACKAGE / 'index.html'))}, 'utf8');
const mode = {json.dumps(storage_mode)}, changes = [], records = new Map();
if (mode === 'corrupt') records.set('unit-converter.state', '{{broken');
let document;
class Element {{
  constructor() {{ this.children = []; this.attributes = {{}}; this.listeners = {{}}; this.inert = false; }}
  setAttribute(key, value) {{ this.attributes[key] = String(value); }}
  addEventListener(type, callback) {{ (this.listeners[type] ||= []).push(callback); }}
  appendChild(child) {{ this.children.push(child); }}
  replaceChildren() {{ this.children = []; }}
  focus() {{ document.activeElement = this; }}
  click() {{ for (const callback of this.listeners.click || []) callback({{}}); }}
}}
const main = new Element(), elements = {{}};
for (const match of html.matchAll(/\\bid="([^"]+)"/g)) elements[match[1]] = new Element();
document = {{documentElement: {{}}, listeners: {{}}, querySelectorAll: () => [],
  querySelector: selector => selector === 'main' ? main : null,
  getElementById: id => elements[id], createElement: () => new Element(),
  addEventListener(type, callback) {{ (this.listeners[type] ||= []).push(callback); }}
}};
const context = {{document, UnitConverter: m, BotApp: {{lang: 'en', onChange: callback => changes.push(callback)}}}};
context.window = context;
Object.defineProperty(context, 'localStorage', {{get() {{
  if (mode === 'denied') throw Error('denied');
  return {{getItem: key => records.get(key) ?? null, setItem(key, value) {{
    if (mode === 'quota') throw Error('quota'); records.set(key, value);
  }}}};
}}}});
vm.createContext(context);
const execute = code => vm.runInContext(code, context);
execute(html.match(/<script>([\\s\\S]*?)<\\/script>/)[1]);
assert.equal(elements.result.textContent, '100');
execute("press('clear'); press('2'); press('decimal'); press('5')");
assert.equal(elements.result.textContent, '250');
execute("openPicker('category')");
assert.equal(main.inert, true);
const before = execute('state.value');
const key = {{key: '7', preventDefault() {{ this.prevented = true; }}, stopPropagation() {{}}}};
for (const handler of document.listeners.keydown) handler(key);
assert.equal(execute('state.value'), before);
elements.choices.children[2].click();
assert.equal(execute('state.category'), 'temperature');
assert.equal(elements.result.textContent, '36.5');
assert.equal(main.inert, false);
assert.equal(document.activeElement, elements.category);
execute("openPicker('from')");
changes[0]({{lang: 'uk'}});
assert.equal(document.documentElement.lang, 'uk');
assert.equal(elements.choices.children[2].textContent, execute("t('unit.k')"));
elements.choices.children[2].click();
assert.equal(execute('state.from'), 'k');
assert.equal(elements.result.textContent, '-455,17');
assert.equal(main.inert, false);
assert.equal(document.activeElement, elements.from);
console.log(JSON.stringify({{state: execute('state'), title: document.title, localizedTitle: execute("t('title')"),
  saved: mode === 'corrupt' ? JSON.parse(records.get('unit-converter.state')) : null}}));
""")
    assert result["state"] == {"category": "temperature", "from": "k", "to": "f", "value": "2.5"}
    assert result["title"] == result["localizedTitle"]
    if storage_mode == "corrupt":
        assert result["saved"] == result["state"]


class VisibleTextParser(HTMLParser):
    def __init__(self):
        super().__init__()
        self.ignored = 0
        self.text = []
        self.literal_attributes = []

    def handle_starttag(self, tag, attrs):
        if tag in {"script", "style"}:
            self.ignored += 1
        for key, value in attrs:
            if key in {"title", "placeholder", "aria-label"} and value:
                self.literal_attributes.append(value)

    def handle_endtag(self, tag):
        if tag in {"script", "style"}:
            self.ignored -= 1

    def handle_data(self, data):
        if not self.ignored and data.strip():
            self.text.append(data.strip())


@needs_node
def test_every_category_unit_and_ui_label_has_both_translations():
    html = (PACKAGE / "index.html").read_text("utf-8")
    dictionary = re.search(r"const STR = (\{.*?\n  \});", html, re.S).group(1)
    translations = run_model(f"console.log(JSON.stringify({dictionary}));")
    assert translations["uk"].keys() == translations["en"].keys()
    categories = run_model("console.log(JSON.stringify(m.categories));")
    required = set(re.findall(r'data-i18n(?:-aria|-title)?="([^"]+)"', html)) | set(categories)
    for definition in categories.values():
        for unit in definition["units"]:
            required.update({f"unit.{unit}", f"symbol.{unit}"})
    for language in ("uk", "en"):
        assert all(translations[language].get(key) for key in required)
    parser = VisibleTextParser()
    parser.feed(html)
    assert parser.text == [], "Static visible text must be filled from dictionaries"
    assert parser.literal_attributes == [], "Titles and accessible names need translation keys"
    picker = re.search(r'<section id="picker"([^>]*)>', html).group(1)
    assert 'role="dialog"' in picker and 'aria-modal="true"' in picker
    assert 'aria-labelledby="picker-title"' in picker
    manifest = json.loads((PACKAGE / "package.json").read_text("utf-8"))
    assert manifest["id"] == PACKAGE.name
    assert not re.search("[Ѐ-ӿ]", manifest["label"] + manifest["description"] + manifest["author"])
    assert all(manifest["locales"][language][field] for language in ("uk", "en") for field in ("label", "description"))
