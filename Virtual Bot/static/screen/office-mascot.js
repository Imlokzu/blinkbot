import { OfficeActions } from './office-actions.js';

/** Adapt the existing PixelCrab; no office-specific sprite or face artwork. */
export function officeEmotion(state) {
  // The fright lasts through the hop: the native jump plays on that face
  if(state.wakeStage==='startled'||(state.wakeStage==='hop'&&state.wakeVisitor))return 'surprised';
  if (['sleep','dozing','calling','waking'].includes(state.pose)) return 'sleepy';
  if (state.activity === 'help') return 'asking';
  const expression = state.emotion || 'idle';
  if (!['idle', 'sleepy', 'working', 'thinking', 'searching', 'reading', 'writing'].includes(expression)) return expression;
  if (state.pose === 'walk' || state.pose === 'hop') return 'idle';
  if (state.pose === 'read' || state.pose === 'leisure') return 'reading';
  if (state.pose === 'organize') return 'searching';
  if (state.pose === 'work') return 'working';
  if (state.pose === 'play') return 'ball';
  if (state.pose === 'water') return 'working';
  return expression === 'sleepy' ? 'idle' : expression;
}

// crab.js is a classic script: its top-level class is a global lexical
// binding, reachable by name but not as a window property. A host that
// passes no mascot (a fixture, an older screen) still gets the original crab.
const nativeMascot=()=>typeof PixelCrab==='function'?PixelCrab:undefined;
export function createOfficeMascot({source=null,visitor=false,Mascot=source?.constructor??nativeMascot()}={}) {
  if(typeof Mascot!=='function')throw new TypeError('office mascot needs the PixelCrab class');
  const canvas=document.createElement('canvas');canvas.width=160;canvas.height=112;
  const mascot=new Mascot(canvas,null,null,{
    scale:4,autoStart:false,
    ...(visitor?{bodyColor:'#8ca879',shadowColor:'#657d58'}:{}),
  });
  const actions=new OfficeActions(visitor);
  let presentation={seat:0,recline:0,offsetY:0,kind:'stand',time:0};
  let previousPose='', previousX=null;
  function syncEmotion(state) {
    const emotion=presentation.wave && (!state.emotion||state.emotion==='idle') ? 'greeting' : officeEmotion(state);
    if(mascot.emotion!==emotion)mascot.setEmotion(emotion);
    if(source){mascot.colors=source.colors;mascot.setAudioLevel(source.audioLevel);}
  }
  const contact=(state,direction,reduced)=>({walking:state.pose==='walk',direction,reduced,
    seat:presentation.seat,recline:presentation.recline,furniture:true,
    action:presentation.kind,snack:presentation.snack,actionTime:presentation.time});
  function update(state,dt) {
    presentation=actions.update(state,dt);
    syncEmotion(state);
    if(state.pose==='hop'&&previousPose!=='hop'&&!state.reduced)mascot.hop();
    const direction=previousX===null||state.position.x>=previousX?1:-1;
    // Keep all native props within the offscreen frame while room movement
    // is applied when compositing. This is not another animation loop.
    mascot.x=mascot.homeX;
    mascot.advance(dt,contact(state,direction,state.reduced));
    previousPose=state.pose;previousX=state.position.x;
  }
  return {
    update,
    get presentation(){return presentation;},
    /* On show, nothing that happened while hidden plays back: the seat and
       recline land on their targets, and the native body settles with them
       (lift, squash, eyes, no pending jump) through its own reduced path. */
    snap(state){
      presentation=actions.update(state,0,true);
      syncEmotion(state);
      previousPose=state.pose;previousX=state.position.x;
      mascot.x=mascot.homeX;
      mascot.advance(0,contact(state,1,true));
    },
    draw(ctx,state){
      if(presentation.seat<.2 && presentation.recline<.2){
        ctx.save();ctx.globalAlpha=state.pose==='hop'?.08:.18;ctx.fillStyle='#525951';
        ctx.beginPath();ctx.ellipse(state.position.x,state.position.y,state.pose==='walk'?16:20,state.pose==='hop'?2.5:4,0,0,Math.PI*2);ctx.fill();ctx.restore();
      }
      ctx.drawImage(canvas,Math.round(state.position.x-mascot.x),Math.round(state.position.y+presentation.offsetY-mascot.groundY));
    },
    destroy(){mascot.destroy();},
  };
}
