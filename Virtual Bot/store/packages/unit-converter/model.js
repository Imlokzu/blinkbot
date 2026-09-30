(function (root, factory) {
  "use strict";
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.UnitConverter = factory();
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  // Factors use SI bases; gallons and fluid ounces are explicitly US units.
  var categories = {
    length: { defaults: ["m", "cm"], units: { mm: 0.001, cm: 0.01, m: 1, km: 1000, inch: 0.0254, foot: 0.3048, yard: 0.9144, mile: 1609.344 } },
    mass: { defaults: ["kg", "lb"], units: { mg: 0.000001, g: 0.001, kg: 1, tonne: 1000, oz: 0.028349523125, lb: 0.45359237 } },
    temperature: { defaults: ["c", "f"], units: { c: 1, f: 1, k: 1 } },
    volume: { defaults: ["l", "ml"], units: { ml: 0.001, l: 1, m3: 1000, us_fl_oz: 0.0295735295625, us_gal: 3.785411784 } },
    speed: { defaults: ["kmh", "mph"], units: { ms: 1, kmh: 1 / 3.6, mph: 0.44704, knot: 1852 / 3600 } }
  };
  Object.keys(categories).forEach(function (id) {
    Object.freeze(categories[id].defaults);
    Object.freeze(categories[id].units);
    Object.freeze(categories[id]);
  });
  Object.freeze(categories);
  var MAX_VALUE = 1e15;
  var MAX_INPUT_LENGTH = 32;
  var decimal = /^[+-]?(?:\d+(?:[.,]\d*)?|[.,]\d+)(?:e[+-]?\d+)?$/i;
  var partial = /^(?:|-|[.,]|-[.,])$/;
  function owns(object, key) { return typeof key === "string" && Object.prototype.hasOwnProperty.call(object, key); }
  function failure(error) { return { ok: false, error: error }; }

  function parseDecimal(input) {
    if (typeof input !== "string" && typeof input !== "number") return failure("number");
    var text = String(input).trim();
    if (!text) return failure("empty");
    if (text.length > MAX_INPUT_LENGTH) return failure("range");
    if (!decimal.test(text)) return failure("number");
    var value = Number(text.replace(",", "."));
    // Reject overflow and underflow rather than reporting a nonzero value as zero.
    if (!Number.isFinite(value) || Math.abs(value) > MAX_VALUE ||
        (value === 0 && /[1-9]/.test(text.split(/[eE]/)[0]))) return failure("range");
    return { ok: true, value: value === 0 ? 0 : value };
  }

  function convert(category, from, to, input) {
    if (!owns(categories, category)) return failure("units");
    var units = categories[category].units;
    if (!owns(units, from) || !owns(units, to)) return failure("units");
    var parsed = parseDecimal(input);
    if (!parsed.ok) return parsed;
    var value = parsed.value, result = value;
    if (from !== to) {
      if (category === "temperature") {
        // Direct pairs avoid losing tiny same-unit values in an offset roundtrip.
        if (from === "c") result = to === "f" ? value * 9 / 5 + 32 : value + 273.15;
        else if (from === "f") result = to === "c" ? (value - 32) * 5 / 9 : (value + 459.67) * 5 / 9;
        else result = to === "c" ? value - 273.15 : value * 9 / 5 - 459.67;
      } else result = value * (units[from] / units[to]);
    }
    if (!Number.isFinite(result) || Math.abs(result) > MAX_VALUE || (result === 0 && value !== 0 && category !== "temperature")) return failure("range");
    return { ok: true, value: result === 0 ? 0 : result };
  }

  function formatNumber(value) {
    if (!Number.isFinite(value) || Math.abs(value) > MAX_VALUE) return "";
    // Twelve significant digits keep the tiny display readable.
    return String(Number(value.toPrecision(12)));
  }

  function defaultState(category) {
    var id = owns(categories, category) ? category : "length";
    return { category: id, from: categories[id].defaults[0], to: categories[id].defaults[1], value: "1" };
  }

  function normalizeState(saved) {
    if (!saved || typeof saved !== "object" || Array.isArray(saved) || !owns(categories, saved.category)) return defaultState();
    var state = defaultState(saved.category), units = categories[state.category].units;
    if (owns(units, saved.from)) state.from = saved.from;
    if (owns(units, saved.to)) state.to = saved.to;
    if (typeof saved.value === "string" && saved.value.length <= MAX_INPUT_LENGTH) {
      var value = saved.value.trim();
      if (partial.test(value) || parseDecimal(value).ok) state.value = value;
    }
    return state;
  }

  function swapState(saved) {
    var state = normalizeState(saved);
    // Live edits may be invalid, including a partially deleted exponent.
    // Keep them visible instead of silently converting the default value 1.
    if (saved && saved.category === state.category && saved.from === state.from && saved.to === state.to &&
        typeof saved.value === "string" && saved.value.length <= MAX_INPUT_LENGTH) state.value = saved.value;
    var converted = convert(state.category, state.from, state.to, state.value);
    return { category: state.category, from: state.to, to: state.from,
      // Display rounding must not erase significant digits from the next input.
      value: converted.ok ? String(converted.value) : state.value };
  }

  function editValue(input, key) {
    var value = typeof input === "string" ? input : "";
    if (key === "clear") return "";
    if (key === "backspace") return value.slice(0, -1);
    if (key === "sign") return value.charAt(0) === "-" ? value.slice(1) : value.length < MAX_INPUT_LENGTH ? "-" + value : value;
    if (!/^(?:\d|[.,])$/.test(key)) return value;
    // A new digit after a scientific result starts a fresh keypad entry.
    if (/[eE]/.test(value)) value = "";
    if (key === "." || key === ",") {
      if (/[.,]/.test(value)) return value;
      if (!value || value === "-") value += "0";
    } else if (value === "0" || value === "-0") value = value.charAt(0) === "-" ? "-" : "";
    return value.length < MAX_INPUT_LENGTH ? value + key : value;
  }

  return Object.freeze({ categories: categories, MAX_VALUE: MAX_VALUE, MAX_INPUT_LENGTH: MAX_INPUT_LENGTH,
    parseDecimal: parseDecimal, convert: convert, formatNumber: formatNumber,
    defaultState: defaultState, normalizeState: normalizeState, swapState: swapState, editValue: editValue });
});
