"""Pairwise interference check of all parts of a SOMA model.

Run with SOMA's Python:  $SOMA_PY -I tools/collide.py <project> [name filter]
Lists every pair whose solids overlap by more than 0.5 mm3. Plugs in sockets, screws in
their bosses and parts inside the maker's STEP overlap on purpose; anything else is a clash.
"""
import sys, os, itertools
os.chdir(sys.argv[1]); sys.path.insert(0, sys.argv[1])
from soma import build
parts = build.run_model(os.path.abspath(sys.argv[1]))
def bb(p):
    b = p["shape"].BoundingBox(); return b
boxes = [(p, bb(p)) for p in parts]
hits = []
flt = sys.argv[2] if len(sys.argv) > 2 else None
for (p, a), (q, b) in itertools.combinations(boxes, 2):
    if flt and flt not in p['name'] and flt not in q['name']:
        continue
    if a.xmax < b.xmin or b.xmax < a.xmin or a.ymax < b.ymin or b.ymax < a.ymin or a.zmax < b.zmin or b.zmax < a.zmin:
        continue
    try:
        v = p["shape"].intersect(q["shape"]).Volume()
    except Exception as e:
        v = -1
    if v > 0.5 or v < 0:
        hits.append((v, p["name"], q["name"]))
for v, a, b in sorted(hits, reverse=True):
    print(f"{v:9.1f} mm3  {a}  <->  {b}")
print(len(hits), "overlaps")
