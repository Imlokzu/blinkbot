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
const context=new Proxy({{}},{{get:(o,k)=>k==='createLinearGradient'?()=>({{addColorStop(){{}}}}):k==='measureText'?()=>({{width:10}}):()=>{{if(k==='fillRect')fills++;}},set:(o,k,v)=>{{o[k]=v;return true;}}}});
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
