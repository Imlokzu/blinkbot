/** Search affordance regression: hover, focus and reduced-motion stay legible. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:8100';
const path = process.env.DASHBOARD_TEST_PATH || '/dash/';
const menu = '.model-effort-menu';
const catalog = {
  models: [{ id: 'openai/search-fixture', label: 'Search fixture', context: 200000 }],
  selected: 'openai/search-fixture',
  default: 'openai/search-fixture',
  thinking: 'high',
  thinking_levels: ['high'],
  available: true,
};

const parseDurations = value => value.split(',').map(part => {
  const trimmed = part.trim();
  if (trimmed.endsWith('ms')) return Number.parseFloat(trimmed) / 1000;
  if (trimmed.endsWith('s')) return Number.parseFloat(trimmed);
  return 0;
});

const readStyles = `() => {
  const button = document.querySelector('.model-picker-search-button');
  const style = getComputedStyle(button);
  const accentProbe = document.createElement('span');
  accentProbe.style.color = 'var(--c-accent)';
  document.body.append(accentProbe);
  const accent = getComputedStyle(accentProbe).color;
  accentProbe.remove();
  const pseudo = pseudoName => {
    const pseudoStyle = getComputedStyle(button, pseudoName);
    return { content: pseudoStyle.content, background: pseudoStyle.backgroundColor,
      border: pseudoStyle.borderColor, boxShadow: pseudoStyle.boxShadow,
      opacity: pseudoStyle.opacity, transform: pseudoStyle.transform };
  };
  return {
    color: style.color,
    accent,
    background: style.backgroundColor,
    transform: style.transform,
    transitionProperty: style.transitionProperty.split(',').map(item => item.trim()),
    transitionDuration: style.transitionDuration,
    animationName: style.animationName,
    outlineColor: style.outlineColor,
    outlineStyle: style.outlineStyle,
    outlineWidth: style.outlineWidth,
    before: pseudo(':before'),
    after: pseudo(':after'),
  };
}`;

const setup = browser => {
  const route = (url, body) => browser('network', 'route', url, '--body', JSON.stringify(body));
  route('**/api/auth/config', { disabled: true });
  route('**/api/sessions', { sessions: [] });
  route('**/api/projects', { projects: [] });
  route('**/api/workspace/info**', { session_path: 'sessions/default' });
  route('**/api/brain/models', catalog);
  route('**/api/brain/model', { ok: true, selected: catalog.selected });
  route('**/api/brain/thinking', { ok: true, thinking: catalog.thinking });
  browser('open', `${origin}/docs`);
  browser('eval', `localStorage.setItem('claudeBotLang', 'en');
    localStorage.removeItem('claudeBotRecentModels');
    localStorage.removeItem('claude-bot:brain-models:v6');`);
  browser('open', `${origin}${path}#/chat`);
  browser('wait', '[data-brain-choice-trigger]');
};

const openPicker = browser => {
  const trigger = browser('--json', 'eval', `(() => {
    const visible = [...document.querySelectorAll('[data-brain-choice-trigger]')]
      .filter(node => node.getBoundingClientRect().width > 0 && node.getBoundingClientRect().height > 0);
    visible.forEach(node => node.removeAttribute('data-search-icon-trigger'));
    visible[0]?.setAttribute('data-search-icon-trigger', '');
    return visible.length;
  })()`);
  assert.equal(JSON.parse(trigger).data.result, 1, 'one visible model trigger opens the picker');
  browser('click', '[data-search-icon-trigger]');
  browser('wait', menu);
  browser('wait', '--fn', `document.querySelector('${menu}').getAnimations({ subtree: true })
    .every(animation => animation.playState !== 'running')`);
};

const run = reduced => {
  const session = `search-icon-${process.pid}-${reduced ? 'reduced' : 'full'}`;
  const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
  const evaluate = code => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
  const styles = code => evaluate(`(${code})()`);
  try {
    browser('open', 'about:blank');
    browser('set', 'viewport', '1440', '900');
    if (reduced) browser('set', 'media', 'light', 'reduced-motion');
    setup(browser);
    openPicker(browser);

    const idle = styles(readStyles);
    browser('hover', '.model-picker-search-button');
    browser('wait', '--fn', 'document.querySelector(".model-picker-heading").hasAttribute("data-search-open")');
    evaluate('new Promise(resolve => setTimeout(resolve, 260))');
    const hover = styles(readStyles);
    const transitionNames = hover.transitionProperty;
    const transitionSeconds = parseDurations(hover.transitionDuration);
    const haloChanged = hover.background !== idle.background
      || hover.before.background !== idle.before.background
      || hover.after.background !== idle.after.background
      || hover.before.border !== idle.before.border
      || hover.after.border !== idle.after.border
      || hover.before.boxShadow !== idle.before.boxShadow
      || hover.after.boxShadow !== idle.after.boxShadow;

    assert.equal(hover.color, hover.accent, 'hover exposes the accent color');
    assert.ok(haloChanged, 'hover adds a visible accent halo or surface');
    if (!reduced) {
      assert.ok(transitionNames.includes('transform') || transitionNames.includes('all'),
        'search icon hover animates its transform');
      assert.ok(transitionNames.includes('color') || transitionNames.includes('all'),
        'search icon hover animates its color');
      assert.ok(Math.max(...transitionSeconds) > 0.05, 'full motion keeps a perceptible transition');
      assert.notEqual(hover.transform, idle.transform, 'hover changes the icon transform');
    } else {
      assert.ok(Math.max(...transitionSeconds) <= 0.01, 'reduced motion removes the hover transition');
    }

    browser('press', 'Escape');
    browser('wait', '--fn', 'document.querySelector(".model-picker-search-button").ariaExpanded === "false"');
    browser('press', 'Escape');
    browser('wait', '--fn', `!document.querySelector('${menu}')`);
    openPicker(browser);
    browser('press', 'Shift+Tab');
    assert.equal(evaluate('document.activeElement.matches(".model-picker-search-button")'), true,
      'keyboard focus reaches the search icon');
    const focus = styles(readStyles);
    assert.equal(focus.outlineColor, focus.accent, 'focus-visible outline uses the accent');
    assert.notEqual(focus.outlineStyle, 'none', 'search icon has a visible focus outline');
    assert.ok(Number.parseFloat(focus.outlineWidth) >= 2, 'focus outline remains at least two pixels');

    console.log(`PASS: search icon hover color, halo, motion, focus outline (reduced-motion=${reduced})`);
  } catch (error) {
    console.error(browser('snapshot', '-i'));
    console.error(browser('errors'));
    throw error;
  } finally {
    browser('close');
  }
};

if (process.platform === 'darwin') execFileSync('osascript', ['-e', 'set volume output muted true']);
run(false);
run(true);
