"""The crab's quiet life: long naps, purposeful routines, and the wake-up
sequence a message triggers. Work itself is never delayed by any of it."""
import json
from test_screen_office import run as run_scene, SCREEN


def run(body):
    run_scene('import {IDLE_ROUTINES} from ' + json.dumps((SCREEN / 'office-state.js').as_uri()) + ';\n' + body)


def test_message_while_napping_plays_doze_visitor_call_startle_hop_then_job():
    run("""
s.setReducedMotion(true); s.setSleeping(true, {manual:false}); s.setReducedMotion(false);
assert.equal(s.pose,'sleep'); assert.equal(s.manualSleep,false);
s.setActivity({activity:'read',active:true});
// Backend state is immediate; only the picture takes its time
assert.equal(s.active,true); assert.equal(s.activity,'read'); assert.equal(s.target,'library');
assert.equal(s.pose,'dozing'); assert.equal(s.wakeStage,'doze'); assert.equal(s.wakeVisitor,true); assert.equal(s.sleeping,false);
const seen=[]; const note=()=>{const key=s.pose+'/'+s.wakeStage; if(seen.at(-1)!==key) seen.push(key);};
for(let i=0;i<25;i++){s.step(.05);note();}                 // 1.2 s of dozing (+1 step: phases flip at <= 0)
assert.equal(s.wakeStage,'approach');
for(let i=0;i<40;i++){s.step(.05);note();}                 // the visitor is still walking
s.visitorAtBed(); note();
for(let i=0;i<17;i++){s.step(.05);note();}                 // .8 s calling
assert.equal(s.wakeStage,'startled'); assert.ok(s.exclaim>1);
for(let i=0;i<11;i++){s.step(.05);note();}                 // .5 s startled
assert.equal(s.pose,'hop'); assert.ok(s.exclaim>0);
for(let i=0;i<15;i++){s.step(.05);note();}                 // .72 s hop
assert.equal(s.pose,'walk'); assert.equal(s.wakeStage,null); assert.equal(s.route.length>0,true);
assert.deepEqual(seen,['dozing/doze','dozing/approach','calling/call','waking/startled','hop/hop','walk/null']);
advance(20); assert.equal(s.room,'library'); assert.equal(s.pose,'read'); assert.equal(s.active,true);
""")


def test_missing_visitor_is_bounded_by_the_approach_timeout():
    run("""
s.setReducedMotion(true); s.setSleeping(true); s.setReducedMotion(false);
s.setActivity({activity:'work',active:true});
advance(1.2+8+.05); assert.equal(s.pose,'calling');        // nobody came: the call plays anyway
advance(.8+.5+.72+.1); assert.equal(s.pose,'walk'); assert.equal(s.wakeStage,null);
""")


def test_repeated_events_during_the_sequence_do_not_restart_it():
    run("""
s.setReducedMotion(true); s.setSleeping(true); s.setReducedMotion(false);
s.setActivity({activity:'work',active:true}); advance(.5);
const phase=s.phase;
s.setActivity({activity:'work',active:true});              // the 15 s snapshot repeats it
s.setActivity({activity:'read',active:true});              // a second message changes the job
s.setSleeping(false); s.setSleeping(true,{manual:false});  // host display churn
assert.equal(s.pose,'dozing'); assert.equal(s.phase,phase); assert.equal(s.target,'library');
advance(.75); assert.equal(s.wakeStage,'approach');
s.visitorAtBed(); s.visitorAtBed(); assert.equal(s.pose,'calling'); assert.equal(s.phase,.8);
advance(.8+.5+.72+.1); assert.equal(s.pose,'walk');
advance(20); assert.equal(s.room,'library'); assert.equal(s.pose,'read');
""")


def test_reply_finished_during_the_sequence_still_ends_idle_at_the_station():
    run("""
s.setReducedMotion(true); s.setSleeping(true); s.setReducedMotion(false);
s.setActivity({activity:'work',active:true}); advance(2); s.visitorAtBed(); advance(.3);
s.setActivity({activity:'idle',active:false});
assert.equal(s.active,false); assert.equal(s.pose,'calling');
advance(.6+.5+.72+.1); assert.equal(s.pose,'walk'); assert.equal(s.status,'moving');
advance(20); assert.equal(s.room,'work'); assert.equal(s.pose,'idle'); assert.equal(s.sleeping,false);
""")


def test_background_organize_wakes_alone_and_quietly():
    run("""
s.setReducedMotion(true); s.setSleeping(true); s.setReducedMotion(false);
s.setActivity({activity:'organize',active:true});
assert.equal(s.pose,'waking'); assert.equal(s.wakeStage,'stir'); assert.equal(s.wakeVisitor,false);
advance(.7); assert.equal(s.pose,'hop'); assert.equal(s.exclaim,0);
advance(.8); assert.equal(s.pose,'walk'); assert.equal(s.target,'library');
advance(20); assert.equal(s.pose,'organize');
""")


