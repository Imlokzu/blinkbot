import type { IntelligentUiSource } from './intelligentUiData';

export type SandboxTheme = Record<string, string>;
export type SandboxControls = { javascript: string; css: string };
const json = (value: unknown) => JSON.stringify(value).replace(/</g, '\\u003c');

/** Only trusted code runs here: its frame-src policy also blocks child navigation. */
export function intelligentUiDocument(source: IntelligentUiSource, channel: string, theme: SandboxTheme, lang: string, title = '', controls: SandboxControls = { javascript: '', css: '' }): string {
  const nonce = channel.replace(/[^a-zA-Z0-9-]/g, '');
  const content = contentDocument(source, channel, theme, lang, controls);
  return `<!doctype html><html lang="${lang === 'uk' ? 'uk' : 'en'}"><head><meta charset="utf-8">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; img-src data: blob:; frame-src 'none'; base-uri 'none'; form-action 'none'">
<style>html,body,iframe{display:block;box-sizing:border-box;width:100%;height:100%;margin:0;border:0}body{overflow:hidden}</style>
</head><body><script nonce="${nonce}">
(() => {
  const channel = ${json(channel)};
  const child = document.createElement('iframe');
  child.sandbox = 'allow-scripts';
  child.referrerPolicy = 'no-referrer';
  child.title = ${json(title)};
  child.allow = "camera 'none'; microphone 'none'; geolocation 'none'; clipboard-read 'none'; clipboard-write 'none'";
  addEventListener('message', event => {
    if(event.data?.channel !== channel) return;
    if(event.source === child.contentWindow && ['ready','error','resize','followup'].includes(event.data.type)) parent.postMessage(event.data, '*');
    else if(event.source === parent && event.data.type === 'theme') child.contentWindow.postMessage(event.data, '*');
  });
  addEventListener('securitypolicyviolation', () => parent.postMessage({channel, type:'error'}, '*'));
  child.srcdoc = ${json(content)};
  document.body.append(child);
})();</script></body></html>`;
}

/** The host owns the document and CSP; generated markup never owns its head. */
function contentDocument(source: IntelligentUiSource, channel: string, theme: SandboxTheme, lang: string, controls: SandboxControls): string {
  const nonce = channel.replace(/[^a-zA-Z0-9-]/g, '');
  return `<!doctype html><html lang="${lang === 'uk' ? 'uk' : 'en'}"><head>
<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'nonce-${nonce}'; style-src 'unsafe-inline'; img-src data: blob:; font-src 'none'; connect-src 'none'; frame-src 'none'; object-src 'none'; media-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'">
<style>
@layer theme,base,generated,components,utilities;
*{box-sizing:border-box}html,body{margin:0;min-width:0}body{padding:16px;background:var(--background);color:var(--text);font:14px/1.5 var(--font-sans,system-ui);overflow-wrap:anywhere}
label{display:block}svg,canvas,img{max-width:100%}table{border-collapse:collapse;width:100%}th,td{padding:8px;text-align:left;border-bottom:1px solid var(--border)}:focus-visible{outline:2px solid var(--accent);outline-offset:3px}
@media(prefers-reduced-motion:reduce){*,*::before,*::after{animation:none!important;transition:none!important;scroll-behavior:auto!important}}
</style></head><body><div id="blink-ui-root"></div><script nonce="${nonce}">
(() => {
  const channel = ${json(channel)}, source = ${json(source)};
  const send = (type, extra = {}) => parent.postMessage({channel, type, ...extra}, '*');
  const setTheme = values => { for (const [key,value] of Object.entries(values)) if (key.startsWith('--') && typeof value === 'string') document.documentElement.style.setProperty(key,value); };
  setTheme(${json(theme)});
  addEventListener('message', event => { if(event.source === parent && event.data?.channel === channel && event.data?.type === 'theme') setTheme(event.data.values); });
  addEventListener('error', () => send('error'));
  addEventListener('unhandledrejection', () => send('error'));
  // Requests only propose text. The host requires its own explicit Send click.
  Object.defineProperty(window, 'BlinkUI', {value: Object.freeze({requestFollowUp(text) {
    if(typeof text === 'string' && text.trim() && text.length <= 2000) send('followup', {text});
  }}), writable:false});
  const fragment = document.createElement('template');
  fragment.innerHTML = source.html;
  fragment.content.querySelectorAll('script,style,link,meta,base,iframe,frame,frameset,object,embed,portal,foreignObject,template').forEach(node => node.remove());
  for(const node of fragment.content.querySelectorAll('*')) for(const attr of [...node.attributes]) {
    const name = attr.name.toLowerCase();
    if(name.startsWith('on') || ['srcdoc','action','formaction','target','autofocus','nonce','is'].includes(name)
      || (['href','xlink:href'].includes(name) && !attr.value.startsWith('#'))
      || (['src','srcset'].includes(name) && !/^data:image\\/(png|jpeg|webp|gif);base64,/i.test(attr.value))) node.removeAttribute(attr.name);
  }
  document.getElementById('blink-ui-root').append(fragment.content);
  document.addEventListener('submit', event => event.preventDefault(), true);
  const style = document.createElement('style'); style.textContent = '@layer generated {' + source.css + '}'; document.head.append(style);
  const controlStyle = document.createElement('style'); controlStyle.textContent = ${json(controls.css)}; document.head.append(controlStyle);
  const controlScript = document.createElement('script'); controlScript.nonce = ${json(nonce)}; controlScript.textContent = ${json(controls.javascript)}; document.body.append(controlScript);
  let queued = false;
  const resize = () => { if(queued) return; queued = true; requestAnimationFrame(() => {queued = false; send('resize',{height:document.body.scrollHeight});}); };
  new ResizeObserver(resize).observe(document.body);
  const script = document.createElement('script'); script.nonce = ${json(nonce)}; script.textContent = source.javascript; document.body.append(script);
  send('ready'); resize();
})();</script></body></html>`;
}
