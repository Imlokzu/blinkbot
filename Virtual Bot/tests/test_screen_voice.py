"""Run the screen's actual microphone code with controlled media and time."""
from __future__ import annotations

import asyncio
import json
import shutil
import subprocess
from pathlib import Path
from unittest.mock import AsyncMock, MagicMock, patch

import pytest

import app_config as cfg
import brains
import elevenlabs_voice
import openclaw_models

SCREEN = Path(cfg.STATIC_DIR) / "screen"
NODE = shutil.which("node")
SOURCE = (SCREEN / "screen.js").read_text()


def js(body, *sections):
    functions = "\n".join(SOURCE[SOURCE.index(start):SOURCE.index(end, SOURCE.index(start))]
                          for start, end in sections)
    script = (f"import {{VoiceTurnDetector}} from {json.dumps((SCREEN / 'voice-turn.js').as_uri())};\n"
              f"import {{parseWake,findWake,isStopCommand}} from {json.dumps((SCREEN / 'wake.js').as_uri())};\n"
              + functions + "\n" + body)
    result = subprocess.run([NODE, "--input-type=module", "-e", script],
                            capture_output=True, text=True, timeout=15)
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


@pytest.mark.skipif(NODE is None, reason="node is not installed")
class TestConversationTiming:
    def test_noise_and_clicks_do_not_interrupt(self):
        assert js("""
const d=new VoiceTurnDetector();const events=[];
for(let time=0;time<3000;time+=30) events.push(d.update(time===900 ? .8 : .02,time));
console.log(JSON.stringify(events.some(e=>e.start || e.end)));
""") is False

    def test_stop_interrupts_then_finishes_after_silence(self):
        result = js("""
const d=new VoiceTurnDetector();const starts=[],ends=[];
for(let time=0;time<=1200;time+=30) {
 const e=d.update(time<360 ? .3 : .01,time);
 if(e.start) starts.push(time);if(e.end) ends.push(time);
}
console.log(JSON.stringify({starts,end:ends[0]}));
""")
        assert result["starts"] == [180]
        assert 950 <= result["end"] <= 1020

    def test_long_thought_has_room_for_a_breath(self):
        assert js("""
const d=new VoiceTurnDetector();
for(let time=0;time<=6500;time+=50) d.update(.3,time);
console.log(JSON.stringify([d.update(.01,7300).end,d.update(.01,7450).end]));
""") == [False, True]


RECORDING = ("function startRecorder(continuous)", "/* ---------- Режим «поговорити»")
LIFECYCLE = ("async function startPhrase()", 'window.addEventListener("pagehide"')
MIC = ("async function openMic()", "/* ---------- Розпізнавання ---------- */")
STUBS = """
let micStream={getTracks:()=>[]},asrAvailable=true,micEpoch=1;
let mediaRec=null,recTimer=0,recStartAt=0,spoke=false;
let partialBusy=false,partialsOn=false,partialController=null,asrController=null;
let voiceMode='open',listening=true,botSpeaking=false,activeChat=null;
let recognition=null,srRestartTimer=0,wakeTimer=0,followUpPending=false,wakeArmed=false;
const PARTIAL_MS=5000,REC_MAX_MS=15000,SR=null,voiceDetector=new VoiceTurnDetector();
const sent=[],live=[],captions=[];
const t=k=>k,showLive=s=>live.push(s),showCaption=s=>captions.push(s);
const handleFinalText=s=>sent.push(s),setVoiceMode=()=>{},setListening=s=>{listening=s};
const crab={setAudioLevel:()=>{}},meter=null,window={MediaRecorder:true};
const recorders=[];
class MediaRecorder {
 constructor(){this.state='inactive';this.mimeType='audio/mp4';recorders.push(this);}
 start(){this.state='recording';}
 stop(){this.state='inactive';this.ondataavailable?.({data:new Blob(['final audio'],{type:this.mimeType})});this.onstop?.();}
}
"""


