/** Actual clock package on a loopback fixture; no owner services or audio. */
import assert from 'node:assert/strict';
import {execFile} from 'node:child_process';
import {createServer} from 'node:http';
import {mkdtemp,readFile,writeFile,rm} from 'node:fs/promises';
import {dirname,resolve} from 'node:path';
import {fileURLToPath} from 'node:url';
import {promisify} from 'node:util';
const qa=dirname(fileURLToPath(import.meta.url)),repo=resolve(qa,'../..');
const temporary=await mkdtemp(resolve(qa,'browser-'));
const env=Object.fromEntries(['PATH','TMPDIR','LANG','LC_ALL'].filter(k=>process.env[k]).map(k=>[k,process.env[k]]));
const run=promisify(execFile),session=`clock-wave1-${process.pid}`,config=resolve(temporary,'browser.json');
let server,opened=false,items=[],failNext=false,delayNextGet=false,releaseGet;
const observations={screens:[],requests:[]},failures=[];
const browser=async(...args)=>{
  const {stdout}=await run('agent-browser',['--config',config,'--namespace',session,'--session',session,'--allowed-domains','127.0.0.1','--json',...args],{env,cwd:temporary,timeout:40000,maxBuffer:2000000});
  const reply=JSON.parse(stdout);assert.equal(reply.success,true,stdout);return reply.data;
};
const evaluate=async code=>(await browser('eval','-b',Buffer.from(code).toString('base64'))).result;
try{
  await run('osascript',['-e','set volume output muted true'],{env});await writeFile(config,'{}');
  const files=new Map();
  let html=await readFile(resolve(repo,'Virtual Bot/store/packages/clock/index.html'),'utf8');
  html=html.replace('<head>',`<head><meta http-equiv="Content-Security-Policy" content="default-src 'self';script-src 'self' 'unsafe-inline';style-src 'self' 'unsafe-inline';connect-src 'self';img-src 'self' data:"><script>window.fixtureErrors=[];addEventListener('error',e=>fixtureErrors.push(e.message));addEventListener('unhandledrejection',e=>fixtureErrors.push(String(e.reason)));</script>`);
  files.set('/',html);
  for(const name of ['app-kit.js','app-kit.css']) files.set('/static/screen/'+name,await readFile(resolve(repo,'Virtual Bot/static/screen',name)));
  server=createServer(async(request,response)=>{
    if(request.url==='/api/screen/timers'){
      response.setHeader('Content-Type','application/json');
      if(request.method==='POST'){
        let body='';for await(const chunk of request)body+=chunk;
        const data=JSON.parse(body);observations.requests.push(data);
        if(failNext){failNext=false;response.writeHead(500);response.end('{}');return;}
        if(data.action==='set')items.push({...data,id:'alarm-'+observations.requests.length,activation:1,state:'running',seconds:3600,left:3600,ends_at:Date.now()/1000+3600});
        else if(data.action==='cancel')items=items.filter(item=>item.id!==data.id);
        else items=items.map(item=>item.id===data.id?{...item,state:data.action==='pause'?'paused':'running'}:item);
      }
      const snapshot=JSON.stringify({timers:items,now:Date.now()/1000});
      if(request.method==='GET'&&delayNextGet){delayNextGet=false;await new Promise(yes=>releaseGet=yes);}
      response.end(snapshot);return;
    }
    const bytes=files.get(request.url);
    if(bytes===undefined){response.writeHead(404);response.end();return;}
    response.setHeader('Content-Type',request.url==='/'?'text/html':request.url.endsWith('.css')?'text/css':'text/javascript');response.end(bytes);
  });
  await new Promise((yes,no)=>{server.once('error',no);server.listen(0,'127.0.0.1',yes)});
  opened=true;await browser('open',`http://127.0.0.1:${server.address().port}`);await browser('set','viewport','320','240');
  await browser('wait','--fn','!!document.querySelector("#alarmNew")');
  for(const lang of ['en','uk'])for(const ui of ['material','deep'])for(const theme of ['light','dark']){
    await evaluate(`window.postMessage({type:'botSkin',lang:${JSON.stringify(lang)},ui:${JSON.stringify(ui)},theme:${JSON.stringify(theme)},tokens:${JSON.stringify(theme==='light'?{'--bg':'#f5f1e9','--panel':'#fffaf3','--text':'#2c2924','--muted':'#746e66','--line':'#d8d1c6'}:{'--bg':'#16181a','--panel':'#1e2124','--text':'#e8e4dc','--muted':'#8e9498','--line':'#2f3438'})}},location.origin);true`);
    await browser('click','[data-tab="alarm"]');await browser('click','#alarmNew');
    await browser('fill','#alarmLabel',lang==='uk'?'Ранкові ліки':'Morning medicine');
    await browser('select','#alarmRepeat','weekdays');
    await browser('screenshot',resolve(qa,`clock-${lang}-${ui}-${theme}.png`));
    const bounds=await evaluate(`['alarmTime','alarmLabel','alarmRepeat','alarmBack','alarmSave'].map(id=>{const r=document.getElementById(id).getBoundingClientRect();return {id,x:r.x,y:r.y,right:r.right,bottom:r.bottom,height:r.height}})`);
    for(const box of bounds){assert.ok(box.x>=12&&box.right<=308,JSON.stringify(box));assert.ok(box.y>=0&&box.bottom<=224,JSON.stringify(box));}
    observations.screens.push({lang,ui,theme,bounds});
    await browser('click','#alarmBack');
  }
  await evaluate("window.postMessage({type:'botSkin',lang:'en',ui:'material',theme:'light'},location.origin);true");
  await browser('click','#alarmNew');await evaluate(`document.querySelector('#alarmTime').value='08:45';document.querySelector('#alarmTime').dispatchEvent(new Event('input',{bubbles:true}));true`);await browser('fill','#alarmLabel','<b>Literal alarm</b>');await browser('select','#alarmRepeat','daily');
  observations.form=await evaluate(`({valid:document.querySelector('#alarmEditor').checkValidity(),time:document.querySelector('#alarmTime').value,lang:BotApp.lang,theme:BotApp.theme,ui:BotApp.ui})`);console.log(observations.form);failNext=true;await browser('click','#alarmSave');await browser('wait','--fn','document.querySelector("#alarmError").textContent.includes("Could not save")');
  assert.equal(await evaluate('document.querySelector("#alarmLabel").value'),'<b>Literal alarm</b>');
  assert.equal(await evaluate('document.querySelector("#alarmEditor").hidden'),false);
  await browser('click','#alarmSave');await browser('wait','--fn','document.querySelector("#alarmEditor").hidden');
  assert.equal(await evaluate('document.querySelector(".alarm-label").textContent'),'<b>Literal alarm</b>');
  assert.equal(await evaluate('document.querySelector(".alarm-label b")'),null);
  await browser('click','#alarmRows button[aria-label="Pause"]');await browser('wait','--fn','document.querySelector("#alarmRows .alarm-meta").textContent==="Paused"');
  await browser('click','#alarmRows button[aria-label="Resume"]');await browser('wait','--fn','document.querySelector("#alarmRows .alarm-meta").textContent.includes("Daily")');
  await browser('screenshot',resolve(qa,'clock-alarm-list.png'));
  delayNextGet=true;
  await browser('wait','--fn','true');
  const pollDeadline=Date.now()+6000;while(!releaseGet&&Date.now()<pollDeadline)await new Promise(yes=>setTimeout(yes,50));
  assert.ok(releaseGet,'poll started');
  await browser('click','#alarmRows button[aria-label="Delete alarm"]');await browser('wait','--fn','document.querySelectorAll(".alarm-row").length===0');
  releaseGet();releaseGet=null;await browser('wait','--load','networkidle');
  assert.equal(await evaluate('document.querySelectorAll(".alarm-row").length'),0,'stale poll must not resurrect cancelled alarm');
  observations.errors=await evaluate('fixtureErrors');assert.deepEqual(observations.errors,[]);
}catch(error){observations.failure=String(error);try{observations.page=await evaluate(`({text:document.body.innerText,errors:fixtureErrors})`)}catch{}failures.push(error)}finally{
  releaseGet?.();
  if(opened)try{await browser('close')}catch(error){failures.push(error)}
  if(server?.listening){server.closeAllConnections();try{await new Promise((yes,no)=>server.close(error=>error?no(error):yes()))}catch(error){failures.push(error)}}
  try{await rm(temporary,{recursive:true,force:true})}catch(error){failures.push(error)}
}
await writeFile(resolve(qa,'browser-observations.json'),JSON.stringify(observations,null,2));
if(failures.length)throw new AggregateError(failures,'Clock browser probe or cleanup failed');
console.log(JSON.stringify({screens:observations.screens.length,mutations:observations.requests.length,errors:observations.errors},null,2));
