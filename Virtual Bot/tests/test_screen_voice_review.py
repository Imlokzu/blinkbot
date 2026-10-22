"""Adversarial races in the screen's actual voice functions, without devices."""

from __future__ import annotations

import ast
import asyncio
import json
import shutil
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock

import pytest


SCREEN = Path(__file__).resolve().parents[1] / "static" / "screen"
NODE = shutil.which("node")
pytestmark = pytest.mark.skipif(NODE is None, reason="node is not installed")


def run_screen(body: str) -> dict:
    """Extract functions from the real source; stub only their environment."""
    script = f"""
import vm from 'node:vm';
import assert from 'node:assert/strict';
import {{readFileSync}} from 'node:fs';
import {{VoiceTurnDetector}} from {json.dumps((SCREEN / 'voice-turn.js').as_uri())};
import {{WakeCueCache}} from {json.dumps((SCREEN / 'wake-cues.js').as_uri())};
import {{parseWake, findWake, isStopCommand}} from {json.dumps((SCREEN / 'wake.js').as_uri())};
import {{ReplyTurn}} from {json.dumps((SCREEN / 'reply.js').as_uri())};
const source = readFileSync({json.dumps(str(SCREEN / 'screen.js'))}, 'utf8');
const extract = name => {{
  const match = source.match(new RegExp('^(?:async )?function ' + name + '\\\\([^\\\\n]*\\\\) \\\\{{[\\\\s\\\\S]*?^\\\\}}', 'm'));
  assert.ok(match, 'Missing production function: ' + name);
  return match[0];
}};
const deferred = () => {{
  let resolve, reject;
  const promise = new Promise((yes, no) => {{resolve=yes; reject=no;}});
  return {{promise, resolve, reject}};
}};
const flush = async () => {{for(let i=0;i<30;i++) await Promise.resolve();}};
class Element {{
  constructor() {{this.isConnected=true; this.children=[]; this.textContent=''; this.classList={{add(){{}}, remove(){{}}, toggle(){{}}}};}}
  appendChild(child) {{this.children.push(child); return child;}}
  insertBefore(child) {{return this.appendChild(child);}}
  remove() {{this.isConnected=false;}}
  querySelector() {{return null;}}
}}
function environment(extra={{}}) {{
  const ctx=vm.createContext({{
    Blob, FormData, AbortController, TextDecoder, ReplyTurn, VoiceTurnDetector, parseWake, findWake, isStopCommand,
    wakeCueCache:new WakeCueCache(async()=>null), warmWakeReplies(){{}}, handleWakeInterim(){{}},
    clock:0, timers:new Map(), nextTimer:0, log:[], sent:[], recorders:[], plays:0,
    speechQueue:[], speechRunning:false, speechController:null, speechEpoch:0, speechEnded:null,
    spokenReplyText:'', voiceOn:true, ttsAvailable:true, voiceSpeed:1, volume:70, voiceUrl:null,
    botSpeaking:false, musicDucked:false, VOICE_KEY:'voice', SPEAK_MIN_CHARS:24, SPEAK_MAX_CHARS:700,
    micStream:null, micEpoch:1, meter:null, listening:true, recognition:null, mediaRec:null,
    asrAvailable:true, asrController:null, partialController:null, partialBusy:false, partialsOn:true,
    spoke:false, recStartAt:0, recTimer:0, srRestartTimer:0, srRestarts:0, srWindowAt:0,
    SR:null, SR_MAX_RESTARTS:5, SR_WINDOW_MS:10000, REC_MAX_MS:15000, PARTIAL_MS:5000,
    voiceDetector:new VoiceTurnDetector(), voiceMode:'open', wakeWord:'claude', wakeArmed:false,
    wakeTimer:0, followUpPending:false, activeChat:null, chatBusy:false, replyInterrupted:false,
    office:null, officeActivity:{{activity:'idle', active:false}},
    sessionId:'review-session', micButtons:[], micLabel:{{textContent:''}}, chatLog:new Element(),
    URL:{{createObjectURL:()=> 'stub:audio', revokeObjectURL(){{}}}},
    document:{{createElement:()=>new Element()}},
    smd:{{parser:()=>({{}}), default_renderer:()=>({{}}), parser_write(){{}}, parser_end(){{}}}},
    t:key=>key, splitImages:text=>({{text}}), syncMusicVolume(){{}}, captionSpeechStarted(){{}},
    captionSpeechEnded(){{}}, startFollowUp(){{}}, onReplyFinished(){{}}, writePref(){{}}, clearBusy(){{}}, setBusy(){{}},
    chatScrollDown(){{}}, wake(){{}}, setEmotion(){{}}, disarmWake(){{}}, armWake(){{}},
    crab:{{setAudioLevel(){{}}, setEmotion(){{}}, showDefeat(){{}}}},
    requestAnimationFrame:callback=>callback(), toolBusyLabel:()=> 'working',
    ...extra
  }});
  ctx.Date={{now:()=>ctx.clock}};
  ctx.setTimeout=callback=>{{const id=++ctx.nextTimer; ctx.timers.set(id,callback); return id;}};
  ctx.clearTimeout=id=>ctx.timers.delete(id);
  ctx.showLive=text=>ctx.log.push(['live',text]);
  ctx.showCaption=(text,role)=>ctx.log.push(['caption',text,role]);
  ctx.setListening=on=>{{ctx.listening=on;}};
  ctx.setVoiceMode=mode=>{{ctx.voiceMode=mode;}};
  ctx.addMsg=()=>new Element();
  ctx.handleFinalText=text=>ctx.sent.push(text);
  ctx.voiceAudio={{pause(){{}}, play:()=>{{ctx.plays++; return Promise.resolve();}}}};
  ctx.MediaRecorder=class {{
    constructor() {{this.state='inactive'; this.mimeType='audio/webm'; ctx.recorders.push(this);}}
    start() {{this.state='recording';}}
    stop() {{
      this.state='inactive';
      queueMicrotask(()=>{{
        this.ondataavailable?.({{data:new Blob(['final audio'],{{type:this.mimeType}})}});
        this.onstop?.();
      }});
    }}
  }};
  ctx.window={{MediaRecorder:ctx.MediaRecorder}};
  return ctx;
}}
const install=(ctx,names)=>vm.runInContext(names.map(extract).join('\\n'),ctx);
{body}
"""
    result = subprocess.run(
        [NODE, "--input-type=module"], input=script, capture_output=True,
        text=True, timeout=15, check=False,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


def test_old_permission_rejection_cannot_clear_new_microphone():
    result = run_screen("""
const first=deferred(), second=deferred(); const requests=[first,second];
const ctx=environment({navigator:{mediaDevices:{getUserMedia:()=>requests.shift().promise}}});
install(ctx,['openMic']);
const old=ctx.openMic(); ctx.micEpoch++;
const current=ctx.openMic(); const stream={getTracks:()=>[]};
second.resolve(stream); await current;
first.reject(Error('permission denied')); await old;
console.log(JSON.stringify({kept:ctx.micStream===stream}));
""")
    assert result["kept"] is True


def test_mute_cancels_playback_and_allows_next_reply():
    result = run_screen("""
const ctx=environment({fetch:async()=>({ok:true,blob:async()=>new Blob(['audio'])})});
install(ctx,['speechAudioDone','speechReset','ttsBlob','playBlob','speechPump','speechSay','speechWanted','cutForSpeech','toggleVoice']);
ctx.speechSay('playing now'); ctx.speechSay('queued tail'); await flush();
const signal=ctx.speechController.signal;
ctx.toggleVoice(); await flush();
const muted={aborted:signal.aborted,running:ctx.speechRunning,speaking:ctx.botSpeaking,queued:ctx.speechQueue.length};
ctx.toggleVoice(); ctx.speechSay('new reply'); await flush();
const plays=ctx.plays; ctx.speechReset(); await flush();
console.log(JSON.stringify({muted,plays}));
""")
    assert result["muted"] == {"aborted": True, "running": False, "speaking": False, "queued": 0}
    assert result["plays"] == 2


def test_rejected_old_play_promise_cannot_end_new_audio():
    result = run_screen("""
const oldPlay=deferred();
const ctx=environment({fetch:async()=>({ok:true,blob:async()=>new Blob(['audio'])})});
ctx.voiceAudio.play=()=>{ctx.plays++; return ctx.plays===1 ? oldPlay.promise : Promise.resolve();};
install(ctx,['speechAudioDone','speechReset','ttsBlob','playBlob','speechPump','speechSay','speechWanted','cutForSpeech']);
ctx.speechSay('old audio'); await flush();
ctx.speechReset(); ctx.speechSay('new audio'); await flush();
const current=ctx.speechEnded;
oldPlay.reject(Error('old play interrupted')); await flush();
const result={stillPlaying:ctx.speechEnded===current && current!==null,speaking:ctx.botSpeaking};
ctx.speechReset(); await flush(); console.log(JSON.stringify(result));
""")
    assert result == {"stillPlaying": True, "speaking": True}


def test_finishing_phrase_cancels_pending_asr_and_partials():
    result = run_screen("""
const final=new AbortController(), partial=new AbortController();
const ctx=environment({asrController:final,partialController:partial,partialBusy:true});
install(ctx,['finishPhrase','closeMic']); ctx.finishPhrase('');
console.log(JSON.stringify({final:final.signal.aborted,partial:partial.signal.aborted,busy:ctx.partialBusy}));
""")
    assert result == {"final": True, "partial": True, "busy": False}


def test_obsolete_partial_503_does_not_disable_new_session():
    result = run_screen("""
const request=deferred();const recorder={state:'recording'};
const ctx=environment({fetch:()=>request.promise,mediaRec:recorder});
install(ctx,['sendPartial']);
const pending=ctx.sendPartial(new Blob(['old'],{type:'audio/webm'}),recorder,ctx.micEpoch);
ctx.micEpoch++; ctx.partialController=null; ctx.partialBusy=false;
request.resolve({status:503,ok:false}); await pending;
console.log(JSON.stringify({enabled:ctx.partialsOn}));
""")
    assert result["enabled"] is True


def test_aborted_turn_cannot_restore_its_caption_over_new_turn():
    result = run_screen("""
const first=deferred(), second=deferred(), read1=deferred(), read2=deferred();
const responses=[first,second], reads=[read1,read2];
const ctx=environment({voiceOn:false,fetch:()=>responses.shift().promise});
install(ctx,['sendChat','speechAudioDone','speechReset','speechWanted','speechCutIndex']);
const old=ctx.sendChat('first question',false);
first.resolve({ok:true,body:{getReader:()=>({read:()=>reads.shift().promise})}}); await flush();
read1.resolve({done:false,value:new TextEncoder().encode('event: delta\\ndata: {"chunk":"obsolete answer"}\\n\\n')}); await flush();
const current=ctx.sendChat('new question',false);
ctx.showCaption('new question','user');
read2.reject(Error('aborted')); await old;
const last=ctx.log.filter(x=>x[0]==='caption').at(-1);
const busy=ctx.chatBusy;
second.resolve({ok:true,body:{getReader:()=>({read:async()=>({done:true})})}}); await current;
console.log(JSON.stringify({last,busy}));
""")
    assert result == {"last": ["caption", "new question", "user"], "busy": True}


def test_rollover_keeps_recording_during_recognition_and_playback():
    result = run_screen("""
const request=deferred();let uploaded;
const ctx=environment({micStream:{getTracks:()=>[]},botSpeaking:true,
  fetch:(_url,options)=>{uploaded=options.body.get('audio');return request.promise;}});
install(ctx,['startRecorder','sendToAsr','sendPartial']);
ctx.startRecorder(true);ctx.spoke=true;ctx.mediaRec.stop();await flush();
const state=ctx.mediaRec.state, count=ctx.recorders.length, speaking=ctx.botSpeaking;
request.resolve({ok:true,json:async()=>({text:'hello'})}); await flush();
console.log(JSON.stringify({state,count,speaking,audio:await uploaded.text(),sent:ctx.sent}));
""")
    assert result == {"state": "recording", "count": 2, "speaking": True,
                      "audio": "final audio", "sent": ["hello"]}


def test_old_recorder_final_chunk_cannot_contaminate_replacement():
    result = run_screen("""
let uploaded;
const ctx=environment({micStream:{getTracks:()=>[]},
  fetch:async(_url,options)=>{uploaded=options.body.get('audio');return {ok:true,json:async()=>({text:'new'})};}});
install(ctx,['startRecorder','sendToAsr','sendPartial']);
ctx.startRecorder(true);const old=ctx.mediaRec;
ctx.micEpoch++;ctx.startRecorder(true);
old.ondataavailable({data:new Blob(['obsolete audio'])});
ctx.spoke=true;ctx.mediaRec.stop();await flush();
console.log(JSON.stringify({audio:await uploaded.text(),sent:ctx.sent}));
""")
    assert result == {"audio": "final audio", "sent": ["new"]}


def test_browser_recognition_does_not_send_the_playing_reply_back_to_bot():
    result = run_screen("""
const ctx=environment({fetch:async()=>({ok:true,blob:async()=>new Blob(['audio'])})});
ctx.SR=class {start(){}};ctx.sendChat=text=>ctx.sent.push(text);
install(ctx,['speechAudioDone','speechReset','ttsBlob','playBlob','speechPump','speechSay','speechWanted',
  'cutForSpeech','startRecognition','heardWhileSpeaking','bargeIn','handleFinalText','isPlaybackEcho']);
const answer='The weather is sunny in Berlin.';
ctx.speechSay(answer);await flush();ctx.startRecognition(true);
const result=[{transcript:answer}];result.isFinal=true;
ctx.recognition.onresult({resultIndex:0,results:[result]});await flush();
const observed={sent:ctx.sent,speaking:ctx.botSpeaking};
ctx.speechReset();await flush();console.log(JSON.stringify(observed));
""")
    assert result == {"sent": [], "speaking": True}


def test_recognized_echo_after_playback_does_not_create_a_new_turn():
    result = run_screen("""
const ctx=environment({fetch:async()=>({ok:true,blob:async()=>new Blob(['audio'])})});
ctx.sendChat=text=>ctx.sent.push(text);
install(ctx,['speechAudioDone','speechReset','ttsBlob','playBlob','speechPump','speechSay','speechWanted',
  'cutForSpeech','handleFinalText','bargeIn','isPlaybackEcho']);
ctx.speechSay('The weather is sunny in Berlin.');await flush();
ctx.speechAudioDone();await flush();ctx.clock=400;
ctx.handleFinalText('the weather is sunny in berlin');
ctx.handleFinalText('What about tomorrow?');
ctx.handleFinalText('stop');
console.log(JSON.stringify({sent:ctx.sent,interrupted:ctx.replyInterrupted}));
""")
    assert result == {"sent": ["What about tomorrow?"], "interrupted": True}


@pytest.mark.parametrize("tools_response", [False, True])
def test_screen_gateway_failure_never_resubmits_same_turn(monkeypatch, tools_response):
    """An accepted stream may have acted before its first visible event."""
    tree = ast.parse((SCREEN.parents[1] / "brains.py").read_text())
    function = next(node for node in tree.body if isinstance(node, ast.AsyncFunctionDef)
                    and node.name == "chat_openclaw")

    class NeedsTools(Exception):
        pass

    class Activity:
        session_key = "review-session"

        def __init__(self, *args, **kwargs):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, *args):
            return False

    error = NeedsTools() if tools_response else RuntimeError("stream disconnected")
    stream = AsyncMock(side_effect=error)
    plain = AsyncMock(return_value=("replayed", []))
    monkeypatch.setitem(sys.modules, "mobile_api", SimpleNamespace(current_turn_options=lambda: None))
    monkeypatch.setitem(sys.modules, "mobile_routing", SimpleNamespace(seed_history=lambda: False))
    namespace = {
        "ChatHistory": list,
        "cfg": SimpleNamespace(
            get_openclaw_token=lambda: "synthetic-token", OPENCLAW_AGENT="review",
            OPENCLAW_BASE_URL="https://gateway.invalid", CHAT_OPENCLAW_TIMEOUT_S=1,
            CHAT_OPENCLAW_WALL_S=1, httpx_trust_env=lambda _: False,
        ),
        "openclaw_models": SimpleNamespace(catalog=AsyncMock(return_value=[{
            "id": "openai/gpt-6-luna", "available": True,
        }])),
        "_build_messages": lambda *args: [], "GatewayActivity": Activity,
        "_stream_openai_compatible": stream, "_NeedsTools": NeedsTools,
        "_call_openai_compatible_with_tools": plain, "log": MagicMock(),
    }
    exec(compile(ast.Module(body=[function], type_ignores=[]), "brains.py", "exec"), namespace)
    with pytest.raises(type(error)):
        asyncio.run(namespace["chat_openclaw"](
            "stop the timer", "system", [], emit=AsyncMock(),
            session_key="review-session", screen_voice_model="openai/gpt-6-luna",
        ))
    assert stream.await_count == 1
    plain.assert_not_awaited()


def test_mic_accessible_state_follows_pause_and_localization():
    result = run_screen("""
const ctx=environment({faceCaption:{classList:{contains:()=>false}},
  MODES:{open:{labelKey:'mode.open'}},$:()=>null});
ctx.document.querySelectorAll=()=>[];
ctx.micButtons=[new Element(),new Element()];
for(const button of ctx.micButtons) {button.attributes={};button.setAttribute=(key,value)=>{button.attributes[key]=value;};}
install(ctx,['micStateLabel','setListening','renderMode']);
ctx.setListening(false);const idle=ctx.micButtons.map(button=>({...button.attributes}));
ctx.setListening(true);ctx.t=key=>'translated:'+key;ctx.renderMode();
console.log(JSON.stringify({idle,active:ctx.micButtons.map(button=>button.attributes)}));
""")
    assert result["idle"] == [{"aria-label": "voice.start", "aria-pressed": "false"}] * 2
    assert result["active"] == [{"aria-label": "translated:voice.listeningAll", "aria-pressed": "true"}] * 2
