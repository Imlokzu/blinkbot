import { OfficeState } from './office-state.js';
import { OfficeVisitor, BEDSIDE, canTeleport } from './office-visitor.js';
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
  teleport:'M8 1v3M8 12v3M1 8h3M12 8h3M12 8a4 4 0 1 1-8 0 4 4 0 0 1 8 0z',
};
const roomLabel=room=>ot(room==='chat'?'lounge':room);
const statusKey={work:'working',chat:'talking',sleep:'sleeping'};
const ZOOMS=[.5,.75,1,1.5,2];
const read=(key,fallback)=>{try{return localStorage.getItem(key)??fallback;}catch{return fallback;}};
const save=(key,value)=>{try{localStorage.setItem(key,value);}catch{/* Storage can be disabled. */}};

export function mountOffice(host, callbacks={}) {
  const state=new OfficeState(SPOTS);
  let visitorHome=VISITOR;
  try{const saved=JSON.parse(read('botScreenOfficePosition','null'));if(canTeleport(saved))visitorHome=saved;}catch{/* Invalid preferences use the guest chair. */}
  const visitorModel=new OfficeVisitor(visitorHome);
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
  let placing=false,placementPoint={...visitorModel.position},pointer=null;
  let noticeKey='',noticeUntil=0,teleportedAt=-10,lastWakeStage=null,wakeWasActive=false;
  let cameraTransform={zoom:1,tx:0,ty:0};
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
    b.addEventListener('click',e=>{e.stopPropagation();if(!state.sleeping&&!state.wakeStage)callbacks.onInteract?.();action();render();});
    // Keep gestures on the room itself available to the screen carousel.
    for(const type of ['pointerdown','pointerup','pointermove','contextmenu'])b.addEventListener(type,e=>e.stopPropagation());
    parent.append(b);buttons.push({b,key,wide});return b;
  }
  const out=button(controls,'zoomOut','minus',()=>changeZoom(-1));
  const inside=button(controls,'zoomIn','plus',()=>changeZoom(1));
  const follow=button(controls,'overview','overview',()=>{overview=!overview;zoom=overview?.5:1;syncButtons();});
  const guest=button(controls,'visitor','person',()=>{visitor=!visitor;save('botScreenOfficeVisitor',visitor?'1':'0');if(!visitor)cancelPlacement();syncButtons();});
  const teleport=button(controls,'teleport','teleport',()=>{
    if(placing){cancelPlacement();return;}
    visitor=true;save('botScreenOfficeVisitor','1');placing=true;overview=true;zoom=.5;
    placementPoint={...visitorModel.position};canvas.tabIndex=0;canvas.removeAttribute('aria-hidden');
    canvas.setAttribute('role','application');canvas.setAttribute('aria-label',ot('teleportKeys'));canvas.focus();syncButtons();
  });
  button(footer,'chat','chat',()=>callbacks.onChat?.(),true);
  button(footer,'settings','settings',()=>callbacks.onSettings?.());
  button(footer,'classic','classic',()=>callbacks.onClassic?.());

  function cancelPlacement(){
    if(!placing)return;
    if(pointer&&canvas.hasPointerCapture?.(pointer.id))canvas.releasePointerCapture(pointer.id);
    if(noticeKey==='teleportInvalid'){noticeKey='';noticeUntil=0;}
    pointer=null;placing=false;canvas.tabIndex=-1;canvas.setAttribute('aria-hidden','true');canvas.removeAttribute('role');
    if(document.activeElement===canvas)canvas.blur();
    syncButtons();if(visible&&!(state.wakeVisitor&&state.wakeStage))teleport.focus();
  }
  function place(point){
    if(!visitorModel.teleport(point)){
      noticeKey='teleportInvalid';noticeUntil=state.elapsed+2;live.textContent=ot(noticeKey);render();return false;
    }
    save('botScreenOfficePosition',JSON.stringify(visitorModel.home));
    teleportedAt=state.elapsed;noticeKey='teleportDone';noticeUntil=state.elapsed+2;
    cancelPlacement();render();return true;
  }
  function worldPoint(event){
    const box=canvas.getBoundingClientRect();
    return {x:((event.clientX-box.left)*320/box.width-cameraTransform.tx)/cameraTransform.zoom,
      y:((event.clientY-box.top)*240/box.height-cameraTransform.ty)/cameraTransform.zoom};
  }
  function pointerDown(event){
    if(!placing)return;
    // While placing, no pointer reaches the carousel underneath: a second
    // finger or another button must not swipe the tiles or hold the launcher.
    event.stopPropagation();
    if(pointer||!event.isPrimary||event.button!==0)return;
    event.preventDefault();
    pointer={id:event.pointerId,x:event.clientX,y:event.clientY};
    canvas.setPointerCapture?.(event.pointerId);
  }
  function pointerMove(event){
    if(!placing)return;
    event.stopPropagation();
    if(pointer&&event.pointerId!==pointer.id)return;
    placementPoint=worldPoint(event);render();
  }
  function pointerEnd(event){
    if(!placing)return;
    event.stopPropagation();
    if(!pointer||pointer.id!==event.pointerId)return;
    event.preventDefault();
    const start=pointer;pointer=null;
    if(canvas.hasPointerCapture?.(event.pointerId))canvas.releasePointerCapture(event.pointerId);
    const box=canvas.getBoundingClientRect();
    if(event.type!=='pointercancel'&&Math.hypot(event.clientX-start.x,event.clientY-start.y)<8*box.width/320)place(worldPoint(event));
  }
  function placementKey(event){
    if(!placing)return;
    if(!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Enter','Escape'].includes(event.key))return;
    event.preventDefault();event.stopPropagation();
    if(event.key==='Escape'){cancelPlacement();render();return;}
    if(event.key==='Enter'){place(placementPoint);return;}
    const amount=event.shiftKey?16:4;
    placementPoint={x:Math.max(35,Math.min(605,placementPoint.x+(event.key==='ArrowRight'?amount:event.key==='ArrowLeft'?-amount:0))),
      y:Math.max(41,Math.min(427,placementPoint.y+(event.key==='ArrowDown'?amount:event.key==='ArrowUp'?-amount:0)))};
    render();
  }
  canvas.addEventListener('pointerdown',pointerDown);
  canvas.addEventListener('pointermove',pointerMove);
  canvas.addEventListener('pointerup',pointerEnd);
  canvas.addEventListener('pointercancel',pointerEnd);
  root.addEventListener('keydown',placementKey);

  function updateVisitor(dt){
    const cue=state.wakeVisitor&&!!state.wakeStage;
    if(cue&&placing)cancelPlacement();
    if(state.wakeStage==='approach'&&lastWakeStage!=='approach')visitorModel.go(BEDSIDE,{reduced:state.reduced});
    if(!cue&&wakeWasActive)visitorModel.go(visitorModel.home,{returning:true,reduced:state.reduced});
    visitorModel.step(dt,state.reduced);
    if(state.wakeStage==='approach'&&!visitorModel.route.length&&Math.hypot(visitorModel.position.x-BEDSIDE.x,visitorModel.position.y-BEDSIDE.y)<1)state.visitorAtBed();
    if(lastWakeStage!==state.wakeStage){lastWakeStage=state.wakeStage;syncButtons();}
    wakeWasActive=cue;
  }
  function finishHiddenWake(){
    if(!state.wakeStage)return;
    state.skipWake();visitorModel.go(visitorModel.home,{reduced:true});lastWakeStage=null;wakeWasActive=false;syncButtons();
  }
  function drawWakeCue(){
    if(state.pose==='calling'){
      const from={x:visitorModel.position.x,y:visitorModel.position.y-22};
      const toward={x:state.position.x,y:state.position.y+mascot.presentation.offsetY-20};
      const angle=Math.atan2(toward.y-from.y,toward.x-from.x),progress=state.reduced?1:Math.max(0,1-state.phase/.8);
      c.save();c.strokeStyle='#6f8470';c.lineWidth=2;c.globalAlpha=.65;
      for(let i=0;i<3;i++){c.beginPath();c.arc(from.x,from.y,12+i*11+progress*22,angle-.5,angle+.5);c.stroke();}
      c.restore();
    }
    if(state.exclaim>0){
      c.fillStyle='#a76b3d';c.font='bold 18px monospace';c.textAlign='center';
      c.fillText('!',state.position.x+24,state.position.y-45);
    }
  }

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
    teleport.disabled=state.wakeVisitor&&!!state.wakeStage;
    teleport.setAttribute('aria-label',ot(placing?'teleportCancel':'teleport'));teleport.title=ot(placing?'teleportCancel':'teleport');
    teleport.setAttribute('aria-pressed',String(placing));
    root.dataset.placing=String(placing);root.dataset.zoom=String(zoom);root.dataset.visitor=String(visitor);
  }
  function refreshLanguage() {
    root.setAttribute('aria-label',ot('title'));
    for(const {b,key,wide} of buttons){b.setAttribute('aria-label',ot(key));b.title=ot(key);if(wide)b.querySelector('span').textContent=ot(key);}
    if(placing)canvas.setAttribute('aria-label',ot('teleportKeys'));
    theme='';label='';syncButtons();render();
  }
  function paintBackground() {
    const next=document.documentElement.dataset.theme==='light'?'light':'dark';
    if(theme===next)return;
    theme=next;palette=PALETTES[next];drawHouse(bg,palette,{light:next==='light',roomName:roomLabel});
  }
  const visitorState=()=>({...state,position:{...visitorModel.position},room:visitorModel.room,route:visitorModel.route,doorIntents:visitorModel.doorIntents,
    visitorPresent:state.room===visitorModel.room,active:false,activity:'idle',
    pose:visitorModel.route.length?'walk':visitorModel.seated?'idle':'visit',emotion:state.pose==='calling'?'speaking':'idle'});
  function render(dt=0) {
    if(destroyed||!visible)return;
    paintBackground();
    updateVisitor(dt);
    const showVisitor=visitor||(state.wakeVisitor&&!!state.wakeStage)||visitorModel.returning;
    state.visitorPresent=showVisitor&&visitorModel.room===state.room;
    mascot.update(state,dt);
    if(showVisitor)guestMascot.update(visitorState(),dt);
    const tx=overview?0:Math.max(320-WORLD.width*zoom,Math.min(0,160-camera.x*zoom));
    // Overhead view with the face above captions; overview centers the floor plan.
    const ty=overview?10:Math.min(30,110-(camera.y+mascot.presentation.offsetY)*zoom);
    cameraTransform={zoom,tx,ty};
    c.setTransform(1,0,0,1,0,0);c.fillStyle=palette.void;c.fillRect(0,0,640,480);
    c.imageSmoothingEnabled=false;c.setTransform(2*zoom,0,0,2*zoom,2*tx,2*ty);
    c.drawImage(background,0,0);drawDoors(c,state,palette,showVisitor?visitorState():null);drawWork(c,state,palette);
    if(showVisitor){
      guestMascot.draw(c,visitorState());
      drawFurnitureFront(c,visitorState(),palette,guestMascot.presentation,{visitor:true,body:'#8ca879'});
      c.fillStyle=palette.ink;c.font='10px monospace';c.textAlign='center';c.fillText(ot('visitorName'),visitorModel.position.x,visitorModel.position.y-42);
    }
    mascot.draw(c,state);
    drawFurnitureFront(c,state,palette,mascot.presentation,{body:callbacks.mascot?.colors?.body||'#d98263'});
    drawWakeCue();
    if(placing){
      c.strokeStyle=canTeleport(placementPoint)?'#6d946d':'#bc765c';c.lineWidth=2;
      c.beginPath();c.ellipse(placementPoint.x,placementPoint.y,27,7,0,0,Math.PI*2);c.stroke();
      c.beginPath();c.moveTo(placementPoint.x-6,placementPoint.y);c.lineTo(placementPoint.x+6,placementPoint.y);c.moveTo(placementPoint.x,placementPoint.y-6);c.lineTo(placementPoint.x,placementPoint.y+6);c.stroke();
    } else if(state.elapsed-teleportedAt<1){
      c.save();c.globalAlpha=1-(state.elapsed-teleportedAt);c.strokeStyle='#7fa36b';c.lineWidth=2;
      c.beginPath();c.ellipse(visitorModel.position.x,visitorModel.position.y,30,8,0,0,Math.PI*2);c.stroke();c.restore();
    }
    c.setTransform(1,0,0,1,0,0);
    root.dataset.wake=state.wakeStage||'';
    const contact=mascot.presentation.recline>.99?'lying':mascot.presentation.seat>.99?'seated':
      mascot.presentation.seat>0||mascot.presentation.recline>0?'settling':'standing';
    if(root.dataset.action!==mascot.presentation.kind)root.dataset.action=mascot.presentation.kind;
    if(root.dataset.contact!==contact)root.dataset.contact=contact;
    root.dataset.pose=state.pose;root.dataset.room=state.room;root.dataset.activity=state.activity;root.dataset.connected=String(state.connected);
    const where=roomLabel(state.route.length?state.target:state.room);
    const next=noticeUntil>state.elapsed?ot(noticeKey):placing?ot('teleportHint'):
      state.status==='moving'?ot('moving',{room:where}):ot(statusKey[state.status]||state.status)+' · '+where;
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
      const nearVisitor=state.wakeVisitor&&!!state.wakeStage&&visitorModel.room==='sleep';
      const target=nearVisitor?{x:(state.position.x+visitorModel.position.x)/2,
        y:(state.position.y+mascot.presentation.offsetY+visitorModel.position.y)/2+8-mascot.presentation.offsetY}:
        {x:state.position.x,y:state.position.y};
      const k=state.reduced?1:Math.min(1,delta/1000*6);
      camera.x+=(target.x-camera.x)*k;camera.y+=(target.y-camera.y)*k;
      render(delta/1000);
    }
    raf=requestAnimationFrame(tick);
  }
  function setVisible(value) {
    const wasVisible=visible;
    visible=!!value;
    if(!visible){finishHiddenWake();if(placing)cancelPlacement();}
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
    setActivity(value){state.setActivity(value);if(!visible)finishHiddenWake();render();},
    setEmotion(value){state.emotion=String(value||'idle');render();},
    setSleeping(value,options){state.setSleeping(value,options);render();},
    setConnected(value){state.setConnected(value);render();},
    setReducedMotion(value){requestedReduced=!!value;motion();if(state.reduced&&wakeWasActive){visitorModel.go(visitorModel.home,{reduced:true});wakeWasActive=false;lastWakeStage=null;}},
    setVisible,refreshLanguage,
    destroy(){destroyed=true;if(raf)cancelAnimationFrame(raf);observer.disconnect();mascot.destroy();guestMascot.destroy();motionQuery.removeEventListener('change',motion);root.remove();},
  };
}
