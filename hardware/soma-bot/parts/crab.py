"""Crab parts: two claws resting on the desk in front of the face, three legs per side that
carry the body, and two eyes on stalks on top.

Every limb is a chain of tapered capsules (cones with spheres at the joints), so it reads as
one smooth printed piece. Limbs are separate parts: they plug into the shell, and the piece
inside the shell is cut away so nothing reaches into the electronics.
"""
import cadquery as cq

from .params import BLACK, DESK_Z, OUTER_BOTTOM, OUTER_TOP, OUTER_X, WHITE


def sphere(r, p):
    return cq.Solid.makeSphere(r, cq.Vector(*p), angleDegrees1=-90, angleDegrees2=90)


def capsule(p0, p1, r0, r1):
    """Tapered capsule from p0 (radius r0) to p1 (radius r1)."""
    a, b = cq.Vector(*p0), cq.Vector(*p1)
    d = b - a
    cone = cq.Solid.makeCone(r0, r1, d.Length, a, d.normalized())
    return cq.Workplane().add(cone).union(cq.Workplane().add(sphere(r0, p0))).union(cq.Workplane().add(sphere(r1, p1)))


def chain(points, radii):
    out = None
    for i in range(len(points) - 1):
        c = capsule(points[i], points[i + 1], radii[i], radii[i + 1])
        out = c if out is None else out.union(c)
    return out


def ellipsoid(center, radii):
    m = cq.Matrix([[radii[0], 0, 0, center[0]], [0, radii[1], 0, center[1]], [0, 0, radii[2], center[2]]])
    return cq.Workplane().add(sphere(1.0, (0, 0, 0)).transformGeometry(m))


def legs(cavity):
    """Three walking legs per side. Each bends out at the knee and ends in a point on the desk."""
    out = []
    for sgn, side in ((-1, "left"), (1, "right")):
        for i, y in enumerate((14.0, 32.0, 50.0)):
            splay = (i - 1) * 7.0
            hip = (sgn * (OUTER_X - 3.0), y, OUTER_BOTTOM + 12.0)
            knee = (sgn * (OUTER_X + 10.0), y + splay * 0.5, OUTER_BOTTOM + 11.0)
            foot = (sgn * (OUTER_X + 15.0), y + splay, DESK_Z + 3.0)
            leg = chain([hip, knee, foot], [6.0, 5.2, 3.0]).cut(cavity)
            out.append((f"Leg {side} {i + 1}", leg))
    return out


def claws(cavity):
    """Arm and pincer per side. The pincer lies on the desk in front of the face, fingers forward."""
    out = []
    z = DESK_Z
    for sgn, side in ((-1, "left"), (1, "right")):
        def p(x, y, zz):
            return (sgn * x, y, zz)
        x0 = OUTER_X
        arm = chain([p(x0 - 3.0, 6.0, -32.0), p(x0 + 14.0, -4.0, -42.0), p(x0 + 12.0, -16.0, z + 13.0)],
                    [7.5, 6.6, 6.0]).cut(cavity)
        palm = ellipsoid(p(x0 + 11.0, -30.0, z + 12.0), (14.0, 17.0, 11.5))
        fixed = chain([p(x0 + 5.0, -42.0, z + 11.0), p(x0 + 2.0, -54.0, z + 10.0), p(x0 + 7.0, -63.0, z + 9.0)],
                      [7.5, 5.0, 2.0])
        moving = chain([p(x0 + 18.0, -41.0, z + 13.0), p(x0 + 23.0, -52.0, z + 13.5), p(x0 + 15.5, -63.0, z + 12.0)],
                       [7.0, 4.6, 2.0])
        out.append((f"Claw arm {side}", arm, "arm"))
        out.append((f"Pincer {side}", palm.union(fixed).union(moving), "pincer"))
    return out


def eye_stalks():
    """Two eyes on short stalks behind the light bar, leaning a little outwards."""
    out = []
    for sgn, side in ((-1, "left"), (1, "right")):
        base = (sgn * 27.0, 36.0, OUTER_TOP - 2.0)
        top = (sgn * 31.0, 33.0, OUTER_TOP + 13.0)
        stalk = chain([base, top], [3.8, 3.0])
        eye = (top[0] + sgn * 0.5, top[1] - 1.0, top[2] + 6.0)
        ball = cq.Workplane().add(sphere(7.0, eye))
        glint = cq.Workplane().add(sphere(1.7, (eye[0] - sgn * 2.0, eye[1] - 5.6, eye[2] + 2.6)))
        out.append((f"Eye stalk {side}", stalk, "stalk"))
        out.append((f"Eye {side}", ball, BLACK))
        out.append((f"Eye glint {side}", glint, WHITE))
    return out