@pytest.mark.skipif(NODE is None, reason="node is not installed")
class TestRecorderLifecycle:
    def test_mic_records_during_asr_and_preserves_browser_mime(self):
        result = js(STUBS + """
let resolve,upload;
const fetch=async(url,o)=>{upload=o.body.get('audio');return await new Promise(r=>resolve=r)};
startRecorder(true);spoke=true;recorders[0].stop();const state=mediaRec.state;
resolve({ok:true,json:async()=>({text:'hello'})});await new Promise(r=>setImmediate(r));
clearTimeout(recTimer);console.log(JSON.stringify({state,mime:upload.type,name:upload.name,sent}));
""", RECORDING)
        assert result == {"state": "recording", "mime": "audio/mp4", "name": "voice.m4a", "sent": ["hello"]}

    def test_pause_rejects_late_asr_and_does_not_restart(self):
        result = js(STUBS + """
let resolve,signal;
const fetch=async(url,o)=>{signal=o.signal;return await new Promise(r=>resolve=r)};
startRecorder(true);spoke=true;recorders[0].stop();stopContinuous();
resolve({ok:true,json:async()=>({text:'obsolete'})});await new Promise(r=>setImmediate(r));
console.log(JSON.stringify({aborted:signal.aborted,sent,listening,recorder:mediaRec}));
""", RECORDING, LIFECYCLE, MIC)
        assert result == {"aborted": True, "sent": [], "listening": False, "recorder": None}

    def test_silence_does_not_call_paid_recognition(self):
        result = js(STUBS + """
let requests=0;const fetch=()=>{requests++;throw Error('unexpected')};
startRecorder(true);recorders[0].stop();clearTimeout(recTimer);
console.log(JSON.stringify({requests,recorders:recorders.length}));
""", RECORDING)
        assert result == {"requests": 0, "recorders": 2}

    def test_pause_releases_pending_microphone_permission(self):
        result = js(STUBS + """
micStream=null;let resolve,stopped=0;
const navigator={mediaDevices:{getUserMedia:()=>new Promise(r=>resolve=r)}};
const pending=startContinuous();stopContinuous();
resolve({getTracks:()=>[{stop:()=>stopped++}]});await pending;
console.log(JSON.stringify({stopped,listening,recorders:recorders.length,stream:micStream}));
""", RECORDING, LIFECYCLE, MIC)
        assert result == {"stopped": 1, "listening": False, "recorders": 0, "stream": None}


@pytest.mark.skipif(NODE is None, reason="node is not installed")
def test_bare_stop_cancels_generation_and_voice_without_calling_model():
    result = js("""
let followUpPending=true,replyInterrupted=false,voiceMode='open',listening=true,wakeCuePendingFinal=false;
const activeChat=new AbortController();let resets=0;const sent=[],captions=[];
const speechReset=()=>resets++,clearBusy=()=>{},armWake=()=>{},disarmWake=()=>{};
const showCaption=s=>captions.push(s),t=k=>k,sendChat=s=>sent.push(s);
handleFinalText('stop');
console.log(JSON.stringify({aborted:activeChat.signal.aborted,resets,sent,replyInterrupted,captions}));
""", ("function bargeIn(", "/* Браузерний SR:"))
    assert result == {"aborted": True, "resets": 1, "sent": [], "replyInterrupted": True,
                      "captions": ["voice.stopped"]}


@pytest.mark.skipif(NODE is None, reason="node is not installed")
def test_conversation_stop_does_not_swallow_music_and_timer_requests():
    assert js("""
console.log(JSON.stringify(['stop','stop it','please stop','стоп','будь ласка стоп',
  'stop music','stop the timer','pause the video'].map(isStopCommand)));
""") == [True, True, True, True, True, False, False, False]


