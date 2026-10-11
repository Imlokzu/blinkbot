"""Independent wake-cue races in production JS, with controlled media only."""

from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest


SCREEN = Path(__file__).resolve().parents[1] / "static" / "screen"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="node is not installed")


def run_wake(body: str) -> dict:
    """Run actual queue, cache, recognition and dispatch functions together."""
    script = r"""
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import {WakeCueCache} from CUE_MODULE;
import {VoiceTurnDetector} from DETECTOR_MODULE;
import {parseWake, findWake, isStopCommand} from WAKE_MODULE;
const source=readFileSync(SCREEN_SOURCE,'utf8');
const extract=name=>{
  const match=source.match(new RegExp('^(?:async )?function '+name+'\\([^\\n]*\\) \\{[\\s\\S]*?^\\}', 'm'));
  assert.ok(match,'Missing production function: '+name);
  return match[0];
};
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no;});return {promise,resolve,reject};};
const flush=async()=>{for(let i=0;i<40;i++) await Promise.resolve();};
function environment(extra={}) {
  const ctx=vm.createContext({
    Blob, FormData, AbortController, parseWake, findWake, isStopCommand, VoiceTurnDetector,
    voiceOn:true,ttsAvailable:true,voiceSpeed:1,ttsVoiceSignature:'review:0',volume:70,voiceUrl:null,
    voiceMode:'wake',wakeWord:'claude',wakeArmed:false,wakeCuePendingFinal:false,wakeCueNumber:0,
    listening:true,botSpeaking:false,activeChat:null,followUpPending:false,replyInterrupted:false,
    speechQueue:[],speechRunning:false,speechController:null,speechEpoch:0,speechEnded:null,
    recognition:null,micEpoch:1,micStream:null,meter:null,mediaRec:null,recTimer:0,srRestartTimer:0,
    asrController:null,partialController:null,partialBusy:false,srRestarts:0,srWindowAt:0,
    asrAvailable:true,partialsOn:false,spoke:false,recStartAt:0,PARTIAL_MS:5000,REC_MAX_MS:15000,
    voiceDetector:new VoiceTurnDetector(),recorders:[],
    SR_MAX_RESTARTS:5,SR_WINDOW_MS:10000,WAKE_ARM_MS:8000,FOLLOW_UP_MS:8000,wakeTimer:0,
    VOICE_KEY:'voice',clock:0,lang:'en',sent:[],log:[],requests:[],plays:0,timers:new Map(),nextTimer:0,
    micButtons:[],micLabel:{textContent:''},chatLive:{textContent:''},
    faceCaption:{classList:{contains:()=>false}},
    URL:{createObjectURL:()=> 'stub:audio',revokeObjectURL(){}},
    splitImages:text=>({text}),syncMusicVolume(){},captionSpeechStarted(){},captionSpeechEnded(){},
    clearBusy(){},writePref(){},wake(){},hideCaption(){},setVoiceMode(){},
    crab:{setEmotion(){},setAudioLevel(){}},
    ...extra,
  });
  ctx.Date={now:()=>ctx.clock};
  ctx.setTimeout=callback=>{const id=++ctx.nextTimer;ctx.timers.set(id,callback);return id;};
  ctx.clearTimeout=id=>ctx.timers.delete(id);
  ctx.getLang=()=>ctx.lang;
  ctx.t=key=>({
    'voice.wakeHi':ctx.lang==='uk' ? 'Привіт!' : 'Hi!',
    'voice.wakeNeed':ctx.lang==='uk' ? 'Що тобі потрібно?' : 'What do you need?',
  }[key] || key);
  ctx.showCaption=(text,kind)=>ctx.log.push(['caption',text,kind]);
  ctx.showLive=text=>ctx.log.push(['live',text]);
  ctx.sendChat=text=>{ctx.sent.push(text);ctx.speechReset();};
  ctx.fetch=(url,options)=>{
    ctx.requests.push({url,body:JSON.parse(options.body),signal:options.signal});
    return Promise.resolve({ok:true,blob:async()=>new Blob(['audio'])});
  };
  ctx.voiceAudio={pause(){},play(){ctx.plays++;return Promise.resolve();}};
  ctx.SR=class {start(){} stop(){}};
  ctx.MediaRecorder=class {
    constructor(){this.state='inactive';this.mimeType='audio/webm';ctx.recorders.push(this);}
    start(){this.state='recording';}
    stop(){
      this.state='inactive';
      this.ondataavailable?.({data:new Blob(['recorded speech'],{type:this.mimeType})});
      this.onstop?.();
    }
  };
  ctx.window={MediaRecorder:ctx.MediaRecorder};
  const names=['speechAudioDone','speechReset','ttsBlob','cutForSpeech','playBlob','speechPump',
    'speechSay','speechWanted','wakeCueScope','warmWakeReplies','acknowledgeWake','handleWakeInterim',
    'handleFinalText','isPlaybackEcho','heardWhileSpeaking','bargeIn','armWake','disarmWake',
    'startFollowUp','micStateLabel','setListening','startRecognition','stopContinuous','closeMic',
    'onMicLevel','startRecorder','sendPartial','sendToAsr'];
  vm.runInContext(names.map(extract).join('\n'),ctx);
  ctx.wakeCueCache=new WakeCueCache((text,signal,speed)=>ctx.ttsBlob(text,signal,speed));
  return ctx;
}
const deliver=(ctx,text,isFinal,index=0)=>{
  const result=[{transcript:text}];result.isFinal=isFinal;
  const results=Array.from({length:index+1},()=>null);results[index]=result;
  ctx.recognition.onresult({resultIndex:index,results});
};
BODY
"""
    replacements = {
        "CUE_MODULE": json.dumps((SCREEN / "wake-cues.js").as_uri()),
        "DETECTOR_MODULE": json.dumps((SCREEN / "voice-turn.js").as_uri()),
        "WAKE_MODULE": json.dumps((SCREEN / "wake.js").as_uri()),
        "SCREEN_SOURCE": json.dumps(str(SCREEN / "screen.js")),
        "BODY": body,
    }
    for marker, value in replacements.items():
        script = script.replace(marker, value)
    result = subprocess.run(
        [NODE, "--input-type=module"], input=script, capture_output=True,
        text=True, timeout=15, check=False,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


@pytest.mark.parametrize("mode", ["wake", "open"])
def test_command_finishes_while_its_interim_name_cue_plays(mode):
    """A fast greeting must not make the rest of that utterance disappear."""
    result = run_wake(f"""
const ctx=environment({{voiceMode:{json.dumps(mode)}}});ctx.startRecognition(true);
deliver(ctx,'Claude',false);await flush();const greeting=ctx.plays;
deliver(ctx,'Claude what time is it',true);await flush();
console.log(JSON.stringify({{greeting,sent:ctx.sent,pending:ctx.wakeCuePendingFinal}}));
""")
    expected = "what time is it" if mode == "wake" else "Claude what time is it"
    assert result == {"greeting": 1, "sent": [expected], "pending": False}


@pytest.mark.parametrize("mode", ["wake", "open"])
def test_interim_and_repeated_final_name_share_one_cue(mode):
    """Keep the final for one result separate from a later deliberate call."""
    result = run_wake(f"""
const ctx=environment({{voiceMode:{json.dumps(mode)}}});ctx.startRecognition(true);
deliver(ctx,'Claude',false);await flush();
deliver(ctx,'Claude',false);await flush();deliver(ctx,'Claude',true);await flush();
deliver(ctx,'Claude',true);await flush();
const first={{plays:ctx.plays,sent:[...ctx.sent],pending:ctx.wakeCuePendingFinal}};
ctx.speechAudioDone();await flush();deliver(ctx,'Claude',true,1);await flush();
const next=ctx.plays;ctx.speechReset();await flush();
console.log(JSON.stringify({{first,next}}));
""")
    assert result == {"first": {"plays": 1, "sent": [], "pending": False}, "next": 2}


def test_short_cue_echo_is_filtered_with_an_older_reply_in_history():
    """The one-word Hi cue needs its own echo comparison, not concatenation."""
    result = run_wake("""
const ctx=environment({voiceMode:'open'});
ctx.voiceAudio.speechEchoes=[{text:'The weather is sunny.',expires:1200}];
ctx.startRecognition(true);deliver(ctx,'Claude',false);await flush();
deliver(ctx,'Hi!',true);await flush();
const observed={sent:ctx.sent,plays:ctx.plays};ctx.speechReset();await flush();
console.log(JSON.stringify(observed));
""")
    assert result == {"sent": [], "plays": 1}


@pytest.mark.parametrize("mode", ["wake", "open"])
def test_follow_up_without_name_is_received_while_greeting_still_plays(mode):
    result = run_wake(f"""
const ctx=environment({{voiceMode:{json.dumps(mode)}}});ctx.startRecognition(true);
deliver(ctx,'Claude',false);await flush();deliver(ctx,'Claude',true);await flush();
const speaking=ctx.botSpeaking;
deliver(ctx,'what time is it',true,1);await flush();
console.log(JSON.stringify({{speaking,sent:ctx.sent,plays:ctx.plays}}));
""")
    assert result == {"speaking": True, "sent": ["what time is it"], "plays": 1}


@pytest.mark.parametrize("phrase", ["Claude", "Claude what time is it"])
def test_new_named_call_interrupts_an_ordinary_reply_in_wake_mode(phrase):
    """Browser fallback and server ASR should both hear a new explicit address."""
    result = run_wake(f"""
const ctx=environment();ctx.speechSay('An ordinary answer is still playing.');await flush();
ctx.startRecognition(true);deliver(ctx,{json.dumps(phrase)},true);await flush();
const observed={{sent:ctx.sent,plays:ctx.plays}};ctx.speechReset();await flush();
console.log(JSON.stringify(observed));
""")
    expected = {"sent": [], "plays": 2} if phrase == "Claude" else {
        "sent": ["what time is it"], "plays": 1,
    }
    assert result == expected


@pytest.mark.parametrize("mode", ["wake", "open"])
def test_named_tool_stop_still_dispatches_once(mode):
    result = run_wake(f"""
const ctx=environment({{voiceMode:{json.dumps(mode)}}});ctx.startRecognition(true);
deliver(ctx,'Claude',false);await flush();deliver(ctx,'Claude stop the music',true);await flush();
console.log(JSON.stringify({{sent:ctx.sent,interrupted:ctx.replyInterrupted}}));
""")
    expected = "stop the music" if mode == "wake" else "Claude stop the music"
    assert result == {"sent": [expected], "interrupted": False}


def test_browser_follow_up_started_in_window_can_finish_after_expiry():
    result = run_wake("""
const ctx=environment();ctx.armWake(8000);ctx.startRecognition(true);
ctx.clock=7000;deliver(ctx,'what time',false);
ctx.clock=8500;ctx.disarmWake();deliver(ctx,'what time is it',true);await flush();
console.log(JSON.stringify({sent:ctx.sent,armed:ctx.wakeArmed}));
""")
    assert result == {"sent": ["what time is it"], "armed": False}


def test_empty_browser_final_does_not_retain_follow_up_intent():
    result = run_wake("""
const ctx=environment();ctx.armWake(8000);ctx.startRecognition(true);
ctx.clock=7000;deliver(ctx,'hmm',false);ctx.clock=8500;ctx.disarmWake();
deliver(ctx,'',true);deliver(ctx,'too late for that window',true,1);await flush();
console.log(JSON.stringify({sent:ctx.sent,armed:ctx.wakeArmed,listening:ctx.listening}));
""")
    assert result == {"sent": [], "armed": False, "listening": True}


@pytest.mark.parametrize("empty", [False, True])
def test_recorded_follow_up_owns_intent_until_one_final_even_after_expiry(empty):
    result = run_wake(f"""
const ctx=environment({{micStream:{{getTracks:()=>[]}}}});const first=deferred();let calls=0;
ctx.fetch=(url)=>url==='/api/asr' ? (++calls===1 ? first.promise : Promise.resolve({{ok:true,json:async()=>({{text:'too late'}})}}))
  : Promise.resolve({{ok:true,blob:async()=>new Blob(['cue'])}});
ctx.voiceDetector={{reset(){{}},update:()=>({{start:true,end:false}})}};
ctx.armWake(8000);ctx.startRecorder(true);ctx.clock=7000;ctx.onMicLevel(.5);
ctx.clock=8500;ctx.disarmWake();ctx.mediaRec.stop();
first.resolve({{ok:true,json:async()=>({{text:{json.dumps('' if empty else 'what time is it')}}})}});await flush();
const sent=[...ctx.sent];ctx.clock=9000;ctx.onMicLevel(.5);ctx.mediaRec.stop();await flush();
console.log(JSON.stringify({{sent,afterNext:ctx.sent,armed:ctx.wakeArmed,listening:ctx.listening}}));
""")
    expected = [] if empty else ["what time is it"]
    assert result == {"sent": expected, "afterNext": expected, "armed": False, "listening": True}


@pytest.mark.parametrize("noise_first", [False, True])
def test_recorded_start_before_expiry_is_confirmed_after_it_without_noise_latching(noise_first):
    """The detector's 180 ms confirmation must preserve speech, not a click."""
    result = run_wake(f"""
const ctx=environment({{micStream:{{getTracks:()=>[]}}}});
ctx.fetch=url=>Promise.resolve(url==='/api/asr' ? {{ok:true,json:async()=>({{text:'what time is it'}})}}
  : {{ok:true,blob:async()=>new Blob(['cue'])}});
ctx.armWake(8000);ctx.startRecorder(true);ctx.clock=7950;ctx.onMicLevel(.5);
if ({str(noise_first).lower()}) {{ctx.clock=7980;ctx.onMicLevel(.01);}}
ctx.clock=8100;ctx.disarmWake();
if ({str(noise_first).lower()}) {{ctx.clock=8200;ctx.onMicLevel(.5);}}
ctx.clock=8400;ctx.onMicLevel(.5);ctx.mediaRec.stop();await flush();
console.log(JSON.stringify({{sent:ctx.sent,armed:ctx.wakeArmed,listening:ctx.listening}}));
""")
    expected = [] if noise_first else ["what time is it"]
    assert result == {"sent": expected, "armed": False, "listening": True}


@pytest.mark.parametrize("phrase", ["Клод Бот", "Клод", "hey Claude Bot", "Claude"])
def test_configured_full_name_and_shorthand_share_the_feedback_path(phrase):
    result = run_wake(f"""
const ctx=environment({{wakeWord:'Клод Бот'}});ctx.startRecognition(true);
deliver(ctx,{json.dumps(phrase)},false);await flush();deliver(ctx,{json.dumps(phrase)},true);await flush();
const observed={{plays:ctx.plays,sent:ctx.sent,pending:ctx.wakeCuePendingFinal}};
ctx.speechReset();await flush();console.log(JSON.stringify(observed));
""")
    assert result == {"plays": 1, "sent": [], "pending": False}


def test_pause_releases_a_pending_cue_without_waiting_for_synthesis():
    """An uncooperative download must neither hold the pump nor repaint later."""
    result = run_wake("""
const pending=deferred();const ctx=environment();
ctx.wakeCueCache=new WakeCueCache(()=>pending.promise);ctx.acknowledgeWake(true);await flush();
ctx.stopContinuous();ctx.showCaption('next caption','bot');await flush();
const paused={running:ctx.speechRunning,speaking:ctx.botSpeaking,listening:ctx.listening};
pending.resolve(new Blob(['obsolete']));await flush();
console.log(JSON.stringify({paused,plays:ctx.plays,last:ctx.log.filter(x=>x[0]==='caption').at(-1)}));
""")
    assert result == {
        "paused": {"running": False, "speaking": False, "listening": False},
        "plays": 0, "last": ["caption", "next caption", "bot"],
    }


@pytest.mark.parametrize("change", ["ctx.lang='uk'", "ctx.voiceSpeed=1.1", "ctx.ttsVoiceSignature='review:1'"])
def test_scope_change_cancels_a_waiting_cue_and_keeps_new_cache(change):
    """Late audio for an old locale, speed or voice cannot enter a new scope."""
    result = run_wake(f"""
const pending=deferred();const ctx=environment();
ctx.wakeCueCache=new WakeCueCache((text,signal)=>signal===oldSignal ? pending.promise : Promise.resolve(new Blob(['new'])));
let oldSignal;ctx.warmWakeReplies();oldSignal=ctx.wakeCueCache.controller.signal;
ctx.acknowledgeWake(true);await flush();
{change};ctx.warmWakeReplies();await flush();
const changed={{aborted:oldSignal.aborted,running:ctx.speechRunning}};
ctx.showCaption('new locale caption','bot');pending.resolve(new Blob(['obsolete']));await flush();
const blob=ctx.wakeCueCache.get(ctx.wakeCueScope(),ctx.t('voice.wakeHi'));
console.log(JSON.stringify({{changed,plays:ctx.plays,audio:await blob.text(),last:ctx.log.filter(x=>x[0]==='caption').at(-1)}}));
""")
    assert result == {
        "changed": {"aborted": True, "running": False}, "plays": 0, "audio": "new",
        "last": ["caption", "new locale caption", "bot"],
    }


def test_warm_request_speed_is_the_speed_of_its_cache_scope():
    """Cache scheduling crosses a microtask; capture speed before that boundary."""
    result = run_wake("""
const ctx=environment();ctx.warmWakeReplies();const scope=ctx.wakeCueScope();
ctx.voiceSpeed=1.1;await flush();
const warmed=ctx.requests.map(item=>item.body.speed);
await ctx.ttsBlob('ordinary reply',new AbortController().signal);
console.log(JSON.stringify({scope,warmed,next:ctx.requests.at(-1).body.speed}));
""")
    assert result == {"scope": "en:1:review:0", "warmed": [1, 1], "next": 1.1}


@pytest.mark.parametrize("mode", ["wake", "open"])
@pytest.mark.parametrize("phrase", ["stop", "Claude stop"])
def test_stop_during_a_cue_never_becomes_a_model_turn(mode, phrase):
    result = run_wake(f"""
const pending=deferred();const ctx=environment({{voiceMode:{json.dumps(mode)}}});
ctx.wakeCueCache=new WakeCueCache(()=>pending.promise);ctx.acknowledgeWake(true);await flush();
ctx.handleFinalText({json.dumps(phrase)});await flush();
const observed={{sent:ctx.sent,running:ctx.speechRunning,pending:ctx.wakeCuePendingFinal}};
pending.resolve(new Blob(['obsolete']));await flush();console.log(JSON.stringify(observed));
""")
    assert result == {"sent": [], "running": False, "pending": False}


@pytest.mark.parametrize("mode", ["wake", "open"])
def test_name_feedback_cannot_start_while_listening_is_paused(mode):
    result = run_wake(f"""
const ctx=environment({{voiceMode:{json.dumps(mode)},listening:false}});
ctx.handleWakeInterim('Claude');ctx.handleFinalText('Claude');await flush();
console.log(JSON.stringify({{plays:ctx.plays,requests:ctx.requests.length,sent:ctx.sent,log:ctx.log}}));
""")
    assert result == {"plays": 0, "requests": 0, "sent": [], "log": []}


def test_stop_pending_cue_does_not_hold_the_next_spoken_reply():
    result = run_wake("""
const pending=deferred();const ctx=environment();
ctx.wakeCueCache=new WakeCueCache(()=>pending.promise);ctx.acknowledgeWake(true);await flush();
ctx.bargeIn();ctx.speechSay('new reply');await flush();const plays=ctx.plays;
pending.resolve(new Blob(['obsolete']));await flush();
const last=ctx.log.filter(x=>x[0]==='caption').at(-1);ctx.speechReset();await flush();
console.log(JSON.stringify({plays,last}));
""")
    assert result == {"plays": 1, "last": ["caption", "voice.stopped", "bot"]}


def test_cancelled_cache_generation_cannot_erase_new_generation_for_same_scope():
    result = run_wake("""
const old=deferred(),current=deferred();let count=0;
const cache=new WakeCueCache(()=>++count===1 ? old.promise : current.promise);
const first=cache.prepare('en:1:review:0',['Hi!']);await flush();cache.cancel();
const second=cache.prepare('en:1:review:0',['Hi!']);await flush();const slot=cache.get(cache.scope,'Hi!');
old.resolve(new Blob(['obsolete']));await first;
const kept=cache.get(cache.scope,'Hi!')===slot;
current.resolve(new Blob(['current']));await second;
console.log(JSON.stringify({kept,audio:await cache.get(cache.scope,'Hi!').text()}));
""")
    assert result == {"kept": True, "audio": "current"}
