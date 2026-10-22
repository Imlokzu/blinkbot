import { VISITOR } from './office-world.js';
import { planRoute, routeDoors, roomAt, canPlace } from './office-navigation.js';
export const BEDSIDE = {x:152,y:176};
export const canTeleport=point=>canPlace(point)&&routeDoors(point,[point]).length===0;

/** The visitor moves visually; it has no connection to chat/model requests. */
export class OfficeVisitor {
  constructor(home=VISITOR){
    this.home={...home};this.position={...home};this.route=[];this.doorIntents=[];
    this.destination={...home};this.returning=false;
  }
  teleport(point){
    if(!canTeleport(point))return false;
    this.home={...point};this.position={...point};this.destination={...point};
    this.route=[];this.doorIntents=[];this.returning=false;return true;
  }
  go(point,{returning=false,reduced=false}={}){
    const route=planRoute(this.position,point);
    if(route===null)return false;
    this.destination={...point};this.route=route;this.doorIntents=routeDoors(this.position,route);
    this.returning=returning;
    if(reduced){this.position={...point};this.route=[];this.doorIntents=[];this.returning=false;}
    return true;
  }
  step(dt,reduced=false){
    if(!this.route.length){this.returning=false;return;}
    if(reduced){this.position={...this.destination};this.route=[];this.doorIntents=[];this.returning=false;return;}
    const p=this.route[0],dx=p.x-this.position.x,dy=p.y-this.position.y,d=Math.hypot(dx,dy);
    const step=Math.max(0,Math.min(.1,dt))*180;
    if(d<=step){this.position={...p};this.route.shift();}
    else {this.position.x+=dx/d*step;this.position.y+=dy/d*step;}
    if(!this.route.length){this.doorIntents=[];this.returning=false;}
  }
  get room(){return roomAt(this.position)||'corridor';}
  get seated(){return !this.route.length&&Math.hypot(this.position.x-VISITOR.x,this.position.y-VISITOR.y)<1;}
}
