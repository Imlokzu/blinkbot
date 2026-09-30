/* Pure checklist rules: the same model runs in the screen and in Node. */
(function (root, factory) {
  "use strict";
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.ChecklistModel = factory();
}(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  var MAX_TASKS = 24;
  var MAX_TITLE = 80;
  var MAX_STORAGE = 32768;
  var STORAGE_KEY = "daily-checklist.state.v1";
  var STARTERS = {
    morning: ["water", "stretch", "plan"],
    work: ["priority", "break", "review"],
    evening: ["tidy", "tomorrow", "unwind"]
  };
  var starterKeys = new Set();
  Object.keys(STARTERS).forEach(function (name) {
    STARTERS[name].forEach(function (task) { starterKeys.add("task." + name + "." + task); });
  });

  function empty() { return { version: 1, tasks: [] }; }

  function cleanTitle(value) {
    if (typeof value !== "string") return "";
    return value.replace(/[\u0000-\u001f\u007f-\u009f]/g, " ")
      .replace(/[\u202a-\u202e\u2066-\u2069]/g, "").replace(/\s+/g, " ").trim();
  }

  function titleError(value) {
    if (!value) return "emptyTitle";
    return value.length > MAX_TITLE ? "longTitle" : null;
  }

  function result(state, tasks, error) {
    return { state: tasks ? { version: 1, tasks: tasks } : state, error: error || null };
  }

  function newId(tasks) {
    var used = new Set(tasks.map(function (task) { return task.id; }));
    for (var n = 1; n <= MAX_TASKS; n++) {
      if (!used.has("task-" + n)) return "task-" + n;
    }
  }

  function add(state, value) {
    var title = cleanTitle(value), error = titleError(title);
    if (error) return result(state, null, error);
    if (state.tasks.length >= MAX_TASKS) return result(state, null, "full");
    return result(state, state.tasks.concat({ id: newId(state.tasks), title: title, key: null, done: false }));
  }

  function edit(state, id, value) {
    var title = cleanTitle(value), error = titleError(title);
    if (error) return result(state, null, error);
    if (!state.tasks.some(function (task) { return task.id === id; })) return result(state, null, "missing");
    return result(state, state.tasks.map(function (task) {
      return task.id === id ? { id: id, title: title, key: null, done: task.done } : task;
    }));
  }

  function remove(state, id) {
    return result(state, state.tasks.filter(function (task) { return task.id !== id; }));
  }

  function toggle(state, id) {
    return result(state, state.tasks.map(function (task) {
      return task.id === id ? Object.assign({}, task, { done: !task.done }) : task;
    }));
  }

  // Reset is deliberately only completion: neither custom nor starter tasks
  // disappear at midnight, on a relaunch, or when starting another day.
  function reset(state) {
    return result(state, state.tasks.map(function (task) { return Object.assign({}, task, { done: false }); }));
  }

  function applyTemplate(state, name) {
    if (!Object.prototype.hasOwnProperty.call(STARTERS, name)) return result(state, null, "unknownTemplate");
    var used = new Set(state.tasks.map(function (task) { return task.id; }));
    var missing = STARTERS[name].map(function (part) {
      return { id: "starter-" + name + "-" + part, title: "", key: "task." + name + "." + part, done: false };
    }).filter(function (task) { return !used.has(task.id); });
    // Atomic merge: a nearly full list must not get half a starter or lose
    // someone's own tasks to make room. Edited starters retain their ids.
    if (state.tasks.length + missing.length > MAX_TASKS) return result(state, null, "full");
    var merged = result(state, state.tasks.concat(missing));
    merged.added = missing.length;
    return merged;
  }

  function progress(state) {
    var done = state.tasks.filter(function (task) { return task.done; }).length;
    var total = state.tasks.length;
    return { done: done, total: total, ratio: total ? done / total : 0 };
  }

  function decode(raw) {
    var fallback = { state: empty(), status: "corrupt" };
    if (typeof raw !== "string" || raw.length > MAX_STORAGE) return fallback;
    var data;
    try { data = JSON.parse(raw); } catch (_) { return fallback; }
    if (!data || data.version !== 1 || !Array.isArray(data.tasks)) return fallback;
    var tasks = [], used = new Set(), repaired = data.tasks.length > MAX_TASKS;
    // Capacity counts valid tasks, not corrupt rows that are skipped.
    data.tasks.forEach(function (task) {
      if (tasks.length >= MAX_TASKS) return;
      if (!task || typeof task.id !== "string" || !/^[a-z0-9_-]{1,48}$/.test(task.id) || used.has(task.id)) {
        repaired = true;
        return;
      }
      var title = cleanTitle(task.title);
      var key = starterKeys.has(task.key) ? task.key : null;
      // Only a real starter id may claim a translatable task key.
      if (key && task.id !== "starter-" + key.slice(5).replace(/\./g, "-")) key = null;
      // Recover an edited title even if a stale starter key was left beside it.
      if (key && title) key = null;
      if (!key && titleError(title)) { repaired = true; return; }
      var clean = { id: task.id, title: title, key: key, done: task.done === true };
      if (task.title !== title || task.key !== key || task.done !== clean.done) repaired = true;
      tasks.push(clean);
      used.add(task.id);
    });
    return { state: { version: 1, tasks: tasks }, status: repaired ? "recovered" : "ok" };
  }

  function load(storage) {
    try {
      var raw = storage.getItem(STORAGE_KEY);
      return raw == null ? { state: empty(), status: "ok" } : decode(raw);
    } catch (_) { return { state: empty(), status: "unavailable" }; }
  }

  function save(storage, state) {
    try {
      var raw = JSON.stringify(state);
      if (decode(raw).status !== "ok") return false;
      storage.setItem(STORAGE_KEY, raw);
      return true;
    } catch (_) { return false; }
  }

  return Object.freeze({
    MAX_TASKS: MAX_TASKS, MAX_TITLE: MAX_TITLE, MAX_STORAGE: MAX_STORAGE, STORAGE_KEY: STORAGE_KEY,
    empty: empty, cleanTitle: cleanTitle, add: add, edit: edit, remove: remove, toggle: toggle,
    reset: reset, applyTemplate: applyTemplate, progress: progress, decode: decode, load: load, save: save
  });
}));
