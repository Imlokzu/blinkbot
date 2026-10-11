/* Two short voice cues, prepared while listening starts. Changing the voice,
   speed or language invalidates them; cancelled downloads cannot refill them. */
export class WakeCueCache {
  constructor(synthesize) {
    this.synthesize = synthesize;
    this.scope = "";
    this.audio = new Map();
    this.pending = new Map();
    this.controller = null;
  }

  prepare(scope, phrases, speed) {
    if (scope !== this.scope) {
      this.cancel();
      this.audio.clear();
      this.scope = scope;
    }
    if (!this.controller || this.controller.signal.aborted) this.controller = new AbortController();
    const controller = this.controller;
    return Promise.all(phrases.map((text) => {
      if (this.audio.has(text)) return this.audio.get(text);
      if (this.pending.has(text)) return this.pending.get(text);
      const request = Promise.resolve().then(() => {
        if (controller.signal.aborted) return null;
        return this.synthesize(text, controller.signal, speed);
      }).catch(() => null).then((blob) => {
        if (this.controller === controller && !controller.signal.aborted) {
          if (blob) this.audio.set(text, blob);
          this.pending.delete(text);
        }
        return controller.signal.aborted ? null : blob;
      });
      this.pending.set(text, request);
      return request;
    }));
  }

  get(scope, text) {
    return scope === this.scope ? (this.audio.get(text) || this.pending.get(text) || null) : null;
  }

  cancel() {
    if (this.controller) this.controller.abort();
    this.controller = null;
    this.pending.clear();
  }
}
