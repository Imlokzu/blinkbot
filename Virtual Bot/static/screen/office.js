import { OfficeState } from './office-state.js';
import { createOfficeMascot } from './office-mascot.js';
import { WORLD, SPOTS, PALETTES, drawHouse, drawWork, drawDoors, drawFurnitureFront, VISITOR } from './office-world.js';
import { ot } from './office-i18n.js';

const ICONS = {
  minus:'M4 8h8',plus:'M4 8h8M8 4v8',overview:'M2 6l6-4 6 4v8H2V6zM6 14V9h4v5',
  follow:'M2 5V2h3M11 2h3v3M14 11v3h-3M5 14H2v-3M5 8h6M8 5v6',
  person:'M10 4a2 2 0 1 1-4 0 2 2 0 0 1 4 0zM3 14v-2a5 5 0 0 1 10 0v2',
  chat:'M2 2h12v9H7l-4 3v-3H2zM5 5h6M5 8h4',
  settings:'M8 2v2M8 12v2M2 8h2M12 8h2M4 4l1 1M11 11l1 1M4 12l1-1M11 5l1-1M11 8a3 3 0 1 1-6 0 3 3 0 0 1 6 0z',
  classic:'M2 3h12v10H2zM5 6v3M11 6v3M6 11h4',
};
const roomLabel=room=>ot(room==='chat'?'lounge':room);
const statusKey={work:'working',chat:'talking',sleep:'sleeping'};
const ZOOMS=[.5,.75,1,1.5,2];
const read=(key,fallback)=>{try{return localStorage.getItem(key)??fallback;}catch{return fallback;}};
const save=(key,value)=>{try{localStorage.setItem(key,value);}catch{/* Storage can be disabled. */}};

