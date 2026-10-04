/** Inspect actual browser keyframes and layout, including native reduced motion. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
for (const reduced of [false, true]) {
  const session = `attachment-motion-${process.pid}-${reduced}`;
  const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
  const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
  const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
  const trigger = '.prompt-bar__tool[aria-label="Add to conversation"]';
  try {
    browser('open', 'about:blank');
    if (reduced) browser('set', 'media', 'light', 'reduced-motion');
    route('**/api/auth/config', { disabled: true });
    route('**/api/setup', { configured:true, profile:{ configured:true, name:'Fixture', language:'en', persona:'friendly', reply_length:'balanced', use_emoji:true, spontaneous:false }, languages:[], personas:[], reply_lengths:[], models:[], selected_model:'', keys_set:{} });
    route('**/api/sessions', { sessions: [{ id: 'motion', title: 'Opening motion', count: 1 }] });
    route('**/api/sessions/motion', { id:'motion', messages:[{ role:'user', content:'A reference document.', attachments:[
      { url:'/uploads/motion-notes.md', name:'Motion notes.md', type:'text/markdown', size:80 },
    ] }] });
    route('**/api/chat/attachment-preview**', { text:'# A reference\n\nPreview text stays readable while opening.', truncated:false, type:'text/markdown', size:80 });
    route('**/api/brain/models', { models:[{id:'test',label:'Test',context:200000}], selected:'test', thinking:'high', thinking_levels:['high'] });
    route('**/api/**', {});
    browser('open', `${origin}/static/dash/#/chat`);
    evaluate("localStorage.setItem('claudeBotLang','en')");
    browser('reload');
    browser('wait', '[data-session-id="motion"]');
    browser('click', '[data-session-id="motion"]');
    browser('wait', '.attachment-card-open');
    for (const [width, height] of [[1440,960],[390,844]]) {
      browser('set','viewport',String(width),String(height));
      browser('wait','.prompt-bar__send');
      evaluate(`new Promise((resolve, reject) => {
        const started = performance.now(); let previous, stable = 0;
        const sample = () => {
          const rect = document.querySelector('.prompt-bar').getBoundingClientRect();
          const value = [rect.top, rect.left, rect.width, rect.height];
          stable = previous && value.every((part, index) => Math.abs(part - previous[index]) < .1) ? stable + 1 : 0;
          previous = value;
          if (stable >= 6) resolve(true);
          else if (performance.now() - started > 4000) reject(new Error('Composer did not settle after resize'));
          else requestAnimationFrame(sample);
        }; requestAnimationFrame(sample);
      })`);
      const draftTop = evaluate('document.querySelector(".prompt-bar").getBoundingClientRect().top');
      for (let repeat = 0; repeat < 2; repeat++) {
        browser('click',trigger);
        browser('wait','[data-attachment-menu]');
        const frames = evaluate(`const panel = document.querySelector('[data-attachment-menu]');
          const animation = panel.getAnimations().find(animation => animation.animationName === 'attach-menu-open');
          const rows = [...panel.querySelectorAll('.attach-action-row, .attach-media-tile')].map(row => ({name:getComputedStyle(row).animationName, delay:parseFloat(getComputedStyle(row).animationDelay)}));
          const samples = [];
          if (animation) {
            animation.pause();
            for (const time of [0,120,240]) {
              animation.currentTime = time;
              const style = getComputedStyle(panel); samples.push({ opacity:Number(style.opacity), transform:style.transform, filter:style.filter });
            }
            animation.finish();
          }
          ({ name:getComputedStyle(panel).animationName, rows, samples, top:panel.getBoundingClientRect().top, right:panel.getBoundingClientRect().right });`);
        assert.ok(Math.abs(evaluate('document.querySelector(".prompt-bar").getBoundingClientRect().top') - draftTop) < 1, 'opening must not move the composer');
        assert.ok(frames.top >= 55 && frames.right <= width + 1, 'opening must fit the viewport');
        if (reduced) {
          assert.equal(frames.name,'none');
          assert.ok(frames.rows.every(row => row.name === 'none'));
        } else {
          assert.equal(frames.name,'attach-menu-open');
          assert.equal(frames.samples[0].opacity,0);
          assert.equal(frames.samples.at(-1).opacity,1);
          assert.ok(frames.samples.every(sample => sample.filter === 'none'));
          assert.ok(frames.rows.every(row => row.name === 'attach-action-open'));
          assert.ok(frames.rows.at(-1).delay > frames.rows[0].delay);
        }
        // A nested popover owns its Escape before the attachment surface.
        if (evaluate('Boolean(document.querySelector("[data-radix-popper-content-wrapper]"))')) {
          browser('press','Escape');
          browser('wait','--fn','!document.querySelector("[data-radix-popper-content-wrapper]")');
        }
        browser('press','Escape');
        browser('wait','--fn','!document.querySelector("[data-attachment-menu]")');
        assert.equal(evaluate(`document.activeElement.matches(${JSON.stringify(trigger)})`),true);
      }
      browser('click','.attachment-card-open');
      browser('wait','.attachment-dialog');
      const preview = evaluate(`const panel = document.querySelector('.attachment-dialog');
        const animation = panel.getAnimations().find(animation => animation.animationName?.startsWith('attachment-'));
        const name = getComputedStyle(panel).animationName;
        const samples = [];
        if (animation) {
          animation.pause();
          for (const time of [0,120,Number(animation.effect.getTiming().duration)]) {
            animation.currentTime = time;
            const style = getComputedStyle(panel), rect = panel.getBoundingClientRect();
            samples.push({opacity:Number(style.opacity),filter:style.filter,left:rect.left,right:rect.right,center:rect.left+rect.width/2});
          }
          animation.finish();
        }
        ({name,samples});`);
      if (reduced) assert.equal(preview.name,'none');
      else {
        assert.equal(preview.name,width < 760 ? 'attachment-sheet-open' : 'attachment-preview-open');
        assert.equal(preview.samples[0].opacity,0);
        assert.equal(preview.samples.at(-1).opacity,1);
        assert.ok(preview.samples.every(sample => sample.filter === 'none' && sample.left >= -1 && sample.right <= width+1));
        assert.ok(preview.samples.every(sample => Math.abs(sample.center-width/2) < 1),'desktop centering survives animation transforms');
      }
      browser('wait','--fn','Boolean(document.activeElement.closest(".attachment-dialog"))');
      browser('press','Escape');
      browser('wait','--fn','!document.querySelector(".attachment-dialog")');
      browser('wait','--fn','document.activeElement.matches(".attachment-card-open")');
      assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'),true);
    }
    console.log(`PASS: menu/preview opening keyframes, stagger, repeated opening, layout/focus, reduced motion=${reduced}`);
  } finally { browser('close'); }
}
