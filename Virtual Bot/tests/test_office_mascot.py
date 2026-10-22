"""The office must render the existing PixelCrab, including its animations."""
import json
import shutil
import subprocess
from pathlib import Path

import pytest

STATIC = Path(__file__).resolve().parents[1] / 'static'
pytestmark = pytest.mark.skipif(shutil.which('node') is None, reason='node is not installed')


def run(body):
    source = f"""
import vm from 'node:vm';
import fs from 'node:fs';
import assert from 'node:assert/strict';
import {{createOfficeMascot, officeEmotion}} from {json.dumps((STATIC/'screen/office-mascot.js').as_uri())};
let raf=0, canceled=0, fills=0;
const listeners=new Set();
// Every fill is recorded with its colour, so a test can look for eyes, folded
// legs or a frozen snack in what the real painter drew.
const rects=[];
const context=new Proxy({{}},{{get:(o,k)=>k==='createLinearGradient'?()=>({{addColorStop(){{}}}}):k==='measureText'?()=>({{width:10}}):
  k==='fillRect'?(x,y,w,h)=>{{fills++;rects.push({{style:o.fillStyle,x,y,w,h}});}}:()=>{{}},set:(o,k,v)=>{{o[k]=v;return true;}}}});
const makeCanvas=()=>({{width:160,height:112,getContext:()=>context}});
const document={{hidden:false,addEventListener:(type,fn)=>listeners.add(fn),removeEventListener:(type,fn)=>listeners.delete(fn),createElement:()=>makeCanvas()}};
const env={{document,performance:{{now:()=>0}},requestAnimationFrame:()=>++raf,cancelAnimationFrame:()=>canceled++}};
vm.createContext(env);vm.runInContext(fs.readFileSync({json.dumps(str(STATIC/'crab.js'))},'utf8')+';globalThis.Mascot=PixelCrab;',env);
const Mascot=env.Mascot;
globalThis.document=document;
{body}
"""
    result = subprocess.run(['node','--input-type=module','-e',source],text=True,capture_output=True,timeout=20)
    assert result.returncode == 0, result.stderr


def test_manual_mode_uses_native_animation_without_a_second_frame_loop():
    run("""
const crab=new Mascot(makeCanvas(),null,null,{scale:4,autoStart:false});
assert.equal(raf,0);assert.equal(listeners.size,0);
for(const emotion of ['idle','working','reading','searching','sad','asking','love','sleepy','speaking']) {
  crab.setEmotion(emotion);
  for(let i=0;i<30;i++)crab.advance(1/24,{walking:false});
  assert.ok(Number.isFinite(crab.x));assert.equal(crab.emotion,emotion);
}
assert.ok(fills>100);assert.equal(raf,0);
crab.setEmotion('idle');crab.hop();assert.equal(crab.jump.phase,'squish');
for(let i=0;i<30;i++)crab.advance(1/24,{});
assert.equal(crab.jump,null);
crab.setEmotion('sleepy');crab.advance(.1,{reduced:true});
assert.ok(crab.eyeClose>.5);assert.equal(crab.airY,0);assert.equal(crab.particles.length,0);
crab.destroy();assert.equal(listeners.size,0);
""")


def test_classic_auto_loop_still_works_and_pauses_when_covered():
    run("""
const crab=new Mascot(makeCanvas(),null,null,{scale:4});
assert.equal(raf,1);assert.equal(listeners.size,1);
crab.setVisible(false);assert.equal(canceled,1);
for(const listener of listeners)listener();assert.equal(raf,1);
crab.setVisible(true);assert.equal(raf,2);
crab.destroy();assert.equal(listeners.size,0);assert.equal(canceled,2);
""")


