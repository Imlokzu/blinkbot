/** Connector UI exercises only isolated API fixtures, never real Google mutations. */
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';

const session = `connectors-test-${process.pid}`;
const origin = process.env.DASHBOARD_TEST_URL || 'http://127.0.0.1:5181';
const browser = (...args) => execFileSync('agent-browser', ['--session', session, ...args], { encoding: 'utf8' });
const evaluate = (code) => JSON.parse(browser('--json', 'eval', `(() => eval(${JSON.stringify(code)}))()`)).data.result;
const route = (url, data) => browser('network', 'route', url, '--body', JSON.stringify(data));
const notebook = '00000000-0000-0000-0000-000000000001';
const source = '00000000-0000-0000-0000-000000000002';
const status = { installed: true, profile: 'fixture', connected: null, agent_access: false, login_running: false };
const findButton = (name) => browser('find', 'role', 'button', 'click', '--name', name, '--exact');

try {
  browser('open', `${origin}/static/dash/#/chat`);
  route('**/api/sessions', { sessions: [] });
  route('**/api/setup', { configured: true, profile: { configured: true }, keys_set: {}, languages: [], personas: [], reply_lengths: [] });
  route('**/api/connectors', { available: true, connectors: [
    { id: 'notebooklm', name: 'NotebookLM', kind: 'notebooklm', enabled: true, agent_access: true, browse: true },
    { id: 'actual-openclaw-server', name: 'actual-openclaw-server', kind: 'mcp', enabled: true, agent_access: true, browse: false },
  ] });
  route('**/api/connectors/notebooklm/status', status);
  route('**/api/connectors/notebooklm/check', { ...status, connected: false, code: 'needs_login' });
  route('**/api/connectors/notebooklm/notebooks', { notebooks: [{ id: notebook, title: 'Research notebook' }] });
  route(`**/api/connectors/notebooklm/notebooks/${notebook}/sources`, { sources: [
    { id: source, title: 'Real source title', status: 'ready' },
    { id: 'failed', title: 'Failed source', status: 'error' },
  ] });
  route('**/api/connectors/notebooklm/attach', { url: '/uploads/fixture-source.txt', name: 'Real source title.txt', type: 'text/plain', size: 45 });
  evaluate("localStorage.setItem('claudeBotLang','en')");
  browser('reload');
  browser('wait', '.prompt-bar__send');
  browser('set', 'viewport', '1280', '850');
  browser('click', '.prompt-bar__tool[aria-label="Add to conversation"]');
  findButton('Connectors');
  browser('wait', '--fn', '[...document.querySelectorAll("[role=dialog] button")].some(x=>x.textContent.includes("actual-openclaw-server"))');
  assert.ok(evaluate('document.querySelector("[role=dialog]").textContent.includes("actual-openclaw-server")'), 'the menu uses gateway inventory rather than a hardcoded provider catalog');
  browser('find', 'text', 'NotebookLM', 'click', '--exact');
  browser('wait', '--text', 'Research notebook');
  findButton('Research notebook');
  browser('wait', '--fn', '[...document.querySelectorAll("[role=dialog] button")].some(x=>x.textContent.includes("Real source title"))');
  assert.equal(evaluate('[...document.querySelectorAll("[role=dialog] button")].find(x=>x.textContent.includes("Failed source")).disabled'), true);
  findButton('Real source title');
  browser('wait', '--fn', 'document.querySelector(".prompt-bar__chip")?.textContent.includes("Real source title.txt")');
  assert.equal(evaluate('document.querySelectorAll(".prompt-bar__chip").length'), 1);

  browser('open', `${origin}/static/dash/#/settings?tab=connectors`);
  browser('wait', '#notebook-profile');
  assert.equal(evaluate('document.querySelector("#notebook-profile").value'), 'fixture');
  assert.equal(evaluate('[...document.querySelectorAll("button")].find(x=>x.textContent.includes("Connect to the agent")).disabled'), true, 'an unchecked connection cannot be granted to the agent');
  browser('fill', '#notebook-profile', 'unsaved-profile');
  assert.equal(evaluate('[...document.querySelectorAll("button")].find(x=>x.textContent.trim()==="Check connection").disabled'), true, 'authentication actions use only the saved profile');
  browser('fill', '#notebook-profile', 'fixture');
  findButton('Check connection');
  browser('wait', '--text', 'Your NotebookLM login expired.');
  assert.equal(evaluate('document.querySelectorAll("input[type=password]").length'), 0, 'Google credentials stay in the installed API profile');
  browser('set', 'viewport', '390', '844');
  assert.equal(evaluate('document.documentElement.scrollWidth <= innerWidth'), true);
  console.log('PASS: real inventory shape, notebook/source selection, source readiness, attachment, expired-auth setup and mobile layout');
} finally {
  browser('close');
}
