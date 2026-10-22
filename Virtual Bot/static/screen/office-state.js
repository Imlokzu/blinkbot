import { ROOMS } from './office-world.js';

/** Time-driven office behavior, independent of the DOM and transport. */
export class OfficeState {
  constructor(spots, random = Math.random) {
    this.spots = spots;
    this.random = random;
    this.room = 'work';
    this.position = { ...spots.work.point };
    this.destination = { ...spots.work.point };
    this.route = [];
    this.target = 'work';
    this.pose = 'idle';
    this.activity = 'idle';
    this.active = false;
    this.hurry = false;
    this.connected = false;
    this.emotion = 'idle';
    this.reduced = false;
    this.sleeping = false;
    this.phase = 0;
    this.hold = 8;
    this.elapsed = 0;
    this.idleIndex = 0;
  }

  physicalRoom() {
    for (const [id, rect] of Object.entries(ROOMS)) {
      if (this.position.x >= rect.x && this.position.x <= rect.x + rect.w
          && this.position.y >= rect.y && this.position.y <= rect.y + rect.h) return id;
    }
    return 'corridor';
  }

  go(room) {
    const to = this.spots[room];
    const destination = room === 'library' && !this.active ? to.leisure : to.point;
    // Already on the way there: keep walking. A new spot in the same room
    // (the chair for leisure, the cart for a memory job) still replans.
    if (this.target === room && this.route.length
        && this.destination.x === destination.x && this.destination.y === destination.y) return;
    this.target = room;
    const current = this.physicalRoom();
    const from = this.spots[current];
    this.destination = { ...destination };
    // The feet lane is below the corridor's center so the entire 40x33
    // sprite clears both walls. Rerouting uses physical position, not the
    // destination of the previous walk.
    const lane = 232;
    this.route = current === room ? [
      { x: to.door.x, y: this.position.y },
      { x: to.door.x, y: destination.y }, { ...destination },
    ] : [
      ...(from ? [{ x: from.door.x, y: this.position.y }, { x: from.door.x, y: lane }]
        : [{ x: this.position.x, y: lane }]),
      { x: to.door.x, y: lane }, { x: to.door.x, y: destination.y }, { ...destination },
    ];
    this.pose = 'walk';
    if (this.reduced) this.arrive();
  }

  arrive() {
    this.position = { ...this.destination };
    this.room = this.target;
    this.hurry = false;
    this.route = [];
    this.pose = this.sleeping && this.room === 'sleep' ? 'sleep' :
      this.active ? this.activity : this.room === 'kitchen' ? 'eat' :
      this.room === 'library' ? 'leisure' : 'idle';
    this.hold = 10 + this.random() * 7;
  }

  wakeUp() {
    if (this.pose === 'waking' || this.pose === 'hop') return;
    this.sleeping = false;
    this.route = [];
    if (this.reduced) { this.go('work'); return; }
    this.pose = 'waking';
    this.phase = 0.65;
  }

  setActivity(value = {}) {
    const allowed = ['idle', 'work', 'read', 'organize', 'chat', 'help'];
    const next = allowed.includes(value.activity) ? value.activity : 'idle';
    const active = value.active === true && next !== 'idle';
    if (next === this.activity && active === this.active) return;
    this.activity = next;
    this.active = active;
    if (active) this.hurry = true;
    if (active || next === 'help') {
      const room = next === 'read' || next === 'organize' ? 'library' : next === 'chat' ? 'chat' : 'work';
      if (this.sleeping || this.pose === 'sleep') this.wakeUp();
      if (this.pose === 'waking' || this.pose === 'hop') { this.target = room; return; }
      this.go(room);
      if (next === 'help') this.hold = 8;
    } else if (this.target === 'library' && this.route.length) {
      this.route = [];
      this.go('library');
    } else if (!this.route.length && !['waking', 'hop', 'sleep'].includes(this.pose)) {
      this.pose = 'idle';
      this.hold = 5;
    }
  }

  setSleeping(value) {
    if (value) {
      if (this.active) return;
      this.sleeping = true;
      this.go('sleep');
    } else if (this.sleeping || this.pose === 'sleep') {
      this.target = 'work';
      this.wakeUp();
    }
  }

  setConnected(value) {
    this.connected = !!value;
    if (!value) {
      this.active = false;
      this.activity = 'idle';
      if (!this.sleeping) { this.pose = 'idle'; this.route = []; this.hold = 5; }
    }
  }

  setReducedMotion(value) {
    this.reduced = !!value;
    if (value && ['waking', 'hop'].includes(this.pose)) this.go(this.target);
    if (value && this.route.length) this.arrive();
  }

  step(dt) {
    dt = Math.max(0, Math.min(0.1, Number(dt) || 0));
    this.elapsed += dt;
    if (this.pose === 'waking' || this.pose === 'hop') {
      this.phase -= dt;
      if (this.phase <= 0) {
        if (this.pose === 'waking') { this.pose = 'hop'; this.phase = 0.35; }
        else this.go(this.target);
      }
      return;
    }
    if (this.route.length) {
      if (this.reduced) { this.arrive(); return; }
      const point = this.route[0];
      const dx = point.x - this.position.x, dy = point.y - this.position.y;
      const distance = Math.hypot(dx, dy), step = dt * (this.active || this.hurry ? 120 : 48);
      if (distance <= step) {
        this.position = { ...point };
        this.route.shift();
        this.room = this.physicalRoom();
        if (!this.route.length) this.arrive();
      } else {
        this.position.x += dx / distance * step;
        this.position.y += dy / distance * step;
      }
      return;
    }
    if (this.active || this.sleeping) return;
    this.hold -= dt;
    if (this.hold <= 0 && !this.reduced) {
      const rooms = ['kitchen', 'chat', 'library'];
      this.activity = 'idle';
      this.go(rooms[this.idleIndex++ % rooms.length]);
    }
  }

  get status() {
    if (!this.connected) return 'offline';
    if (this.pose === 'waking' || this.pose === 'hop') return 'waking';
    if (this.pose === 'sleep') return 'sleep';
    if (this.activity === 'help') return 'help';
    if (this.active) return this.activity;
    // A stroll is announced as such; a job on the way is already the job
    if (this.route.length) return 'moving';
    if (this.pose === 'leisure') return 'leisure';
    if (this.pose === 'eat') return 'eat';
    return 'idle';
  }
}