def test_office_adapter_drives_the_same_native_states_and_keeps_palette_and_audio():
    run("""
const native=new Mascot(makeCanvas(),null,null,{scale:4,autoStart:false});
native.setAudioLevel(.7);native.colors.body='#cc8877';
let sprite;class CapturedMascot extends Mascot {constructor(...args){super(...args);sprite=this;}}
const adapter=createOfficeMascot({source:native,Mascot:CapturedMascot});
const s={pose:'read',activity:'read',emotion:'idle',position:{x:330,y:142},reduced:false};
adapter.update(s,.05);assert.equal(sprite.emotion,'reading');assert.equal(sprite.colors,native.colors);assert.equal(sprite.audioLevel,.7);
adapter.update({...s,pose:'work',activity:'work'},.05);assert.equal(sprite.emotion,'working');
adapter.update({...s,pose:'sleep',activity:'idle'},.05);assert.equal(sprite.emotion,'sleepy');
adapter.update({...s,pose:'hop'},.05);assert.ok(sprite.jump);
assert.equal(officeEmotion({...s,emotion:'sad'}),'sad');
assert.equal(officeEmotion({...s,activity:'help'}),'asking');
adapter.draw(context,s);adapter.destroy();native.destroy();assert.equal(raf,0);
""")


def test_contact_seats_the_native_body_keeps_eyes_and_never_lets_a_jump_leave_the_chair():
    run("""
class Captured extends Mascot {_draw(v){this.lastV=v;super._draw(v);}}
const crab=new Captured(makeCanvas(),null,null,{scale:4,autoStart:false});
const S=crab.S;
const contact=(extra)=>({walking:false,direction:1,reduced:false,furniture:true,action:'rest',seat:1,recline:0,snack:null,actionTime:0,...extra});
crab.setEmotion('idle');
for(let i=0;i<40;i++)crab.advance(1/24,contact());
assert.equal(crab.lastV.seated,1);assert.ok(crab.lift<=-2*S+.5,'body lowered into the legs: '+crab.lift);
assert.ok(rects.some(r=>r.style===crab.colors.eye),'eyes still drawn when seated');
assert.ok(rects.some(r=>r.w===S&&Math.abs(r.h-S*.35)<1e-9),'legs folded');
// A fright starts the native jump; a seated body never leaves the chair
crab.setEmotion('surprised');assert.ok(crab.jump);
crab.advance(1/24,contact());assert.equal(crab.jump,null);assert.equal(crab.airY,0);
crab.hop();crab.advance(1/24,contact());assert.equal(crab.jump,null,'an explicit hop is held in the chair too');
crab.setEmotion('idle');crab.setEmotion('surprised');crab.advance(1/24,contact({seat:0}));assert.ok(crab.jump,'standing, the same fright jumps');
// Lying under the duvet flattens the body but keeps the (sleepy) eyes
crab.setEmotion('sleepy');for(let i=0;i<40;i++)crab.advance(1/24,contact({seat:0,recline:1,action:'sleep'}));
assert.ok(crab.sy<.7&&crab.sy>.5,'reclined: '+crab.sy);assert.ok(rects.some(r=>r.style===crab.colors.eye));
// Standing up unfolds the legs on the very first walking frame, while the
// scene is still easing its seat value down
crab.setEmotion('idle');crab.advance(1/24,contact({walking:true,seat:.9}));
assert.equal(crab.lastV.seated,0);
""")


