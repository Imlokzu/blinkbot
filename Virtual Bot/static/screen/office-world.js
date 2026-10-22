/** Opus 5.5's revised top-down white office. All art is local and cached. */
export const WORLD = {width:640,height:440};
export const CORRIDOR_Y = 220;
export const ROOMS = {
  sleep: {x:8,y:8,w:200,h:180}, library:{x:216,y:8,w:208,h:180},
  work:{x:432,y:8,w:200,h:180}, kitchen:{x:8,y:252,w:296,h:180},
  chat:{x:312,y:252,w:320,h:180},
};
export const SPOTS = {
  sleep:{point:{x:76,y:106},door:{x:108,y:192}},
  library:{point:{x:330,y:142},leisure:{x:250,y:150},door:{x:320,y:192}},
  work:{point:{x:544,y:90},door:{x:532,y:192}},
  kitchen:{point:{x:124,y:364},door:{x:156,y:248}},
  chat:{point:{x:400,y:378},door:{x:472,y:248}},
};
export const VISITOR = {x:536,y:358};
const WHITE = {void:'#e8e5df',floor:'#f6f4f0',seam:'#ece8e1',wall:'#ffffff',edge:'#b9b1a6',shadow:'#e4dfd7',inset:'#ddd7ce',trim:'#8c857a',wood:'#c9a57a',woodHi:'#e6cfad',ink:'#3d454a',cream:'#fffdfa',green:'#7fa36b',blue:'#9aa8b8'};
// The office stays white in both screen themes, as requested. Chrome can
// follow the surrounding screen without turning the office into a dark room.
export const PALETTES = {dark:WHITE,light:WHITE};
function rect(c,x,y,w,h,color){c.fillStyle=color;c.fillRect(x,y,w,h);}
function round(c,x,y,w,h,color,r=4){c.fillStyle=color;c.beginPath();c.roundRect(x,y,w,h,r);c.fill();}
function line(c,x1,y1,x2,y2,color,width=1){c.strokeStyle=color;c.lineWidth=width;c.beginPath();c.moveTo(x1,y1);c.lineTo(x2,y2);c.stroke();}
function table(c,x,y,w,h,p){round(c,x+3,y+4,w,h,p.shadow);round(c,x,y,w,h,p.woodHi);line(c,x+3,y+2,x+w-3,y+2,p.cream,2);}
function plant(c,x,y,p,r=12){
  c.fillStyle=p.shadow;c.beginPath();c.ellipse(x+3,y+4,r+2,r+2,0,0,Math.PI*2);c.fill();
  c.fillStyle='#e7e2d5';c.beginPath();c.arc(x,y,r*.75,0,Math.PI*2);c.fill();
  for(let i=0;i<7;i++){const a=i*Math.PI*2/7;c.fillStyle=i%2?'#91b17c':p.green;c.beginPath();c.ellipse(x+Math.cos(a)*r*.45,y+Math.sin(a)*r*.45,r*.75,r*.35,a,0,Math.PI*2);c.fill();}
  round(c,x-3,y-3,6,6,'#5e7e50',2);
}
function wall(c,x,y,w,h,p){rect(c,x+3,y+4,w,h,p.shadow);rect(c,x,y,w,h,p.edge);rect(c,x+1,y+1,w-2,h-2,p.wall);}
function chair(c,x,y,p,color=p.blue){round(c,x+2,y+4,28,28,p.shadow,7);round(c,x,y,28,28,color,6);round(c,x+3,y+3,22,17,'#c4cdd4',4);line(c,x+3,y+22,x+25,y+22,'#7f8f9b',2);}

