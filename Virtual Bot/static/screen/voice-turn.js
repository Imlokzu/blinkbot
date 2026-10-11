/* Speech boundaries use the echo-cancelled microphone's normalized level.
   Keep this independent of the DOM so timing and noise can be tested. */
export class VoiceTurnDetector {
  constructor() { this.reset(); }

  static isEcho(heard, spoken) {
    const words = (text) => (String(text || "").normalize("NFKC").toLowerCase()
      .match(/[\p{L}\p{N}]+/gu) || []).join(" ");
    const phrase = words(heard);
    const reply = words(spoken);
    if (!phrase || !reply) return false;
    if (phrase === reply) return true;
    // Short fragments overlap ordinary answers too easily ("yes", "I think").
    if (phrase.split(" ").length < 3) return false;
    return (" " + reply + " ").includes(" " + phrase + " ");
  }

  reset(now = 0) {
    this.noise = 0.015;
    this.candidate = null;
    this.started = null;
    this.lastVoice = now;
  }

  update(level, now) {
    const threshold = Math.max(0.06, this.noise * 3 + 0.025);
    let start = false;
    if (level > threshold) {
      if (this.candidate === null) this.candidate = now;
      this.lastVoice = now;
      if (this.started === null && now - this.candidate >= 180) {
        this.started = this.candidate;
        start = true;
      }
    } else {
      this.candidate = null;
      if (this.started === null) this.noise = this.noise * 0.98 + Math.min(level, 0.05) * 0.02;
    }
    // Longer thoughts get room for a breath; short turns finish promptly.
    const silence = this.started !== null && this.lastVoice - this.started > 6000 ? 950 : 650;
    const end = this.started !== null && now - this.lastVoice >= silence;
    return { start, end };
  }
}