@pytest.mark.parametrize("channel,voice,spoken,expected", [
    ("screen", True, True, True), ("screen", False, True, True),
    ("screen", True, False, True), ("screen", False, False, False),
    ("chat", True, True, False), ("telegram", True, True, False),
])
def test_luna_is_only_requested_for_screen_voice(channel, voice, spoken, expected):
    gateway = AsyncMock(return_value=("Hello.", [], "openai/gpt-6-luna"))
    with patch.object(cfg, "get_openclaw_token", return_value="synthetic-token"), \
         patch.object(brains, "openclaw_backoff_remaining", return_value=0), \
         patch.object(brains, "build_system_prompt", return_value="system"), \
         patch.object(brains, "chat_openclaw", gateway):
        asyncio.run(brains.chat("hello", channel=channel, voice=voice, spoken=spoken))
    assert ("screen_voice_model" in gateway.await_args.kwargs) is expected


def test_screen_gateway_route_selects_luna_with_turn_local_thinking():
    call = AsyncMock(return_value=("Hello.", []))
    catalog = AsyncMock(return_value=[{"id": "openai/gpt-6-luna", "available": True}])
    with patch.object(cfg, "get_openclaw_token", return_value="synthetic-token"), \
         patch.object(openclaw_models, "catalog", catalog), \
         patch.object(openclaw_models, "chat_route", AsyncMock()) as route, \
         patch.object(brains, "_call_openai_compatible_with_tools", call):
        result = asyncio.run(brains.chat_openclaw("hello", "system", [], screen_voice_model="openai/gpt-6-luna"))
    route.assert_not_awaited()
    assert call.await_args.args[1]["x-openclaw-model"] == "openai/gpt-6-luna"
    assert call.await_args.args[2]["messages"][-1]["content"] == "/think:off hello"
    assert result[2] == "openai/gpt-6-luna"


def test_missing_luna_fails_before_provider_request():
    with patch.object(cfg, "get_openclaw_token", return_value="synthetic-token"), \
         patch.object(openclaw_models, "catalog", AsyncMock(return_value=[])), \
         patch.object(brains, "_call_openai_compatible_with_tools", AsyncMock()) as call:
        with pytest.raises(RuntimeError, match="screen_voice_model_unavailable"):
            asyncio.run(brains.chat_openclaw("hello", "system", [], screen_voice_model="openai/gpt-6-luna"))
    call.assert_not_awaited()


def test_elevenlabs_uses_reply_language_instead_of_forcing_ukrainian():
    response = MagicMock(status_code=200, content=b"synthetic-audio")
    client = MagicMock()
    client.__enter__.return_value.post.return_value = response
    with patch.object(cfg, "get_elevenlabs_key", return_value="synthetic-key"), \
         patch.object(elevenlabs_voice.httpx, "Client", return_value=client):
        assert elevenlabs_voice.synthesize("Hello, how are you?") == b"synthetic-audio"
    assert "language_code" not in client.__enter__.return_value.post.call_args.kwargs["json"]


def test_screen_asr_can_detect_language_without_changing_dictation_default():
    import asr_regolo
    response = MagicMock()
    response.headers = {"content-type": "text/plain"}
    response.text = "Hello."
    client = MagicMock()
    post = AsyncMock(return_value=response)
    client.__aenter__.return_value.post = post
    with patch.object(cfg, "get_regolo_asr_key", return_value="synthetic-key"), \
         patch.object(asr_regolo.httpx, "AsyncClient", return_value=client):
        assert asyncio.run(asr_regolo.transcribe(b"audio", detect_language=True)) == "Hello."
    assert "language" not in post.await_args.kwargs["data"]


@pytest.mark.parametrize("detect,expected", [(False, {}), (True, {"detect_language": True})])
def test_asr_endpoint_forwards_opt_in_language_detection(detect, expected):
    import main
    from fastapi.testclient import TestClient
    transcribe = AsyncMock(return_value="Hello.")
    with patch.object(cfg, "ASR_PROVIDER", "regolo"), \
         patch.object(main.asr_regolo, "is_available", return_value=True), \
         patch.object(main.asr_regolo, "transcribe", transcribe):
        response = TestClient(main.app).post("/api/asr", data={"detect_language": str(detect).lower()},
            files={"audio": ("voice.webm", b"audio", "audio/webm")})
    assert response.status_code == 200
    assert transcribe.await_args.kwargs == expected