export function drawHouse(c,p,{roomName=()=>''}={}){
  c.clearRect(0,0,640,440);rect(c,0,0,640,440,p.floor);
  for(let x=8;x<632;x+=16)line(c,x,8,x,432,p.seam);
  for(let y=8;y<432;y+=16)line(c,8,y,632,y,p.seam);
  rect(c,8,196,624,48,'#e9e7e1');
  for(let x=28;x<620;x+=32)line(c,x,198,x,242,'#e0ded7');
  // Low wall tops and intentional 56-unit doorways, wide enough for the
  // 40-unit crab plus clearance on both sides.
  wall(c,0,0,640,8,p);wall(c,0,432,640,8,p);wall(c,0,8,8,424,p);wall(c,632,8,8,424,p);
  wall(c,208,8,8,180,p);wall(c,424,8,8,180,p);wall(c,304,252,8,180,p);
  for(const [start,end,cx] of [[8,208,108],[216,424,320],[432,632,532]]){
    wall(c,start,188,cx-28-start,8,p);wall(c,cx+28,188,end-cx-28,8,p);
  }
  for(const [start,end,cx] of [[8,304,156],[312,632,472]]){
    wall(c,start,244,cx-28-start,8,p);wall(c,cx+28,244,end-cx-28,8,p);
  }
  // Bedroom: white duvet, a single coral stripe, and a soft bedside rug.
  round(c,38,38,76,108,p.shadow,5);round(c,36,34,76,108,'#c6c2b8',4);
  round(c,40,40,68,98,p.cream,3);round(c,46,45,56,23,'#e7e7e1',5);
  rect(c,44,76,60,58,'#f0efeb');rect(c,44,80,60,5,'#dc9c87');
  for(let y=95;y<130;y+=12)line(c,48,y,100,y,'#e1dfd9');
  table(c,126,43,27,28,p);c.fillStyle='#e7d3aa';c.beginPath();c.arc(140,56,8,0,Math.PI*2);c.fill();
  round(c,50,153,100,23,'#efe3cf',10);plant(c,181,37,p,12);
  rect(c,164,87,24,53,'#fdfcf9');line(c,164,113,188,113,p.inset);rect(c,181,100,2,5,p.trim);rect(c,181,125,2,5,p.trim);
  // Library: top-down bookcases, a leisure chair and a separate sorting cart.
  const books=['#a8bba0','#aebbc8','#c6b398','#be9f99','#b1a3bc','#dfceb0'];
  for(let j=0;j<3;j++){
    table(c,228+j*62,19,56,22,p);
    for(let i=0;i<8;i++){rect(c,231+j*62+i*6,22,4,15,books[(j+i)%6]);rect(c,232+j*62+i*6,25,2,1,p.cream);}
  }
  round(c,229,111,60,61,'#d3ddcb',12);chair(c,237,120,p,'#91a182');
  table(c,346,110,45,33,p);rect(c,352,116,15,20,'#aebbc8');rect(c,370,117,15,19,'#c6b398');
  for(const [x,y] of [[350,143],[385,143]])round(c,x,y,4,5,p.trim,1);
  plant(c,397,163,p,10);
  // Study: desk, monitor, keyboard, mouse and office chair seen from above.
  table(c,493,25,109,38,p);round(c,527,29,44,13,'#2b2f36',2);rect(c,530,31,38,8,'#bfe0ff');
  rect(c,546,42,6,5,p.ink);rect(c,537,47,24,2,p.trim);
  round(c,526,52,42,8,'#fdfdfb',1);for(let i=0;i<10;i++)rect(c,528+i*4,54,2,2,p.inset);
  round(c,574,52,6,8,p.cream,2);c.fillStyle=p.cream;c.beginPath();c.arc(588,42,5,0,Math.PI*2);c.fill();
  chair(c,531,68,p);table(c,446,31,28,76,p);for(let i=0;i<4;i++)rect(c,451,37+i*16,18,11,i%2?p.cream:'#d3ddcb');
  plant(c,604,155,p,14);
  // Kitchen: quiet white cabinets, steel sink, hob, fridge and round table.
  round(c,18,397,242,27,p.shadow);rect(c,16,394,244,27,p.cream);
  for(let x=16;x<260;x+=40)line(c,x,397,x,421,p.inset);
  round(c,45,398,43,18,'#c5cecf',3);round(c,50,401,33,12,'#acbabb',2);line(c,66,395,66,403,p.trim,2);
  round(c,219,396,36,23,'#444e50',2);
  for(const x of [229,246])for(const y of [403,414]){c.strokeStyle='#8f9c9a';c.lineWidth=1.5;c.beginPath();c.arc(x,y,4,0,Math.PI*2);c.stroke();}
  round(c,17,268,30,43,p.shadow,3);round(c,15,264,30,43,p.cream,3);line(c,15,278,45,278,p.inset);rect(c,39,283,2,12,p.trim);
  c.fillStyle=p.shadow;c.beginPath();c.arc(128,326,29,0,Math.PI*2);c.fill();
  c.fillStyle=p.woodHi;c.beginPath();c.arc(124,322,28,0,Math.PI*2);c.fill();
  round(c,80,310,18,23,p.blue,5);round(c,151,310,18,23,p.blue,5);
  c.fillStyle=p.cream;c.beginPath();c.arc(124,321,11,0,Math.PI*2);c.fill();
  round(c,121,317,8,5,'#d4ad74',2);plant(c,282,280,p,12);
  // Lounge: generous sage rug, blue-gray sofa, guest chair and low table.
  round(c,336,300,172,116,'#d3ddcb',12);
  for(let x=346;x<505;x+=8)line(c,x,305,x,410,'#ccd7c5');
  round(c,338,384,124,35,p.shadow,7);round(c,336,378,124,35,p.blue,7);
  round(c,341,382,9,27,'#8b9bab',4);round(c,446,382,9,27,'#8b9bab',4);
  for(let x=354;x<444;x+=30)round(c,x,383,26,23,'#b5c1c9',3);
  round(c,358,385,15,13,'#dfa38e',3);
  table(c,379,324,61,29,p);rect(c,386,329,17,18,p.cream);rect(c,405,332,14,14,'#b8c6ae');
  chair(c,522,332,p);plant(c,601,276,p,14);plant(c,332,274,p,11);
  // Room names read as architectural labels, not floating interface cards.
  c.fillStyle='#9e9a91';c.font='10px monospace';c.textAlign='center';
  for(const [key,x,y] of [['sleep',108,177],['library',320,177],['work',532,177],['kitchen',155,277],['chat',472,277]])c.fillText(roomName(key).toUpperCase(),x,y);
}

