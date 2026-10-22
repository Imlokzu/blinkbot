import { WORLD, ROOMS, SPOTS } from './office-world.js';

// Coordinates are feet: the complete walking sprite spans x +/-27, y -33..+5.
// Exact tangency is permitted, penetration is not; a 56-unit door admits it.
const HALF_WIDTH = 27, HEAD = 33, TOE = 5, CLEARANCE = 0.01;
const EPSILON = 1e-9;
const rect = (x, y, w, h) => ({ left: x, top: y, right: x + w, bottom: y + h });
const finite = p => p != null && Number.isFinite(p.x) && Number.isFinite(p.y);
const inWorld = p => finite(p) && p.x >= 0 && p.x < WORLD.width
  && p.y >= 0 && p.y < WORLD.height;
const inside = (p, r) => p.x > r.left + EPSILON && p.x < r.right - EPSILON
  && p.y > r.top + EPSILON && p.y < r.bottom - EPSILON;
const inflate = r => ({
  left: r.left - HALF_WIDTH, right: r.right + HALF_WIDTH,
  top: r.top - TOE, bottom: r.bottom + HEAD,
});

const walls = [
  rect(0, 0, WORLD.width, 8), rect(0, WORLD.height - 8, WORLD.width, 8),
  rect(0, 8, 8, WORLD.height - 16), rect(WORLD.width - 8, 8, 8, WORLD.height - 16),
];
const doors = [];
for (const [id, room] of Object.entries(ROOMS)) {
  const { x, y } = SPOTS[id].door;
  const upper = y < WORLD.height / 2;
  const wallY = upper ? room.y + room.h : room.y - 8;
  walls.push(rect(room.x, wallY, x - 28 - room.x, 8));
  walls.push(rect(x + 28, wallY, room.x + room.w - x - 28, 8));
  // These are the physical opening, expanded by the sprite without padding.
  // Corridor feet at y=232 overlap neither row of door openings.
  doors.push({ id, bounds: inflate(rect(x - 28, wallY, 56, 8)), x, wallY });
  if (room.x + room.w < WORLD.width - 8) {
    walls.push(rect(room.x + room.w, room.y, 8, room.h));
  }
}

// Solid surfaces from drawHouse. Rugs, shadows, door swing arcs and the small
// objects resting on furniture have no independent floor collision. Circular
// tables/plants use conservative bounding boxes, keeping the graph rectangular.
const furniture = [
  rect(36, 34, 76, 108), rect(126, 43, 27, 28), rect(164, 87, 24, 53),
  rect(169, 25, 24, 24), // Bedroom plant.
  ...[228, 290, 352].map(x => rect(x, 19, 56, 22)),
  rect(228, 116, 44, 38), rect(346, 110, 45, 38), rect(387, 153, 20, 20),
  rect(493, 25, 109, 38), rect(522, 58, 44, 38), rect(446, 31, 28, 76),
  rect(590, 141, 28, 28), // Study plant.
  rect(16, 394, 244, 27), rect(15, 264, 30, 43), rect(96, 294, 56, 56),
  rect(80, 310, 18, 23), rect(151, 310, 18, 23), rect(102, 336, 44, 38),
  rect(270, 268, 24, 24), // Kitchen plant.
  rect(336, 378, 124, 35), rect(379, 324, 61, 29), rect(514, 324, 44, 38),
  rect(587, 262, 28, 28), rect(321, 263, 22, 22),
].map(r => inflate(r));
const wallBounds = walls.map(r => inflate(r));

/** First/last parameter where a segment enters a rectangle's open interior. */
function intersection(a, b, r) {
  let first = 0, last = 1;
  for (const [axis, low, high] of [['x', r.left, r.right], ['y', r.top, r.bottom]]) {
    const delta = b[axis] - a[axis];
    if (Math.abs(delta) < EPSILON) {
      if (a[axis] <= low + EPSILON || a[axis] >= high - EPSILON) return null;
    } else {
      const t1 = (low - a[axis]) / delta, t2 = (high - a[axis]) / delta;
      first = Math.max(first, Math.min(t1, t2));
      last = Math.min(last, Math.max(t1, t2));
      if (last - first <= EPSILON) return null;
    }
  }
  return last - first > EPSILON ? first : null;
}

