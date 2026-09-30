/* Activity discovery for the small device screen. Content is locale keys;
   destinations are an allowlist, never a URL supplied by a package. */
export const ACTIONS = {
  checklist: { pkg: "daily-checklist", icon: "list", connection: "offline" },
  weather: { screen: "weather", icon: "sun", connection: "internet" },
  clock: { pkg: "clock", icon: "clock", connection: "offline" },
  focus: { pkg: "pomodoro", icon: "timer", connection: "offline" },
  timer: { screen: "timer", icon: "timer", connection: "server" },
  convert: { pkg: "unit-converter", icon: "sliders", connection: "offline" },
  calculate: { pkg: "calculator", icon: "calc", connection: "offline" },
  breathe: { pkg: "breathe", icon: "leaf", connection: "offline" },
  reaction: { pkg: "reaction", icon: "target", connection: "offline" },
  paint: { pkg: "pixel-paint", icon: "pencil", connection: "offline" },
  rhythm: { pkg: "metronome", icon: "clock", connection: "offline" },
  music: { pkg: "yt-music", icon: "music", connection: "internet" },
  video: { pkg: "youtube", icon: "youtube", connection: "internet" },
  chat: { screen: "chat", icon: "bubble", connection: "server" },
  memory: { screen: "memory", icon: "memory", connection: "server" },
  status: { screen: "state", icon: "gauge", connection: "server" },
  settings: { screen: "settings", icon: "settings", connection: "offline" },
  services: { screen: "services", icon: "server", connection: "server" },
};

export const SCENARIOS = [
  { id: "morning", icon: "sun", actions: ["checklist", "weather", "clock"] },
  { id: "work", icon: "timer", actions: ["focus", "checklist"] },
  { id: "kitchen", icon: "calc", actions: ["timer", "convert", "calculate"] },
  { id: "break", icon: "leaf", actions: ["breathe", "reaction"] },
  { id: "create", icon: "pencil", actions: ["paint", "rhythm"] },
  { id: "media", icon: "music", actions: ["music", "video"] },
  { id: "learn", icon: "memory", actions: ["chat", "memory"] },
  { id: "device", icon: "settings", actions: ["status", "settings", "services"] },
];

/** A failed catalogue is different from an app that is absent from it. */
export function actionState(action, packages) {
  if (action.screen) return "ready";
  if (!Array.isArray(packages)) return "unknown";
  const pkg = packages.find((p) => p?.id === action.pkg && p.type === "app");
  return pkg ? (pkg.installed ? "ready" : "install") : "unavailable";
}

/** Use the real response contract; an HTML error page is not an empty store. */
export async function loadGuideCatalog(fetcher = fetch) {
  const response = await fetcher("/api/screen-store/catalog");
  if (!response.ok) throw new Error("catalog_http");
  const data = await response.json();
  if (!Array.isArray(data?.packages)) throw new Error("catalog_shape");
  return data.packages;
}