export function drawDoors(c,state,p){
  for(const spot of Object.values(SPOTS)){
    const {x,y}=spot.door, upper=y<220;
    const near=Math.hypot(state.position.x-x,state.position.y-y)<75;
    const open=near||state.reduced;
    // Swing arc remains part of the floor plan. The leaf opens before the
    // crab reaches it and closes behind him; movement stays on door centers.
    c.save();c.strokeStyle='#d6d0c6';c.lineWidth=1;c.setLineDash([2,3]);
    c.beginPath();c.arc(x-27,y,54,upper?-Math.PI/2:0,upper?0:Math.PI/2);c.stroke();c.setLineDash([]);
    line(c,x-27,y,open?x-27:x+27,open?y+(upper?-54:54):y,p.wood,4);
    if(open)rect(c,x-26,y+(upper?-47:43),3,3,p.trim);else rect(c,x+19,y-2,3,3,p.trim);
    c.restore();
  }
}
export function drawWork(c,state,p){
  if(state.active&&state.room==='work'){
    const shift=state.reduced?0:Math.floor(state.elapsed*3)%3;
    rect(c,530,31,38,8,'#324e60');
    for(let i=0;i<3;i++)rect(c,532,32+i*2,12+(i+shift)%3*6,1,'#b7dbd6');
  }
  if(state.active&&state.activity==='organize'&&state.room==='library'){
    rect(c,350,112,15,5,'#a8bba0');rect(c,351,117,16,5,'#c6b398');rect(c,349,122,17,5,p.blue);
  }
}
