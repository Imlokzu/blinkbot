"""A name call acknowledges hearing without a model turn or duplicate voice."""
from __future__ import annotations

import json
import shutil
import subprocess
from pathlib import Path

import pytest

from test_screen_voice_review import run_screen

SCREEN = Path(__file__).resolve().parents[1] / 'static' / 'screen'
NODE = shutil.which('node')
pytestmark = pytest.mark.skipif(NODE is None, reason='node is not installed')


def run_cue(body):
    script = (f"import {{WakeCueCache}} from {json.dumps((SCREEN / 'wake-cues.js').as_uri())};\n"
              f"import {{parseWake}} from {json.dumps((SCREEN / 'wake.js').as_uri())};\n" + body)
    result = subprocess.run([NODE, '--input-type=module', '-e', script], capture_output=True,
                            text=True, timeout=15)
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout)


@pytest.mark.parametrize('armed', [False, True])
def test_name_and_polite_prefix_are_a_call_even_in_follow_up(armed):
    result = run_cue(f"console.log(JSON.stringify(['Claude','hey Claude','hello Claude'].map("
                     f"s=>parseWake(s,'claude',{str(armed).lower()}))));")
    assert result == [{'action': 'arm', 'text': ''}] * 3


def test_name_with_request_is_still_a_request():
    result = run_cue("""
console.log(JSON.stringify(['Claude what time is it','hey Claude stop the music'].map(s=>parseWake(s,'claude',false))));
""")
    assert result == [{'action': 'send', 'text': 'what time is it'},
                      {'action': 'send', 'text': 'stop the music'}]


def test_full_configured_name_and_shorthand_both_wake_without_becoming_requests():
    result = run_cue("""
console.log(JSON.stringify(['Клод Бот','Клод','hey Claude Bot','Claude','Клод Бот котра година'].map(
 s=>parseWake(s,'Клод Бот',false))));
""")
    assert result == [{'action': 'arm', 'text': ''}] * 4 + [{'action': 'send', 'text': 'котра година'}]


def test_warm_cues_reuse_audio_without_more_synthesis():
    result = run_cue("""
let calls=0;const cache=new WakeCueCache(async text=>{calls++;return new Blob([text]);});
await cache.prepare('en:1:voice1',['Hi!','What do you need?']);
await cache.prepare('en:1:voice1',['Hi!','What do you need?']);
console.log(JSON.stringify({calls,text:await cache.get('en:1:voice1','Hi!').text(),wrongScope:cache.get('uk:1:voice1','Hi!')}));
""")
    assert result == {'calls': 2, 'text': 'Hi!', 'wrongScope': None}


def test_pending_prefetch_is_reused_and_old_scope_cannot_refill_cache():
    result = run_cue("""
const releases=[];const signals=[];let calls=0;
const cache=new WakeCueCache((text,signal)=>{calls++;signals.push(signal);return new Promise(r=>releases.push(r));});
const old=cache.prepare('en:1:voice1',['Hi!']);await Promise.resolve();
const same=cache.get('en:1:voice1','Hi!');cache.prepare('en:1:voice1',['Hi!']);
const current=cache.prepare('uk:1:voice1',['Hello in UK']);await Promise.resolve();
releases[0](new Blob(['obsolete']));await old;
releases[1](new Blob(['current']));await current;
console.log(JSON.stringify({calls,oldAborted:signals[0].aborted,old:cache.get('en:1:voice1','Hi!'),
 current:await cache.get('uk:1:voice1','Hello in UK').text(),pendingReused:!!same}));
""")
    assert result == {'calls': 2, 'oldAborted': True, 'old': None,
                      'current': 'current', 'pendingReused': True}


WAKE_STUBS = """
const ctx=environment({wakeCuePendingFinal:false,wakeCueNumber:0,ttsVoiceSignature:'elevenlabs:1',
  WAKE_ARM_MS:8000,FOLLOW_UP_MS:8000,
  getLang:()=> 'en',t:k=>({'voice.wakeHi':'Hi!','voice.wakeNeed':'What do you need?'}[k] || k)});
ctx.cues=[];ctx.speechSay=(text,_onStart,audio)=>{ctx.cues.push({text,audio:!!audio});return true;};
ctx.sendChat=text=>ctx.sent.push(text);
install(ctx,['speechWanted','wakeCueScope','warmWakeReplies','acknowledgeWake','handleWakeInterim',
  'handleFinalText','isPlaybackEcho','bargeIn','speechReset','speechAudioDone','armWake','disarmWake']);
"""


@pytest.mark.parametrize('mode', ['open', 'wake'])
def test_repeated_interims_and_final_greet_once_without_model(mode):
    result = run_screen(WAKE_STUBS + f"ctx.voiceMode={json.dumps(mode)};" + """
ctx.handleWakeInterim('Claude');ctx.handleWakeInterim('Claude');ctx.handleFinalText('Claude');
console.log(JSON.stringify({cues:ctx.cues.map(c=>c.text),sent:ctx.sent,pending:ctx.wakeCuePendingFinal}));
""")
    assert result == {'cues': ['Hi!'], 'sent': [], 'pending': False}


def test_separate_name_calls_get_separate_short_cues():
    result = run_screen(WAKE_STUBS + """
ctx.handleFinalText('Claude');ctx.handleFinalText('Claude');
console.log(JSON.stringify({cues:ctx.cues.map(c=>c.text),sent:ctx.sent}));
""")
    assert result == {'cues': ['Hi!', 'What do you need?'], 'sent': []}


def test_name_and_request_in_same_phrase_go_to_model_once():
    result = run_screen(WAKE_STUBS + """
ctx.voiceMode='wake';ctx.handleWakeInterim('Claude what time is it');
ctx.handleFinalText('Claude what time is it');
console.log(JSON.stringify({cues:ctx.cues,sent:ctx.sent}));
""")
    assert result == {'cues': [], 'sent': ['what time is it']}


def test_wake_name_arms_a_follow_up_without_repeating_name():
    result = run_screen(WAKE_STUBS + """
ctx.voiceMode='wake';ctx.handleFinalText('Claude');ctx.handleFinalText('what time is it');
console.log(JSON.stringify({cues:ctx.cues.map(c=>c.text),sent:ctx.sent}));
""")
    assert result == {'cues': ['Hi!'], 'sent': ['what time is it']}


def test_new_command_cancels_waiting_cue_without_stalling_next_audio():
    result = run_screen("""
const pending=deferred();const ctx=environment({fetch:async()=>({ok:true,blob:async()=>new Blob(['fresh'])})});
install(ctx,['speechAudioDone','speechReset','ttsBlob','playBlob','speechPump','speechSay','speechWanted','cutForSpeech']);
ctx.speechSay('Hi!',null,pending.promise);await flush();
ctx.speechReset();ctx.speechSay('fresh reply');await flush();
const result={plays:ctx.plays,speaking:ctx.botSpeaking};
pending.resolve(new Blob(['old cue']));await flush();
result.afterLateCue=ctx.plays;ctx.speechReset();await flush();console.log(JSON.stringify(result));
""")
    assert result == {'plays': 1, 'speaking': True, 'afterLateCue': 1}


def test_name_cues_are_localized_in_both_languages():
    source = (SCREEN / 'i18n.js').read_text()
    assert source.count('"voice.wakeHi"') == 2
    assert source.count('"voice.wakeNeed"') == 2