def test_reduced_motion_and_hidden_skip_the_whole_sequence():
    run("""
s.setReducedMotion(true); s.setSleeping(true);
s.setActivity({activity:'work',active:true});
assert.equal(s.pose,'work'); assert.equal(s.room,'work'); assert.equal(s.wakeStage,null);
s.setActivity({activity:'idle',active:false}); s.setSleeping(true); s.setReducedMotion(false);
s.setActivity({activity:'chat',active:true}); assert.equal(s.pose,'dozing');
s.skipWake();                                              // the office is not on screen
assert.equal(s.pose,'chat'); assert.equal(s.room,'chat'); assert.equal(s.wakeStage,null); assert.equal(s.route.length,0);
s.setActivity({activity:'idle',active:false}); s.setSleeping(true); s.setActivity({activity:'work',active:true});
s.setReducedMotion(true);                                  // reduced arrives mid-doze
assert.equal(s.pose,'work'); assert.equal(s.wakeStage,null);
""")


def test_disconnect_cancels_the_sequence_and_life_resumes():
    run("""
s.setReducedMotion(true); s.setSleeping(true); s.setReducedMotion(false);
s.setActivity({activity:'work',active:true}); advance(1.5);
s.setConnected(false);
assert.equal(s.pose,'idle'); assert.equal(s.wakeStage,null); assert.equal(s.wakeVisitor,false); assert.equal(s.active,false);
assert.equal(s.status,'offline');
s.setConnected(true); assert.equal(s.status,'idle');
advance(16); assert.ok(s.route.length>0||s.pose!=='idle');  // routines pick up again
""")


def test_idle_life_is_mostly_naps_with_meaningful_errands():
    run("""
assert.ok(IDLE_ROUTINES.filter(r=>r.pose==='sleep').reduce((t,r)=>t+r.seconds,0)
  > .6*IDLE_ROUTINES.reduce((t,r)=>t+r.seconds,0));
let napping=0,total=0; const poses=new Set(); const rooms=new Set();
for(let i=0;i<2400*20;i++){ s.step(.05); total++; if(s.pose==='sleep') napping++; poses.add(s.pose); rooms.add(s.room);
  assert.equal(s.active,false); assert.equal(s.activity,'idle'); }
assert.ok(napping/total>.6,'nap share '+(napping/total).toFixed(2));
for(const pose of ['sleep','eat','leisure','play','stretch','water','walk']) assert.ok(poses.has(pose),pose);
for(const room of ['sleep','kitchen','library','chat','work']) assert.ok(rooms.has(room),room);
""")


def test_automatic_sleep_is_a_finite_nap_and_quick_sleep_is_indefinite():
    run("""
s.setSleeping(true,{manual:false}); advance(30);
assert.equal(s.pose,'sleep'); assert.equal(s.manualSleep,false); assert.ok(s.napRemaining>0&&s.napRemaining<=240);
const remaining=s.napRemaining;
s.setSleeping(true,{manual:false});                        // the idle timer fires again: same nap
assert.equal(s.napRemaining,remaining);
advance(300); assert.ok(s.idleIndex>=1,'the nap ended and a routine began');
s.setSleeping(true); advance(5);                           // explicit Sleep: until woken
assert.equal(s.manualSleep,true); const index=s.idleIndex;
advance(900); assert.equal(s.pose,'sleep'); assert.equal(s.idleIndex,index);
s.setSleeping(true,{manual:false}); assert.equal(s.manualSleep,true);   // automatic does not shorten it
s.setSleeping(false); assert.equal(s.pose,'waking'); assert.equal(s.wakeVisitor,false);
""")


def test_skipped_or_cancelled_wakes_take_the_startle_with_them():
    # Hidden, reduced-motion and disconnect skips teleport the crab to its
    # station; the "!" from the startle must not reappear there later. A
    # wake that completes its hop still lets the "!" fade on the way.
    run("""
const nap=()=>{s.setActivity({activity:'idle',active:false});s.setReducedMotion(true);s.setSleeping(true);s.setReducedMotion(false);assert.equal(s.pose,'sleep');};
const startle=()=>{s.setActivity({activity:'work',active:true});advance(1.3);s.visitorAtBed();advance(.85);assert.equal(s.wakeStage,'startled');assert.ok(s.exclaim>0);};
nap(); startle(); s.skipWake();
assert.equal(s.exclaim,0); assert.equal(s.pose,'work'); assert.equal(s.wakeStage,null);
nap(); startle(); s.setConnected(false); assert.equal(s.exclaim,0); s.setConnected(true);
nap(); startle(); s.setReducedMotion(true); assert.equal(s.exclaim,0); assert.equal(s.pose,'work'); s.setReducedMotion(false);
// Sleep is refused while a job is active; once the reply is done it clears the shout
nap(); startle(); s.setActivity({activity:'idle',active:false}); s.setSleeping(true);
assert.equal(s.exclaim,0); assert.equal(s.pose,'sleep');
nap(); startle(); advance(.55);
assert.equal(s.pose,'hop'); advance(.75); assert.equal(s.pose,'walk'); assert.ok(s.exclaim>0);
advance(2); assert.equal(s.exclaim,0);
""")
