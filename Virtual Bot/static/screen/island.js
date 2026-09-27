/* ============================================================
   The island: what is going on right now, in a pill at the top

   Like the Dynamic Island on iOS. Timers, music (and a video that went on
   as sound when its app closed) each become an "activity". The pill shows
   the most important one, plus a dot for a second one; a tap opens it with
   controls, and a selector when there is more than one.

   This module decides only WHAT to show and in which order. It has no DOM,
   so the rules can be run straight from node (tests/test_island_js.py).
   ============================================================ */

/**
 * Which activities exist right now, most important first.
 *
 * state = {
 *   timers:  [{id, label, kind, state, left}]  — `left` in seconds, already
 *            computed by the caller (it owns the clock skew)
 *   ringing: true while a finished timer is sounding
 *   music:   {id, title, uploader, provider, playing, position, duration,
 *             fromVideo} | null
 *   openApp: package id of the app in front ("yt-music", "youtube"), or ""
 * }
 *
 * Each activity: {key, kind: "timer"|"ring"|"music"|"radio"|"video", ...}.
 * An activity is hidden while its own app is in front — that app already
 * shows it, and the island would only repeat it (iOS does the same).
 */
export function activities(state) {
  const s = state || {};
  const out = [];
  const open = s.openApp || "";

  if (s.ringing) out.push({ key: "ring", kind: "ring" });

  const m = s.music;
  if (m && m.id) {
    let kind = "music";
    let app = "yt-music";
    if (m.provider === "radio") { kind = "radio"; app = ""; }
    else if (m.fromVideo) { kind = "video"; app = "youtube"; }
    if (!app || open !== app) {
      out.push({
        key: kind + ":" + m.id, kind, app,
        id: m.id, title: m.title || "", subtitle: m.uploader || "",
        playing: !!m.playing, position: Number(m.position) || 0,
        duration: Number(m.duration) || 0, cover: m.cover || "",
      });
    }
  }

  const live = (s.timers || []).filter((tm) => tm && tm.kind !== "reminder" &&
    (tm.state === "paused" || (tm.state === "running" && tm.left > 0)));
  // Running before paused, then the one that ends first: what is about to
  // happen matters more than what waits.
  live.sort((a, b) => (a.state === "paused") - (b.state === "paused") || a.left - b.left);
  for (const tm of live) {
    out.push({
      key: "timer:" + tm.id, kind: "timer", id: tm.id,
      title: tm.label || "", left: tm.left, paused: tm.state === "paused",
    });
  }

  // Rank by what is moving: a ringing timer, then whatever runs or plays,
  // then what is paused. Ties keep the order above (music before timers).
  const rank = (a) => (a.kind === "ring" ? 0
    : a.kind === "timer" && !a.paused ? 1
    : (a.kind === "music" || a.kind === "video" || a.kind === "radio") && a.playing ? 1
    : 2);
  const order = out.map((a, i) => [rank(a), i, a]);
  order.sort((x, y) => x[0] - y[0] || x[1] - y[1]);
  return order.map((x) => x[2]);
}

/**
 * The activity the open island shows. The person's pick survives updates
 * (a timer ticking must not yank them back to the music); when the pick is
 * gone, fall back to the first.
 */
export function selectKey(list, previous) {
  if (!list.length) return "";
  if (previous && list.some((a) => a.key === previous)) return previous;
  return list[0].key;
}

/** Seconds -> "4:05" / "1:02:03": short, for a pill 150 px wide. */
export function fmtClock(sec) {
  const s = Math.max(0, Math.ceil(Number(sec) || 0));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, "0");
  return h ? h + ":" + String(m).padStart(2, "0") + ":" + ss : m + ":" + ss;
}
