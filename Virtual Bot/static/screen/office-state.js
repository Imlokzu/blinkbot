import { ROOMS } from './office-world.js';
import { planRoute, routeDoors } from './office-navigation.js';

// Quiet routines spend most idle time in bed. Each stop has a purpose;
// these are ambient actions, never invented backend jobs.
export const IDLE_ROUTINES = [
  {room:'sleep',pose:'sleep',seconds:240},
  {room:'kitchen',pose:'eat',seconds:45},
  {room:'sleep',pose:'sleep',seconds:180},
  {room:'library',pose:'leisure',seconds:60},
  {room:'sleep',pose:'sleep',seconds:240},
  {room:'chat',pose:'play',point:{x:480,y:350},seconds:30},
  {room:'chat',pose:'stretch',point:{x:480,y:350},seconds:8},
  {room:'work',pose:'water',point:{x:574,y:172},seconds:12},
  {room:'sleep',pose:'sleep',seconds:240},
  {room:'chat',pose:'idle',seconds:60},
];
const WAKING = new Set(['dozing','calling','waking','hop']);

/** Behavior is local and time-driven; work itself never waits on animation. */
export class OfficeState {
  constructor(spots, random = Math.random) {
    this.spots=spots;this.random=random;
    this.room='work';this.position={...spots.work.point};this.destination={...this.position};
    this.route=[];this.doorIntents=[];this.target='work';this.pose='idle';
    this.activity='idle';this.active=false;this.hurry=false;this.connected=false;
    this.emotion='idle';this.reduced=false;this.sleeping=false;this.manualSleep=false;
    this.phase=0;this.hold=12;this.elapsed=0;this.idleIndex=0;this.routine=null;
    this.napRemaining=0;this.wakeStage=null;this.wakeVisitor=false;this.exclaim=0;
  }
  physicalRoom() {
    for(const [id,r] of Object.entries(ROOMS))if(this.position.x>=r.x&&this.position.x<=r.x+r.w&&this.position.y>=r.y&&this.position.y<=r.y+r.h)return id;
    return 'corridor';
  }
  go(room, point=null) {
    const to=this.spots[room];
    const destination=point||(room==='library'&&!this.active?to.leisure:to.point);
    if(this.target===room&&this.route.length&&this.destination.x===destination.x&&this.destination.y===destination.y)return;
    const route=planRoute(this.position,destination);
    if(route===null)return false;
    this.target=room;this.destination={...destination};
    this.route=route;this.doorIntents=routeDoors(this.position,route);
    if(!route.length){this.arrive();return true;}
    this.pose='walk';
    if(this.reduced)this.arrive();
    return true;
  }
  arrive() {
    this.position={...this.destination};this.room=this.target;this.hurry=false;
    this.route=[];this.doorIntents=[];
    this.pose=this.sleeping&&this.room==='sleep'?'sleep':this.active?this.activity:
      this.routine?.pose||(this.room==='kitchen'?'eat':this.room==='library'?'leisure':'idle');
    this.hold=this.routine?.seconds??30+this.random()*15;
    if(this.pose==='sleep'&&!this.manualSleep)this.napRemaining=this.routine?.seconds??240;
  }
  // A skipped or cancelled wake takes its shout with it: no stale "!"
  clearWake() {this.wakeStage=null;this.wakeVisitor=false;this.phase=0;this.exclaim=0;}
  wakeUp(visitor=false) {
    if(WAKING.has(this.pose))return;
    this.sleeping=false;this.manualSleep=false;this.napRemaining=0;this.routine=null;
    this.route=[];this.doorIntents=[];
    if(this.reduced){this.clearWake();this.go(this.target);return;}
    this.wakeVisitor=visitor;this.wakeStage=visitor?'doze':'stir';
    this.pose=visitor?'dozing':'waking';this.phase=visitor?1.2:.65;
  }
  visitorAtBed() {
    if(this.wakeStage!=='approach')return;
    this.pose='calling';this.wakeStage='call';this.phase=.8;
  }
  skipWake() {
    if(!WAKING.has(this.pose))return;
    this.clearWake();this.go(this.target);
    if(this.route.length)this.arrive();
  }
  setActivity(value={}) {
    const allowed=['idle','work','read','organize','chat','help'];
    const next=allowed.includes(value.activity)?value.activity:'idle';
    const active=value.active===true&&next!=='idle';
    if(next===this.activity&&active===this.active)return;
    this.activity=next;this.active=active;
    if(active||next==='help'){
      this.hurry=active;this.routine=null;
      const room=next==='read'||next==='organize'?'library':next==='chat'?'chat':'work';
      if(WAKING.has(this.pose)){this.target=room;return;}
      if(this.pose==='sleep'){
        this.target=room;
        // Foreground messages bring the visitor. Background consolidation
        // wakes quietly by itself rather than pretending the user called.
        this.wakeUp(next!=='organize');
        return;
      }
      this.sleeping=false;this.manualSleep=false;this.napRemaining=0;
      this.go(room);if(next==='help')this.hold=12;
    } else if(!WAKING.has(this.pose)){
      this.routine=null;
      if(this.target==='library'&&this.route.length){this.route=[];this.go('library');}
      else if(!this.route.length&&this.pose!=='sleep'){this.pose='idle';this.hold=20;}
    }
  }
  setSleeping(value,{manual=true}={}) {
    if(value){
      if(this.active)return;
      if(!manual&&this.sleeping)return;
      this.clearWake();this.sleeping=true;this.manualSleep=manual;
      this.routine=manual?null:{room:'sleep',pose:'sleep',seconds:240};
      this.go('sleep');
    } else if(WAKING.has(this.pose)){
      // The host wakes its display immediately after delivering the event;
      // that must not cancel the visitor's purely visual wake sequence.
      return;
    } else if(this.pose==='sleep'){
      this.target='work';this.wakeUp(false);
    } else if(this.sleeping){
      this.sleeping=false;this.manualSleep=false;this.routine=null;this.go('work');
    }
  }
  setConnected(value) {
    this.connected=!!value;
    if(!value){
      this.active=false;this.activity='idle';
      if(!this.sleeping){this.clearWake();this.pose='idle';this.route=[];this.doorIntents=[];this.hold=15;}
    }
  }
  setReducedMotion(value) {
    this.reduced=!!value;
    if(value&&WAKING.has(this.pose))this.skipWake();
    if(value&&this.route.length)this.arrive();
  }
  nextRoutine() {
    this.routine=IDLE_ROUTINES[this.idleIndex++%IDLE_ROUTINES.length];
    this.activity='idle';this.sleeping=this.routine.pose==='sleep';this.manualSleep=false;
    this.napRemaining=0;
    if(this.go(this.routine.room,this.routine.point)===false){this.routine=null;this.sleeping=false;this.hold=30;}
  }
  step(dt) {
    dt=Math.max(0,Math.min(.1,Number(dt)||0));this.elapsed+=dt;
    this.exclaim=Math.max(0,this.exclaim-dt);
    if(this.pose==='dozing'){
      this.phase-=dt;
      if(this.phase<=0){
        if(this.wakeStage==='doze'){this.wakeStage='approach';this.phase=8;}
        else this.visitorAtBed(); // Bound a missing/unreachable visitor.
      }
      return;
    }
    if(this.pose==='calling'||this.pose==='waking'||this.pose==='hop'){
      this.phase-=dt;
      if(this.phase<=0){
        if(this.pose==='calling'){this.pose='waking';this.wakeStage='startled';this.phase=.5;this.exclaim=1.6;}
        // Only the startled wake shouts; a background job gets up quietly.
        else if(this.pose!=='hop'){this.exclaim=this.wakeStage==='startled'?1.2:0;this.pose='hop';this.wakeStage='hop';this.phase=.72;}
        else {const shout=this.exclaim;this.clearWake();this.exclaim=shout;this.go(this.target);}   // the "!" fades on the way
      }
      return;
    }
    if(this.route.length){
      if(this.reduced){this.arrive();return;}
      const point=this.route[0],dx=point.x-this.position.x,dy=point.y-this.position.y;
      const distance=Math.hypot(dx,dy),step=dt*(this.active||this.hurry?120:48);
      if(distance<=step){this.position={...point};this.route.shift();if(!this.route.length)this.arrive();}
      else {this.position.x+=dx/distance*step;this.position.y+=dy/distance*step;}
      this.room=this.physicalRoom();return;
    }
    if(this.active)return;
    if(this.sleeping){
      if(!this.manualSleep&&!this.reduced){this.napRemaining-=dt;if(this.napRemaining<=0){this.sleeping=false;this.nextRoutine();}}
      return;
    }
    this.hold-=dt;
    if(this.hold<=0&&!this.reduced)this.nextRoutine();
  }
  get status() {
    if(!this.connected)return 'offline';
    if(this.pose==='dozing')return 'dozing';
    if(this.pose==='calling')return 'calling';
    if(this.pose==='waking'||this.pose==='hop')return 'waking';
    if(this.pose==='sleep')return 'sleep';
    if(this.activity==='help')return 'help';
    if(this.active)return this.activity;
    if(this.route.length)return 'moving';
    if(['leisure','eat','play','water','stretch'].includes(this.pose))return this.pose;
    return 'idle';
  }
}
