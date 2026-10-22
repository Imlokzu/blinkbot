/** Furniture contact is visual: it never delays or queues a real bot task. */
export function actionFor(state, visitor = false) {
  if (['sleep','dozing','calling'].includes(state.pose)) return 'sleep';
  if (['walk','waking','hop'].includes(state.pose)) return state.pose;
  if (state.pose === 'visit') return 'stand';
  if (['play','water','stretch'].includes(state.pose)) return state.pose;
  if (state.activity === 'help') return 'help';
  if (state.pose === 'organize') return 'sort';
  if (state.pose === 'read') return 'memory';
  if (state.pose === 'leisure') return 'read';
  if (state.pose === 'eat') return 'eat';
  if (state.pose === 'work') return 'type';
  if (state.room === 'chat') return 'lounge';
  if (state.room === 'work' && state.pose === 'idle') return 'rest';
  return visitor ? 'lounge' : 'stand';
}

export class OfficeActions {
  constructor(visitor = false) {
    this.visitor = visitor;
    this.kind = 'stand';
    this.time = 0;
    this.seat = 0;
    this.recline = 0;
  }
  update(state, dt = 0, snap = false) {
    const kind = actionFor(state, this.visitor);
    if (kind !== this.kind) { this.kind = kind; this.time = 0; }
    dt = state.reduced ? 0 : Math.max(0, Math.min(.1, Number(dt) || 0));
    this.time += dt;
    const seated = ['type','rest','read','eat','lounge'].includes(kind) ? 1 : 0;
    const lying = kind === 'sleep' ? 1 : 0;
    const approach = (value, target, duration) => snap || state.reduced ? target :
      value + Math.sign(target-value)*Math.min(Math.abs(target-value),dt/duration);
    this.seat = approach(this.seat, seated, seated ? .38 : .22);
    this.recline = approach(this.recline, lying, .4);
    const wave = kind === 'lounge' && state.visitorPresent && !state.reduced
      && this.time % 8 >= 1 && this.time % 8 < 2.2;
    return {
      kind, time:this.time, seat:this.seat, recline:this.recline, wave,
      offsetY:(state.room==='chat'&&!this.visitor?18*this.seat:0)-30*this.recline,
      snack:kind==='eat'?(Math.floor(this.time/2.4)%3===2?'sip':'bite'):null,
    };
  }
}