const clear = (a, b, obstacles) => !obstacles.some(r => intersection(a, b, r) !== null);
const free = (p, obstacles) => inWorld(p) && !obstacles.some(r => inside(p, r));

/** The room under the feet, independent of destination or current activity. */
export function roomAt(point) {
  if (!inWorld(point)) return null;
  for (const [id, room] of Object.entries(ROOMS)) {
    if (point.x >= room.x && point.x <= room.x + room.w
        && point.y >= room.y && point.y <= room.y + room.h) return id;
  }
  return 'corridor';
}

/** Teleports require open floor for the entire sprite, with no station waiver. */
export function canPlace(point) {
  return free(point, wallBounds) && free(point, furniture);
}

/**
 * A bounded visibility graph: at most four corners per static obstacle plus two
 * safe approach points per door. Dijkstra has no grid resolution or retry loop.
 * Furniture overlapping the exact endpoint footprint is waived for this route
 * to preserve intentional bed/desk/chair contact; walls are never waived.
 */
export function planRoute(start, goal) {
  if (!free(start, wallBounds) || !free(goal, wallBounds)) return null;
  const obstacles = wallBounds.concat(furniture.filter(r => !inside(start, r) && !inside(goal, r)));
  if (start.x === goal.x && start.y === goal.y) return [];
  if (clear(start, goal, obstacles)) return [{ x: goal.x, y: goal.y }];

  const nodes = [{ x: start.x, y: start.y }, { x: goal.x, y: goal.y }];
  const seen = new Set(nodes.map(p => `${p.x},${p.y}`));
  const add = p => {
    const key = `${p.x},${p.y}`;
    if (!seen.has(key) && free(p, obstacles)) { seen.add(key); nodes.push(p); }
  };
  for (const r of obstacles) {
    for (const x of [r.left, r.right]) {
      for (const y of [r.top, r.bottom]) add({ x, y });
    }
  }
  for (const door of doors) {
    add({ x: door.x, y: door.wallY - TOE - CLEARANCE });
    add({ x: door.x, y: door.wallY + 8 + HEAD + CLEARANCE });
  }

  const distance = new Float64Array(nodes.length).fill(Infinity);
  const previous = new Int16Array(nodes.length).fill(-1);
  const visited = new Uint8Array(nodes.length);
  distance[0] = 0;
  for (let step = 0; step < nodes.length; step++) {
    let nearest = -1;
    for (let i = 0; i < nodes.length; i++) {
      if (!visited[i] && (nearest < 0 || distance[i] < distance[nearest])) nearest = i;
    }
    if (nearest < 0 || !Number.isFinite(distance[nearest])) return null;
    if (nearest === 1) {
      const route = [];
      for (let i = 1; i !== 0; i = previous[i]) route.push(nodes[i]);
      return route.reverse();
    }
    visited[nearest] = 1;
    for (let i = 0; i < nodes.length; i++) {
      if (visited[i]) continue;
      const candidate = distance[nearest] + Math.hypot(
        nodes[i].x - nodes[nearest].x, nodes[i].y - nodes[nearest].y);
      if (candidate < distance[i] && clear(nodes[nearest], nodes[i], obstacles)) {
        distance[i] = candidate;
        previous[i] = nearest;
      }
    }
  }
  return null;
}

/** Door IDs in traversal order, once each, including a partially exited door. */
export function routeDoors(start, route) {
  if (!finite(start) || !Array.isArray(route) || !route.every(finite)) return [];
  const result = [], seen = new Set();
  let from = start;
  for (const to of route) {
    const hits = [];
    for (const door of doors) {
      const time = intersection(from, to, door.bounds);
      if (time !== null) hits.push({ id: door.id, time });
    }
    hits.sort((a, b) => a.time - b.time);
    for (const hit of hits) {
      if (!seen.has(hit.id)) { seen.add(hit.id); result.push(hit.id); }
    }
    from = to;
  }
  return result;
}
