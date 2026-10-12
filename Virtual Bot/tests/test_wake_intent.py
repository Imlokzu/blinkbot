"""Microphone level confirms speech without inventing wake-command intent."""

import json

import pytest

from test_wake_feedback_review import NODE, run_wake

pytestmark = pytest.mark.skipif(NODE is None, reason='node is not installed')


def recorded(body, *, mode='wake', busy='both', armed=False):
    return run_wake(f"""
const ctx=environment({{voiceMode:{json.dumps(mode)},micStream:{{getTracks:()=>[]}}}});
const reply=new AbortController();
ctx.activeChat={str(busy in ['generation', 'both']).lower()} ? reply : null;
ctx.botSpeaking={str(busy in ['playback', 'both']).lower()};
const response=deferred();
ctx.fetch=(url,options)=>url==='/api/asr' ? response.promise
  : Promise.resolve({{ok:true,blob:async()=>new Blob(['inert cue'])}});
ctx.startRecorder(true);
{f'ctx.armWake(8000);' if armed else ''}
const speak=()=>{{for(const stamp of [0,100,200]){{ctx.clock=stamp;ctx.onMicLevel(.3);}}}};
const finish=async text=>{{ctx.mediaRec.stop();response.resolve({{ok:true,json:async()=>({{text}})}});await flush();}};
{body}
""")


@pytest.mark.parametrize('busy', ['idle', 'generation', 'playback', 'both'])
def test_unarmed_wake_speech_neither_interrupts_nor_dispatches(busy):
    result = recorded("""
speak();const captured=ctx.mediaRec.wakeArmed;const before=reply.signal.aborted;
await finish('This sentence is for someone else');
console.log(JSON.stringify({captured,before,after:reply.signal.aborted,sent:ctx.sent,armed:ctx.wakeArmed}));
""", busy=busy)
    assert result == {'captured': False, 'before': False, 'after': False, 'sent': [], 'armed': False}


@pytest.mark.parametrize('busy', ['generation', 'playback', 'both'])
def test_open_conversation_keeps_level_triggered_interruption(busy):
    result = recorded("""
speak();const before=reply.signal.aborted;const stoppedPlayback=!ctx.botSpeaking;
await finish('A new request');
console.log(JSON.stringify({before,stoppedPlayback,sent:ctx.sent}));
""", mode='open', busy=busy)
    assert result == {'before': busy != 'playback', 'stoppedPlayback': True, 'sent': ['A new request']}


@pytest.mark.parametrize('phrase', ['stop', 'Claude stop'])
@pytest.mark.parametrize('busy', ['generation', 'playback', 'both'])
def test_explicit_stop_waits_for_text_then_cancels_without_a_model_turn(phrase, busy):
    result = recorded(f"""
speak();const before=reply.signal.aborted;await finish({json.dumps(phrase)});
console.log(JSON.stringify({{before,after:reply.signal.aborted,speaking:ctx.botSpeaking,sent:ctx.sent}}));
""", busy=busy)
    assert result == {'before': False, 'after': busy != 'playback', 'speaking': False, 'sent': []}


@pytest.mark.parametrize('phrase', ['Claude what time is it', 'hey Claude set a timer'])
def test_name_plus_request_remains_an_addressed_command(phrase):
    result = recorded(f"""
speak();const before=reply.signal.aborted;await finish({json.dumps(phrase)});
console.log(JSON.stringify({{before,sent:ctx.sent}}));
""")
    assert result == {'before': False, 'sent': [phrase.split('Claude ', 1)[1]]}


def test_recognized_name_interim_interrupts_without_model_dispatch():
    result = recorded("""
speak();const before=reply.signal.aborted;ctx.handleWakeInterim('Claude');await flush();
const afterName=reply.signal.aborted;await finish('Claude');
console.log(JSON.stringify({before,afterName,sent:ctx.sent,plays:ctx.plays}));
""")
    assert result == {'before': False, 'afterName': True, 'sent': [], 'plays': 1}


def test_invited_follow_up_keeps_original_intent_after_global_window_expires():
    result = recorded("""
speak();const captured=ctx.mediaRec.wakeArmed;ctx.clock=8500;ctx.disarmWake();
await finish('what time is it');
console.log(JSON.stringify({captured,aborted:reply.signal.aborted,sent:ctx.sent,armed:ctx.wakeArmed}));
""", armed=True)
    assert result == {'captured': True, 'aborted': True, 'sent': ['what time is it'], 'armed': False}


def test_invitation_opened_after_first_sound_cannot_authorize_that_utterance():
    result = recorded("""
ctx.clock=0;ctx.onMicLevel(.3);const atFirstSound=ctx.mediaRec.wakeArmed;
ctx.clock=100;ctx.armWake(8000);ctx.onMicLevel(.3);
ctx.clock=200;ctx.onMicLevel(.3);const captured=ctx.mediaRec.wakeArmed;
await finish('This sentence is for someone else');
console.log(JSON.stringify({atFirstSound,captured,aborted:reply.signal.aborted,sent:ctx.sent}));
""")
    assert result == {'atFirstSound': False, 'captured': False, 'aborted': False, 'sent': []}


@pytest.mark.parametrize('noise', [False, True])
def test_candidate_start_preserves_real_invitation_but_noise_does_not_latch_it(noise):
    result = recorded(f"""
ctx.armWake(8000);ctx.clock=7900;ctx.onMicLevel(.3);
{ 'ctx.clock=7950;ctx.onMicLevel(.01);' if noise else '' }
ctx.clock=8001;ctx.disarmWake();ctx.onMicLevel(.3);
ctx.clock=8201;ctx.onMicLevel(.3);const captured=ctx.mediaRec.wakeArmed;
await finish('what time is it');
console.log(JSON.stringify({{captured,sent:ctx.sent,aborted:reply.signal.aborted}}));
""")
    assert result == {'captured': not noise, 'sent': [] if noise else ['what time is it'], 'aborted': not noise}


def test_recorder_started_without_invitation_does_not_inherit_barge_in_window():
    result = recorded("""
speak();ctx.bargeIn(false);const captured=ctx.mediaRec.wakeArmed;
await finish('This sentence is for someone else');
console.log(JSON.stringify({captured,sent:ctx.sent}));
""")
    assert result == {'captured': False, 'sent': []}


@pytest.mark.parametrize('mode', ['wake', 'open'])
def test_recognized_playback_echo_is_never_a_command(mode):
    result = recorded("""
ctx.voiceAudio.speechEchoes=[{text:'This is the current reply',expires:10000}];
speak();await finish('This is the current reply');
console.log(JSON.stringify({sent:ctx.sent}));
""", mode=mode)
    assert result['sent'] == []


def test_push_mode_does_not_level_interrupt_an_active_reply():
    result = recorded("""
speak();console.log(JSON.stringify({aborted:reply.signal.aborted,captured:ctx.mediaRec.wakeArmed}));
""", mode='push')
    assert result == {'aborted': False, 'captured': False}


@pytest.mark.parametrize('phrase', ['This sentence is for someone else', 'Claude what time is it', 'stop'])
def test_browser_fallback_unarmed_intent_matches_recorder_policy(phrase):
    result = run_wake(f"""
const ctx=environment();const reply=new AbortController();ctx.activeChat=reply;ctx.botSpeaking=true;
ctx.startRecognition(true);deliver(ctx,{json.dumps(phrase)},true);await flush();
console.log(JSON.stringify({{aborted:reply.signal.aborted,sent:ctx.sent}}));
""")
    assert result == {'aborted': phrase != 'This sentence is for someone else',
                      'sent': ['what time is it'] if phrase == 'Claude what time is it' else []}
