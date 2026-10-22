/** Adapt the existing PixelCrab; no office-specific sprite or face artwork. */
export function officeEmotion(state) {
  if (state.pose === 'sleep' || state.pose === 'waking') return 'sleepy';
  if (state.activity === 'help') return 'asking';
  const expression = state.emotion || 'idle';
  if (!['idle', 'sleepy', 'working', 'thinking', 'searching', 'reading', 'writing'].includes(expression)) return expression;
  if (state.pose === 'walk' || state.pose === 'hop') return 'idle';
  if (state.pose === 'read' || state.pose === 'leisure') return 'reading';
  if (state.pose === 'organize') return 'searching';
  if (state.pose === 'work') return 'working';
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
  let previousPose='', previousX=null;
  function update(state,dt) {
    const emotion=officeEmotion(state);
    if(mascot.emotion!==emotion)mascot.setEmotion(emotion);
    if(source){mascot.colors=source.colors;mascot.setAudioLevel(source.audioLevel);}
    if(state.pose==='hop'&&previousPose!=='hop'&&!state.reduced)mascot.hop();
    const direction=previousX===null||state.position.x>=previousX?1:-1;
    // Keep all native props within the offscreen frame while room movement
    // is applied when compositing. This is not another animation loop.
    mascot.x=mascot.homeX;
    mascot.advance(dt,{walking:state.pose==='walk',direction,reduced:state.reduced});
    previousPose=state.pose;previousX=state.position.x;
  }
  return {
    update,
    draw(ctx,state){
      ctx.fillStyle='#d4d0c7';ctx.beginPath();ctx.ellipse(state.position.x,state.position.y,20,4,0,0,Math.PI*2);ctx.fill();
      ctx.drawImage(canvas,Math.round(state.position.x-mascot.x),Math.round(state.position.y-mascot.groundY));
    },
    destroy(){mascot.destroy();},
  };
}
