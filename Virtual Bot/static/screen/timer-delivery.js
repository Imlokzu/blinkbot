/** Alarm delivery belongs to an activation, rather than permanently to a timer ID. */
export class TimerDeliveries {
  constructor(limit = 512) {
    this.limit = limit;
    this.records = new Map();
    this.occurrences = new Map();
  }

  activation(timer) {
    // Legacy snapshots agree with the server's persisted migration generation.
    return Number.isInteger(timer.activation) && timer.activation > 0 ? timer.activation : 1;
  }

  receive(timer) {
    const activation = this.activation(timer);
    let record = this.records.get(timer.id);
    if (record && activation < record.activation) return record.timer;
    if (!record || activation > record.activation) record = { activation, delivered: false };
    record.timer = timer;
    if (timer.state === "done") {
      record.delivered = true;
      this.rememberOccurrence(timer);
    }
    // Refresh insertion order for bounded retired-history retention.
    this.records.delete(timer.id);
    this.records.set(timer.id, record);
    return timer;
  }

  snapshot(list) {
    const timers = list.map(timer => this.receive(timer));
    const current = new Set(timers.map(timer => timer.id));
    let retired = this.records.size - current.size;
    for (const [id] of this.records) {
      if (retired <= this.limit) break;
      if (!current.has(id)) { this.records.delete(id); retired--; }
    }
    // Keep every current fence, including done history. Only retired IDs are bounded.
    return timers;
  }

  older(timer) {
    const record = this.records.get(timer.id);
    return !!record && this.activation(timer) < record.activation;
  }

  delivered(timer) {
    const record = this.records.get(timer.id);
    return !!record && (this.activation(timer) < record.activation || record.delivered);
  }

  mark(timer) {
    this.rememberOccurrence(timer);
    // A fresh due event can arrive after its NEXT snapshot. Acknowledging
    // that old occurrence must not mark the new countdown as delivered.
    if (this.older(timer)) return;
    this.receive(timer);
    this.records.get(timer.id).delivered = true;
  }

  rememberOccurrence(timer) {
    const key = JSON.stringify([timer.id, this.activation(timer)]);
    this.occurrences.delete(key);
    this.occurrences.set(key, true);
    while (this.occurrences.size > Math.max(512, this.limit)) {
      this.occurrences.delete(this.occurrences.keys().next().value);
    }
  }

  shouldDeliverDue(timer, nowSeconds) {
    if (!timer || typeof timer.id !== "string" || !timer.id || timer.kind !== "reminder"
        || !["daily", "weekdays"].includes(timer.repeat)
        || !Number.isInteger(timer.activation) || timer.activation <= 0
        || !Number.isFinite(timer.ends_at) || !Number.isFinite(nowSeconds)) return false;
    const age = nowSeconds - timer.ends_at;
    if (age < 0 || age > 60) return false;
    const key = JSON.stringify([timer.id, timer.activation]);
    if (this.occurrences.has(key)) return false;
    const record = this.records.get(timer.id);
    return !(record && record.activation === timer.activation && record.delivered);
  }
}
