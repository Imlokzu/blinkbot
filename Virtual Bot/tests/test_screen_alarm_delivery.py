"""Calendar occurrences remain deliverable when their next snapshot arrives first."""
import base64
from pathlib import Path
import shutil
import subprocess

import pytest

NODE = shutil.which("node")
SOURCE = Path(__file__).resolve().parents[1] / "static/screen/timer-delivery.js"


@pytest.mark.skipif(not NODE, reason="Node is required to execute the actual screen delivery class")
def test_recurring_occurrence_delivery_and_old_timer_fences():
    module = "data:text/javascript;base64," + base64.b64encode(SOURCE.read_bytes()).decode()
    script = r'''
import assert from 'node:assert/strict';
const { TimerDeliveries } = await import(MODULE);
const alarm = (id, activation, ends_at = 1000) => ({
  id, activation, ends_at, state: 'running', kind: 'reminder', repeat: 'daily',
});

// An HTTP snapshot may arrive before the live SSE occurrence from the same tick.
const outOfOrder = new TimerDeliveries();
const due = alarm('a', 1), next = alarm('a', 2, 87400);
outOfOrder.snapshot([next]);
assert.equal(outOfOrder.shouldDeliverDue(due, 1001), true);
outOfOrder.mark(due);
assert.equal(outOfOrder.shouldDeliverDue(due, 1001), false);
assert.equal(outOfOrder.delivered(next), false, 'old receipt must not poison next activation');
assert.equal(outOfOrder.snapshot([due])[0].activation, 2);

// If the original screen countdown already rang, the batch is only an update.
const tickFirst = new TimerDeliveries();
tickFirst.snapshot([due]); tickFirst.mark(due); tickFirst.snapshot([next]);
assert.equal(tickFirst.shouldDeliverDue(due, 1001), false);

// Deliver every simultaneous occurrence before installing next-generation state.
const batch = new TimerDeliveries();
const old = [alarm('x', 1), alarm('y', 7)];
batch.snapshot(old);
let rings = 0;
for (const item of old) if (batch.shouldDeliverDue(item, 1000)) { batch.mark(item); rings++; }
const future = [alarm('x', 2, 87400), alarm('y', 8, 87400)];
batch.snapshot(future);
for (const item of old) if (batch.shouldDeliverDue(item, 1000)) rings++;
assert.equal(rings, 2);
assert.equal(batch.delivered(future[0]), false);
assert.equal(batch.delivered(future[1]), false);

const historical = new TimerDeliveries();
historical.snapshot([{...due, state:'done'}]);
assert.equal(historical.shouldDeliverDue(due, 1001), false);
assert.equal(new TimerDeliveries().shouldDeliverDue(due, 1060), true);
for (const [item, now] of [
  [due, 1061], [due, 999], [due, NaN],
  [{...due, kind:'timer'}, 1000], [{...due, repeat:'none'}, 1000],
  [{...due, activation:0}, 1000], [{...due, ends_at:Infinity}, 1000], [null, 1000],
]) assert.equal(new TimerDeliveries().shouldDeliverDue(item, now), false);

const bounded = new TimerDeliveries();
for (let n=1;n<=800;n++) bounded.mark(alarm('one', n));
assert.ok(bounded.occurrences.size <= 512);
assert.equal(bounded.shouldDeliverDue(alarm('one',800),1000),false);
'''.replace("MODULE", repr(module))
    result = subprocess.run([NODE, "--input-type=module", "-e", script], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr


@pytest.mark.skipif(not NODE, reason="Node is required for the actual screen handlers")
def test_screen_rejects_stale_membership_and_clear_preserves_schedules():
    module = "data:text/javascript;base64," + base64.b64encode(SOURCE.read_bytes()).decode()
    screen = SOURCE.with_name("screen.js")
    script = r'''
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const {TimerDeliveries} = await import(MODULE);
const source = readFileSync(SCREEN, 'utf8');
const deliveries = new TimerDeliveries();
const next = {id:'recurring',activation:2,state:'running',repeat:'daily'};
const other = {id:'new',activation:1,state:'running'};
const sandbox = {timerDeliveries:deliveries,timers:[],timerSkew:0,renderTimers(){},noticesChanged(){}};
vm.createContext(sandbox);
vm.runInContext(source.match(/function setTimers\(list, serverNow\) \{[\s\S]*?\n\}/)[0], sandbox);
sandbox.setTimers([next,other],1000);
sandbox.setTimers([{...next,activation:1}],500);
assert.deepEqual(sandbox.timers.map(item=>item.id),['recurring','new']);
const before = sandbox.timerSkew;
sandbox.setTimers([{...next,activation:1}],100);
assert.equal(sandbox.timerSkew,before);

let clear, cancelled=[];
Object.assign(sandbox,{
  $:()=>({addEventListener:(name,callback)=>clear=callback}),wake(){},localNotices:new Map(),
  reminders:()=>[next,{id:'weekdays',repeat:'weekdays'},{id:'once',repeat:'none'},{id:'relative'}],
  reminderFired:()=>true,timerAction:body=>cancelled.push(body.id),
  fetch:async()=>({ok:true,json:async()=>({notices:[]})}),setNotices(){},
});
const start=source.indexOf('$("noticesClear").addEventListener');
const end=source.indexOf('\nfunction onNoticeEvent',start);
vm.runInContext(source.slice(start,end),sandbox);
await clear({stopPropagation(){}});
assert.deepEqual(cancelled,['once','relative']);
'''.replace("MODULE", repr(module)).replace("SCREEN", repr(str(screen)))
    result = subprocess.run([NODE, "--input-type=module", "-e", script], capture_output=True, text=True)
    assert result.returncode == 0, result.stderr