export function mountOffice(host, callbacks={}) {
  const state=new OfficeState(SPOTS);
  const mascot=createOfficeMascot({source:callbacks.mascot});
  const guestMascot=createOfficeMascot({visitor:true,Mascot:callbacks.mascot?.constructor});
  const root=document.createElement('div');root.className='office-root';
  const canvas=document.createElement('canvas');canvas.className='office-canvas';canvas.width=640;canvas.height=480;
  canvas.setAttribute('aria-hidden','true');root.append(canvas);
  const c=canvas.getContext('2d');
  const background=document.createElement('canvas');background.width=WORLD.width;background.height=WORLD.height;
  const bg=background.getContext('2d');
  const controls=document.createElement('div');controls.className='office-controls';root.append(controls);
  const footer=document.createElement('div');footer.className='office-footer';root.append(footer);
  const status=document.createElement('p');status.className='office-status';root.append(status);
  const live=document.createElement('div');live.className='office-a11y';live.setAttribute('role','status');live.setAttribute('aria-live','polite');root.append(live);
  host.append(root);
  let visible=false, destroyed=false, raf=0, last=0, zoom=1, overview=false;
  let visitor=read('botScreenOfficeVisitor','0')==='1';
  let camera={x:state.position.x,y:state.position.y-4};
  let theme='', label='', announced='', lastAnnounced=0;
  let palette=PALETTES.dark;
  const buttons=[];
  const motionQuery=window.matchMedia('(prefers-reduced-motion: reduce)');
  let requestedReduced=false;

  const icon=(name)=>'<svg viewBox="0 0 16 16" aria-hidden="true"><path d="'+ICONS[name]+'"/></svg>';
  function button(parent,key,glyph,action,wide=false) {
    const b=document.createElement('button');b.type='button';b.className='office-button'+(wide?' office-chat':'');
    b.innerHTML=icon(glyph)+(wide?'<span></span>':'');
    b.addEventListener('click',e=>{e.stopPropagation();callbacks.onInteract?.();action();render();});
    // Keep gestures on the room itself available to the screen carousel.
    for(const type of ['pointerdown','pointerup','pointermove','contextmenu'])b.addEventListener(type,e=>e.stopPropagation());
    parent.append(b);buttons.push({b,key,wide});return b;
  }
  const out=button(controls,'zoomOut','minus',()=>changeZoom(-1));
  const inside=button(controls,'zoomIn','plus',()=>changeZoom(1));
  const follow=button(controls,'overview','overview',()=>{overview=!overview;zoom=overview?.5:1;syncButtons();});
  const guest=button(controls,'visitor','person',()=>{visitor=!visitor;save('botScreenOfficeVisitor',visitor?'1':'0');syncButtons();});
  button(footer,'chat','chat',()=>callbacks.onChat?.(),true);
  button(footer,'settings','settings',()=>callbacks.onSettings?.());
  button(footer,'classic','classic',()=>callbacks.onClassic?.());

  function changeZoom(step) {
    const index=ZOOMS.indexOf(zoom);
    zoom=ZOOMS[Math.max(0,Math.min(ZOOMS.length-1,index+step))];
    overview=zoom===.5;syncButtons();
  }
  function syncButtons() {
    out.disabled=zoom===ZOOMS[0];inside.disabled=zoom===ZOOMS.at(-1);
    follow.setAttribute('aria-label',ot(overview?'follow':'overview'));
    follow.title=ot(overview?'follow':'overview');follow.innerHTML=icon(overview?'follow':'overview');
    follow.setAttribute('aria-pressed',String(overview));
    guest.setAttribute('aria-label',ot(visitor?'visitorOff':'visitor'));
    guest.title=ot(visitor?'visitorOff':'visitor');guest.setAttribute('aria-pressed',String(visitor));
    root.dataset.zoom=String(zoom);root.dataset.visitor=String(visitor);
  }
  function refreshLanguage() {
    root.setAttribute('aria-label',ot('title'));
    for(const {b,key,wide} of buttons){b.setAttribute('aria-label',ot(key));b.title=ot(key);if(wide)b.querySelector('span').textContent=ot(key);}
    theme='';label='';syncButtons();render();
  }
  function paintBackground() {
    const next=document.documentElement.dataset.theme==='light'?'light':'dark';
    if(theme===next)return;
    theme=next;palette=PALETTES[next];drawHouse(bg,palette,{light:next==='light',roomName:roomLabel});
  }
  const visitorState=()=>({...state,position:{...VISITOR},room:'chat',visitorPresent:state.room==='chat',active:false,activity:'idle',pose:'idle',emotion:'idle'});
  function render(dt=0) {
    if(destroyed||!visible)return;
    paintBackground();
    state.visitorPresent=visitor;
    mascot.update(state,dt);
    if(visitor)guestMascot.update(visitorState(),dt);
    const tx=overview?0:Math.max(320-WORLD.width*zoom,Math.min(0,160-camera.x*zoom));
    // Overhead view with the face above captions; overview centers the floor plan.
    const ty=overview?10:Math.min(30,110-(camera.y+mascot.presentation.offsetY)*zoom);
    c.setTransform(1,0,0,1,0,0);c.fillStyle=palette.void;c.fillRect(0,0,640,480);
    c.imageSmoothingEnabled=false;c.setTransform(2*zoom,0,0,2*zoom,2*tx,2*ty);
    c.drawImage(background,0,0);drawDoors(c,state,palette);drawWork(c,state,palette);
    if(visitor){
      guestMascot.draw(c,visitorState());
      drawFurnitureFront(c,visitorState(),palette,guestMascot.presentation,{visitor:true,body:'#8ca879'});
      c.fillStyle=palette.ink;c.font='10px monospace';c.textAlign='center';c.fillText(ot('visitorName'),VISITOR.x,VISITOR.y-42);
    }
    mascot.draw(c,state);
    drawFurnitureFront(c,state,palette,mascot.presentation,{body:callbacks.mascot?.colors?.body||'#d98263'});
    c.setTransform(1,0,0,1,0,0);
    const contact=mascot.presentation.recline>.99?'lying':mascot.presentation.seat>.99?'seated':
      mascot.presentation.seat>0||mascot.presentation.recline>0?'settling':'standing';
    if(root.dataset.action!==mascot.presentation.kind)root.dataset.action=mascot.presentation.kind;
    if(root.dataset.contact!==contact)root.dataset.contact=contact;
    root.dataset.pose=state.pose;root.dataset.room=state.room;root.dataset.activity=state.activity;root.dataset.connected=String(state.connected);
    const where=roomLabel(state.route.length?state.target:state.room);
    const next=state.status==='moving'?ot('moving',{room:where}):ot(statusKey[state.status]||state.status)+' · '+where;
    if(next!==label){label=next;status.textContent=next;}
    if(label!==announced && state.elapsed-lastAnnounced>=1.5){live.textContent=label;announced=label;lastAnnounced=state.elapsed;}
  }
  function tick(now) {
    raf=0;
    if(!visible||destroyed||document.hidden)return;
    if(!last)last=now;
    const delta=now-last;
    // Static backgrounds are blitted at 24 fps; no work on hidden screens.
    if(delta>=1000/24){
      last=now;state.step(delta/1000);
      const target={x:state.position.x,y:state.position.y};
      const k=state.reduced?1:Math.min(1,delta/1000*6);
      camera.x+=(target.x-camera.x)*k;camera.y+=(target.y-camera.y)*k;
      render(delta/1000);
    }
    raf=requestAnimationFrame(tick);
  }
  function setVisible(value) {
    const wasVisible=visible;
    visible=!!value;
    if(visible&&!wasVisible){mascot.snap(state);guestMascot.snap(visitorState());}
    if(raf)cancelAnimationFrame(raf);raf=0;last=0;
    if(visible&&!document.hidden){
      if(state.reduced)camera={...state.position};
      render();raf=requestAnimationFrame(tick);
    }
  }
  const motion=()=>{state.setReducedMotion(requestedReduced||motionQuery.matches);if(state.reduced)camera={...state.position};render();};
  const observer=new MutationObserver(()=>{theme='';render();});
  observer.observe(document.documentElement,{attributes:true,attributeFilter:['data-theme','data-ui']});
  motionQuery.addEventListener('change',motion);
  refreshLanguage();motion();
  return {
    setActivity(value){state.setActivity(value);render();},
    setEmotion(value){state.emotion=String(value||'idle');render();},
    setSleeping(value){state.setSleeping(value);render();},
    setConnected(value){state.setConnected(value);render();},
    setReducedMotion(value){requestedReduced=!!value;motion();},
    setVisible,refreshLanguage,
    destroy(){destroyed=true;if(raf)cancelAnimationFrame(raf);observer.disconnect();mascot.destroy();guestMascot.destroy();motionQuery.removeEventListener('change',motion);root.remove();},
  };
}