def test_office_furniture_replaces_native_props_but_keeps_book_glasses_and_snack():
    run("""
const calls=[];
class Spy extends Mascot {
  _drawTerminal(...a){calls.push('terminal');super._drawTerminal(...a);}
  _drawBook(cx,b,S,g,lap){calls.push('book:'+(lap?'lap':'table'));super._drawBook(cx,b,S,g,lap);}
  _drawSnack(cx,b,S,g,kind,time){calls.push('snack:'+kind);super._drawSnack(cx,b,S,g,kind,time);}
  _draw(v){this.lastV=v;super._draw(v);}
}
const crab=new Spy(makeCanvas(),null,null,{scale:4,autoStart:false});
crab.setEmotion('working');crab.advance(1/24,{});                 // manual but no furniture: native terminal
assert.equal(crab.lastV.prop,'terminal');assert.ok(calls.includes('terminal'));calls.length=0;
crab.advance(1/24,{furniture:true,action:'type',seat:1});        // the office desk is real: no second one
assert.equal(crab.lastV.prop,null);assert.ok(!calls.includes('terminal'));
crab.setEmotion('reading');crab.advance(1/24,{furniture:true,action:'read',seat:1});
assert.ok(calls.includes('book:lap'));assert.equal(crab.lastV.glasses,true);calls.length=0;
crab.advance(1/24,{furniture:true,action:'memory',seat:0});assert.ok(calls.includes('book:lap'));calls.length=0;
crab.advance(1/24,{furniture:true,action:'sort',seat:0});assert.ok(!calls.some(c=>c.startsWith('book')));
crab.setEmotion('idle');crab.advance(1/24,{furniture:true,action:'eat',seat:1,snack:'sip',actionTime:1});
assert.ok(calls.includes('snack:sip'));calls.length=0;
crab.setEmotion('reading');crab.advance(1/24,{});assert.ok(calls.includes('book:table'),'classic reading keeps its table');
""")


def test_reduced_motion_freezes_food_and_pages_and_a_zero_dt_contact_is_stable():
    run("""
const crab=new Mascot(makeCanvas(),null,null,{scale:4,autoStart:false});
const frame=(motion)=>{rects.length=0;crab.advance(1/24,motion);return JSON.stringify(rects);};
crab.setEmotion('idle');
const bite={reduced:true,furniture:true,action:'eat',seat:1,snack:'bite',actionTime:1.1};
frame(bite);assert.equal(frame(bite),frame(bite),'reduced motion: the bite does not move');
crab.setEmotion('reading');
const page={reduced:true,furniture:true,action:'read',seat:1};
frame(page);assert.equal(frame(page),frame(page),'reduced motion: no page turn');
crab.setEmotion('idle');
for(let i=0;i<30;i++)crab.advance(1/24,{furniture:true,action:'rest',seat:1});
const lift=crab.lift, t=crab.t;
for(let i=0;i<10;i++)crab.advance(0,{furniture:true,action:'rest',seat:1});
assert.equal(crab.lift,lift);assert.equal(crab.t,t);
""")


def test_classic_automatic_crab_never_sits_and_keeps_its_props():
    run("""
class Captured extends Mascot {_draw(v){this.lastV=v;super._draw(v);}}
const crab=new Captured(makeCanvas(),null,null,{scale:4});          // the screen's own loop
crab.setEmotion('working');
let ts=0;for(let i=0;i<30;i++)crab._tick(ts+=1000/60);
assert.equal(crab.lastV.seated,0);assert.equal(crab.lastV.prop,'terminal');
assert.ok(rects.some(r=>r.w===crab.S&&r.h===crab.S&&r.y===crab.groundY-3*crab.S),'legs unfolded');
assert.ok(!rects.some(r=>r.w===crab.S&&Math.abs(r.h-crab.S*.35)<1e-9));
crab.destroy();
""")


def test_adapter_snaps_contact_on_show_and_stands_up_the_moment_the_state_walks():
    run("""
let sprite;class Captured extends Mascot {constructor(...a){super(...a);sprite=this;}_draw(v){this.lastV=v;super._draw(v);}}
const adapter=createOfficeMascot({Mascot:Captured});
const seated={pose:'work',activity:'work',room:'work',emotion:'idle',position:{x:544,y:90},reduced:false,active:true};
adapter.snap(seated);
assert.equal(adapter.presentation.kind,'type');assert.equal(adapter.presentation.seat,1);
assert.equal(sprite.lift,-2*sprite.S,'the body is already down when the office appears');
assert.equal(sprite.emotion,'working');
adapter.update(seated,1/24);assert.equal(sprite.lastV.seated,1);
const walking={...seated,pose:'walk'};
adapter.update(walking,1/24);assert.equal(adapter.presentation.kind,'walk');assert.equal(sprite.lastV.seated,0);
assert.ok(adapter.presentation.seat>0&&adapter.presentation.seat<1,'the scene eases its seat while the body already stands');
adapter.destroy();
""")