export function mountGuide(box, deps) {
  const { t, icon } = deps;
  const session = deps.state || {};
  const pending = session.pending;
  const root = document.createElement("div");
  root.className = "guide";
  // Arrow keys scroll Guide; the carousel's global shortcuts are underneath it.
  root.addEventListener("keydown", (event) => {
    if (["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight"].includes(event.key)) event.stopPropagation();
  });
  box.appendChild(root);
  let selected = SCENARIOS.find((scenario) => scenario.id === session.scenario) || null;
  session.scenario = selected?.id || null;
  let packages = null;
  let loading = false;
  let busy = Boolean(pending);
  let error = "";
  let revision = 0;

  function text(tag, className, value) {
    const el = document.createElement(tag);
    el.className = className;
    el.textContent = value;
    return el;
  }

  function button(className, label, onClick) {
    const el = text("button", className, label);
    el.type = "button";
    el.addEventListener("click", onClick);
    return el;
  }

  function focusedControl() {
    return root.contains(document.activeElement) ? document.activeElement.dataset.guideFocus : null;
  }

  function restoreFocus(key) {
    if (!key || !root.isConnected) return;
    const control = root.querySelector(`[data-guide-focus="${key}"]`);
    const target = control && !control.disabled ? control :
      root.querySelector('[role="status"]') || root.querySelector("button:not(:disabled)");
    target?.focus({ preventScroll: true });
  }

  function render(focusKey = focusedControl()) {
    root.replaceChildren();
    if (!selected) {
      root.appendChild(text("p", "guide-intro", t("guide.intro")));
      const grid = document.createElement("div");
      grid.className = "guide-grid";
      for (const scenario of SCENARIOS) {
        const card = button("guide-card", "", () => {
          selected = scenario;
          session.scenario = scenario.id;
          error = "";
          revision++;
          render("back");
          box.scrollTop = 0;
        });
        card.dataset.scenario = scenario.id;
        card.dataset.guideFocus = "scenario-" + scenario.id;
        card.append(icon(scenario.icon), text("strong", "", t("guide." + scenario.id + ".title")),
          text("span", "", t("guide." + scenario.id + ".short")));
        grid.appendChild(card);
      }
      root.append(grid, text("p", "guide-hint", t("guide.navigation")),
        text("p", "guide-hint", t("guide.local")));
      restoreFocus(focusKey);
      return;
    }

    const scenarioId = selected.id;
    const back = button("guide-back", t("guide.back"), () => {
      selected = null;
      session.scenario = null;
      error = "";
      revision++;
      render("scenario-" + scenarioId);
      box.scrollTop = 0;
    });
    back.dataset.guideFocus = "back";
    back.disabled = busy;
    const heading = document.createElement("div");
    heading.className = "guide-heading";
    heading.append(text("h2", "guide-title", t("guide." + selected.id + ".title")), back);
    root.append(heading, text("p", "guide-intro", t("guide." + selected.id + ".body")));

    if (error || loading || busy) {
      const note = text("p", "guide-hint", error ? t(error) : t("common.loading"));
      note.setAttribute("role", "status");
      note.tabIndex = -1;
      note.dataset.guideFocus = "status";
      root.appendChild(note);
    }
    if (!loading && (packages === null || error ||
        selected.actions.some((key) => actionState(ACTIONS[key], packages) === "unavailable"))) {
      const retry = button("guide-back", t("guide.retry"), refreshCatalog);
      retry.dataset.guideFocus = "retry";
      retry.disabled = busy;
      root.appendChild(retry);
    }

    for (const key of selected.actions) {
      const action = ACTIONS[key];
      const state = actionState(action, packages);
      const row = button("guide-action", "", () => launch(action));
      row.dataset.action = key;
      row.dataset.guideFocus = "action-" + key;
      row.disabled = busy || state === "unknown" || state === "unavailable";
      const copy = document.createElement("span");
      copy.className = "guide-copy";
      copy.append(text("strong", "", t("guide.action." + key)),
        text("span", "guide-description", t("guide.action." + key + ".body")),
        text("span", "guide-connection", t("guide.connection." + action.connection)));
      const label = state === "ready" ? "store.open" : state === "install" ? "guide.getOpen" :
        state === "unknown" && loading ? "common.loading" : "guide.unavailable";
      row.append(icon(action.icon), copy, text("span", "guide-launch", t(label)));
      root.appendChild(row);
    }
    restoreFocus(focusKey);
  }

  async function refreshCatalog() {
    if (loading) return;
    const focusKey = focusedControl();
    loading = true;
    error = "";
    render();
    try { packages = await (deps.catalog || loadGuideCatalog)(); }
    catch (_) { packages = null; error = "guide.catalogFailed"; }
    loading = false;
    if (root.isConnected) render(focusedControl() === "status" ? focusKey : focusedControl());
  }

  async function launch(action) {
    if (busy || session.pending) return;
    const state = actionState(action, packages);
    if (state !== "ready" && state !== "install") return;
    if (action.screen) { deps.onScreen(action.screen); return; }
    busy = true;
    error = "";
    const focusKey = focusedControl();
    const at = revision;
    const active = () => root.isConnected && at === revision;
    // A language rebuild shares this operation so it cannot install twice.
    const operation = Promise.resolve().then(() => deps.onPackage(action.pkg, state === "install", active));
    session.pending = operation;
    render();
    try {
      // Installation may finish after the person swipes home. Persist it,
      // but never reopen an app over whatever they chose to do next.
      await operation;
    } catch (_) { error = "guide.launchFailed"; }
    if (session.pending === operation) session.pending = null;
    busy = false;
    if (active()) render(focusedControl() === "status" ? focusKey : focusedControl());
  }

  render(selected ? "back" : "scenario-" + SCENARIOS[0].id);
  const catalogReady = refreshCatalog();
  if (pending) {
    const resume = async (failed) => {
      await catalogReady;
      if (!root.isConnected) return;
      busy = false;
      packages = null;
      await refreshCatalog();
      if (failed && root.isConnected) {
        error = "guide.launchFailed";
        render();
      }
    };
    pending.then(() => resume(false), () => resume(true));
  }
}
