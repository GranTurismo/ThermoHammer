"""ThermoHammer GPU benchmark — "Neon Rain" night-city scene generator (GPU Test 2).

A rain-soaked downtown avenue at night, built to be several times heavier than Lake
Valley: ~110 procedurally composed buildings with instanced window modules, grilles,
AC units, balconies and fire escapes; neon, lightbox and blade signs made from real
text; street furniture; an intersection with working traffic signals; ~140 cars driven
by a car-following traffic model (they brake and queue at red lights); pedestrians
under umbrellas; an elevated railway with a passing train; drones and a police
searchlight; lantern strings; a distant skyline. Seeded and procedural like
build_lake_valley.py, so every export produces the identical workload.

Run in Blender 5.2+:
  blender --background --python build_neon_city.py -- --save neon_city.blend
  ... -- --export ../ThermoHammerAndroid/app/src/main/assets/neon_city.bin
    writes the phone version for the app's GPU benchmark (format at export_app_scene).

The active scene is cleared and rebuilt; data-blocks are prefixed "NC_".
"""

import math
import random
import struct
import sys
import time

import bmesh
import bpy
from mathutils import Matrix, Vector, noise

# ── Timeline ─────────────────────────────────────────────────────────────────

SEED = 11
FPS = 30
DURATION = 60.0                # benchmark timeline: camera flight, traffic, train — seconds
FRAMES = int(DURATION * FPS)
SIM_DT = 1.0 / 30.0            # traffic model step (the app uses the same step)
SIM_WARMUP = 40.0              # traffic runs this long before t = 0 so it starts flowing

# ── Street layout (metres; +Y runs down the avenue, X across it, Z up) ──────

LANE_W = 3.5
ROAD_HALF = 7.0                # four lanes
WALK_W = 5.5
FRONT = ROAD_HALF + WALK_W     # building line
CURB_H = 0.15
STREET_Y0, STREET_Y1 = -110.0, 650.0
CROSS_Y = 250.0                # cross-street centreline (runs along X)
CROSS_X1 = 240.0               # cross street extends to ±CROSS_X1
CORNER_DEPTH = 28.0            # corner buildings face both streets
RAIL_Y = 430.0                 # elevated railway centreline (runs along X)
RAIL_Z = 11.0                  # rail deck top
RAIL_GAP = 9.0                 # no buildings within this distance of the viaduct
LANTERN_Y = (110.0, 215.0)     # lantern strings across the avenue
FLOOR_H = 3.2
GROUND_H = 4.8
TAU = 2.0 * math.pi

# ── Workload budget ──────────────────────────────────────────────────────────

SKYLINE_TOWERS = 190
MIDROW_PER_SIDE = 26
PED_COUNT = 150
CARS_PER_AVENUE_LANE = 26
CARS_PER_CROSS_LANE = 9
TRAIN_CARS = 6
RAIN_STREAKS = 60000           # Blender demo only — the app generates rain in the shader

# ── Material classes: the vertex "class" byte the app shades by ─────────────

CLASSES = ["concrete", "brick", "tile", "metal", "glass", "shopglass", "emissive", "asphalt",
           "paving", "paint", "carpaint", "carglass", "rubber", "chrome", "headlight",
           "taillight", "fabric", "skin", "foliage", "screen", "tower", "lantern", "trainwin",
           "wood"]
K = {name: i for i, name in enumerate(CLASSES)}

# Channel byte (stored in the normal's 4th component) for animated/emissive classes:
#   bits 0-4 animation group, bits 5-6 intensity tier (×1, ×2.5, ×6, ×14 in the app).
#   group 0 steady · 1-8 "faulty neon" (the app now keeps them steady) · 9-12 letter chase ·
#   13-16 slow pulse ·
#   24-29 traffic-signal lamps (24 + lamp + 3·signal) · 30 aviation blink · 31 strobe.
# Pedestrian meshes use the channel for the moving part: 1/2 left/right leg, 3/4 arms.


def chan(group=0, tier=1):
    return (group & 31) | ((tier & 3) << 5)


# ── Small maths ──────────────────────────────────────────────────────────────

_noise = noise.noise


def clamp(x, a, b):
    return a if x < a else b if x > b else x


def smoothstep(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def lerp(a, b, t):
    return a + (b - a) * t


def mix3(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def scale3(c, s):
    return (c[0] * s, c[1] * s, c[2] * s)


def jitter(rng, c, amount):
    f = 1.0 + rng.uniform(-amount, amount)
    return (c[0] * f, c[1] * f, c[2] * f)


def newell(pts):
    nx = ny = nz = 0.0
    n = len(pts)
    for i in range(n):
        a, b = pts[i], pts[(i + 1) % n]
        nx += (a[1] - b[1]) * (a[2] + b[2])
        ny += (a[2] - b[2]) * (a[0] + b[0])
        nz += (a[0] - b[0]) * (a[1] + b[1])
    return Vector((nx, ny, nz))


# ── Geometry builder ─────────────────────────────────────────────────────────

class Geo:
    """Indexed triangles with per-vertex position, normal, linear colour, class and channel.

    Flat faces get their own vertices (crisp architecture); smooth parts (tubes, lofts,
    lathes) share vertices. The same arrays become a Blender mesh and the app's vertex buffer.
    """

    __slots__ = ("p", "n", "c", "k", "ch", "idx")

    def __init__(self):
        self.p, self.n, self.c, self.k, self.ch, self.idx = [], [], [], [], [], []

    @property
    def nv(self):
        return len(self.k)

    @property
    def nt(self):
        return len(self.idx) // 3

    def vert(self, p, n, c, k, ch=0):
        self.p.extend((p[0], p[1], p[2]))
        self.n.extend((n[0], n[1], n[2]))
        self.c.extend((c[0], c[1], c[2]))
        self.k.append(k)
        self.ch.append(ch)
        return len(self.k) - 1

    def poly(self, pts, c, k, ch=0, face=None):
        """Flat convex polygon; with `face` (desired outward normal) the winding is fixed."""
        nrm = newell(pts)
        if nrm.length_squared < 1e-16:
            return
        if face is not None and nrm.dot(Vector(face)) < 0.0:
            pts = pts[::-1]
            nrm = -nrm
        nrm.normalize()
        base = len(self.k)
        for q in pts:
            self.vert(q, nrm, c, k, ch)
        for i in range(1, len(pts) - 1):
            self.idx.extend((base, base + i, base + i + 1))

    def quad(self, a, b, c, d, col, k, ch=0, face=None):
        self.poly((a, b, c, d), col, k, ch, face)

    def box(self, m, size, col, k, ch=0, skip=(), center=(0.0, 0.0, 0.0)):
        """Box of `size` centred at `center` in m's local frame. skip: any of -x +x -y +y -z +z."""
        hx, hy, hz = size[0] / 2.0, size[1] / 2.0, size[2] / 2.0
        cx, cy, cz = center
        c = [m @ Vector((cx + ix * hx, cy + iy * hy, cz + iz * hz))
             for ix, iy, iz in ((-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1),
                                (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1))]
        for name, quad in (("-z", (0, 3, 2, 1)), ("+z", (4, 5, 6, 7)), ("-y", (0, 1, 5, 4)),
                           ("+x", (1, 2, 6, 5)), ("+y", (2, 3, 7, 6)), ("-x", (3, 0, 4, 7))):
            if name not in skip:
                self.poly([c[i] for i in quad], col, k, ch)

    def tube(self, p0, p1, r0, r1, seg, col, k, ch=0, caps=False):
        p0, p1 = Vector(p0), Vector(p1)
        axis = (p1 - p0).normalized()
        u = axis.orthogonal().normalized()
        v = axis.cross(u)
        base = len(self.k)
        for p, r in ((p0, r0), (p1, r1)):
            for j in range(seg):
                a = TAU * j / seg
                d = u * math.cos(a) + v * math.sin(a)
                self.vert(p + d * r, d, col, k, ch)
        for j in range(seg):
            j1 = (j + 1) % seg
            self.idx.extend((base + j, base + j1, base + seg + j1, base + j, base + seg + j1, base + seg + j))
        if caps:
            for p, r, sign in ((p0, r0, -1.0), (p1, r1, 1.0)):
                pts = [p + (u * math.cos(TAU * j / seg) + v * math.sin(TAU * j / seg)) * r for j in range(seg)]
                self.poly(pts, col, k, ch, face=axis * sign)

    def lathe(self, m, profile, seg, col, k, ch=0, cap_top=False):
        """Surface of revolution about local Z from (radius, z) pairs, bottom to top."""
        base = len(self.k)
        rows = len(profile)
        for i, (r, z) in enumerate(profile):
            r0, z0 = profile[max(i - 1, 0)]
            r1, z1 = profile[min(i + 1, rows - 1)]
            tr, tz = r1 - r0, z1 - z0                       # profile tangent → normal (tz, -tr)
            ln = math.hypot(tr, tz) or 1.0
            nr, nz = tz / ln, -tr / ln
            for j in range(seg):
                a = TAU * j / seg
                ca, sa = math.cos(a), math.sin(a)
                p = m @ Vector((r * ca, r * sa, z))
                n = (m.to_3x3() @ Vector((nr * ca, nr * sa, nz))).normalized()
                self.vert(p, n, col, k, ch)
        for i in range(rows - 1):
            a, b = base + i * seg, base + (i + 1) * seg
            for j in range(seg):
                j1 = (j + 1) % seg
                self.idx.extend((a + j, a + j1, b + j1, a + j, b + j1, b + j))
        if cap_top:
            r, z = profile[-1]
            pts = [m @ Vector((r * math.cos(TAU * j / seg), r * math.sin(TAU * j / seg), z)) for j in range(seg)]
            self.poly(pts, col, k, ch, face=m.to_3x3() @ Vector((0, 0, 1)))

    def loft(self, rings, col, k, ch=0, closed=True, cap_ends=False):
        """Smooth skin through rings of equal length (each ring CCW seen from its far end)."""
        base = len(self.k)
        n = len(rings[0])
        start_idx = len(self.idx)
        for ring_pts in rings:
            for q in ring_pts:
                self.vert(q, (0.0, 0.0, 1.0), col, k, ch)
        span = n if closed else n - 1
        for r in range(len(rings) - 1):
            a, b = base + r * n, base + (r + 1) * n
            for j in range(span):
                j1 = (j + 1) % n
                self.idx.extend((a + j, b + j, b + j1, a + j, b + j1, a + j1))
        self.smooth_normals(base, start_idx)
        if cap_ends:
            self.poly(list(reversed(rings[0])), col, k, ch)
            self.poly(list(rings[-1]), col, k, ch)

    def smooth_normals(self, v0, i0):
        acc = {}
        idx, p = self.idx, self.p
        for t in range(i0, len(idx), 3):
            a, b, c = idx[t], idx[t + 1], idx[t + 2]
            pa = Vector(p[3 * a:3 * a + 3])
            fn = (Vector(p[3 * b:3 * b + 3]) - pa).cross(Vector(p[3 * c:3 * c + 3]) - pa)
            for v in (a, b, c):
                if v >= v0:
                    acc[v] = acc.get(v, Vector()) + fn
        for v, s in acc.items():
            if s.length_squared > 0.0:
                s.normalize()
                self.n[3 * v:3 * v + 3] = (s.x, s.y, s.z)

    def append(self, other, m=None, k=None, col=None, ch=None):
        """Copy `other` in, optionally transformed and with class/colour/channel overridden."""
        base = len(self.k)
        if m is None:
            self.p.extend(other.p)
            self.n.extend(other.n)
            flip = False
        else:
            nm = m.to_3x3().inverted_safe().transposed()
            flip = m.to_3x3().determinant() < 0.0
            op, on = other.p, other.n
            for i in range(other.nv):
                q = m @ Vector(op[3 * i:3 * i + 3])
                d = (nm @ Vector(on[3 * i:3 * i + 3])).normalized()
                self.p.extend((q.x, q.y, q.z))
                self.n.extend((d.x, d.y, d.z))
        if col is None:
            self.c.extend(other.c)
        else:
            self.c.extend(col * other.nv)
        self.k.extend(other.k if k is None else [k] * other.nv)
        self.ch.extend(other.ch if ch is None else [ch] * other.nv)
        if flip:
            src = other.idx
            for t in range(0, len(src), 3):
                self.idx.extend((src[t] + base, src[t + 2] + base, src[t + 1] + base))
        else:
            self.idx.extend(i + base for i in other.idx)

    def bounds(self):
        xs, ys, zs = self.p[0::3], self.p[1::3], self.p[2::3]
        return (min(xs), min(ys), min(zs)), (max(xs), max(ys), max(zs))


def frame(origin, right, out, up=(0.0, 0.0, 1.0)):
    """4×4 matrix whose local X/Y/Z axes are right/out/up at `origin`."""
    r, o, u = Vector(right), Vector(out), Vector(up)
    m = Matrix((
        (r.x, o.x, u.x, origin[0]),
        (r.y, o.y, u.y, origin[1]),
        (r.z, o.z, u.z, origin[2]),
        (0.0, 0.0, 0.0, 1.0)))
    return m


def at(x, y, z, rz=0.0):
    return Matrix.Translation((x, y, z)) @ Matrix.Rotation(rz, 4, 'Z')


def yaw_of(out):
    """Z rotation that turns local +Y into the horizontal direction `out`."""
    return math.atan2(-out[0], out[1])


# ── Text → geometry (signs) ──────────────────────────────────────────────────

_text_cache = {}


def text_geo(body, mode):
    """Glyphs of `body` scaled to 1 m tall, centred on the origin in local XY (X right, Y up),
    facing +Z. mode 'tube' = neon tubing along the glyph outlines, 'solid' = filled letters.
    Returns (Geo, width)."""
    key = (body, mode)
    if key in _text_cache:
        return _text_cache[key]
    cu = bpy.data.curves.new("NC_tmp_text", 'FONT')
    cu.body = body
    cu.size = 1.0
    cu.align_x = 'CENTER'
    cu.align_y = 'CENTER'
    cu.resolution_u = 3 if mode == 'tube' else 2
    if mode == 'tube':
        cu.fill_mode = 'NONE'                       # 2D curve: outline only — the tube is the bevel
        cu.bevel_depth = 0.028
        cu.bevel_resolution = 1
        cu.extrude = 0.0
    else:
        cu.extrude = 0.025
        cu.bevel_depth = 0.0
    ob = bpy.data.objects.new("NC_tmp_text", cu)
    bpy.context.scene.collection.objects.link(ob)
    dg = bpy.context.evaluated_depsgraph_get()
    me = bpy.data.meshes.new_from_object(ob.evaluated_get(dg))
    bpy.data.objects.remove(ob, do_unlink=True)
    bpy.data.curves.remove(cu)

    me.calc_loop_triangles()
    nv, nt = len(me.vertices), len(me.loop_triangles)
    co, vn, tv = [0.0] * (3 * nv), [0.0] * (3 * nv), [0] * (3 * nt)
    me.vertices.foreach_get("co", co)
    me.vertex_normals.foreach_get("vector", vn)
    me.loop_triangles.foreach_get("vertices", tv)
    bpy.data.meshes.remove(me)

    xs, ys = co[0::3], co[1::3]
    w, h = max(xs) - min(xs), max(ys) - min(ys)
    s = 1.0 / max(h, 1e-6)
    cx, cy = (max(xs) + min(xs)) / 2.0, (max(ys) + min(ys)) / 2.0
    g = Geo()
    for i in range(nv):
        g.vert(((co[3 * i] - cx) * s, (co[3 * i + 1] - cy) * s, co[3 * i + 2] * s),
               (vn[3 * i], vn[3 * i + 1], vn[3 * i + 2]), (1.0, 1.0, 1.0), 0)
    g.idx = tv
    _text_cache[key] = (g, w * s)
    return _text_cache[key]


# ── Palettes (linear RGB; emissive colours are hues — the app scales intensity) ──

WALL_CONCRETE = [(0.30, 0.29, 0.27), (0.21, 0.21, 0.22), (0.34, 0.31, 0.27), (0.17, 0.18, 0.20),
                 (0.26, 0.24, 0.22), (0.38, 0.36, 0.33)]
WALL_TILE = [(0.44, 0.42, 0.37), (0.24, 0.32, 0.35), (0.42, 0.31, 0.29), (0.33, 0.35, 0.31),
             (0.36, 0.38, 0.42), (0.47, 0.44, 0.33)]
WALL_BRICK = [(0.26, 0.10, 0.07), (0.21, 0.11, 0.08), (0.31, 0.16, 0.10), (0.18, 0.09, 0.07)]
GLASS_TINT = [(0.020, 0.030, 0.034), (0.015, 0.022, 0.030), (0.030, 0.030, 0.028)]
REVEAL = (0.32, 0.31, 0.29)
METAL_DARK = (0.035, 0.038, 0.042)
METAL_GREY = (0.16, 0.17, 0.18)
ALU = (0.42, 0.43, 0.44)
RUBBER = (0.018, 0.018, 0.020)
ASPHALT = (0.030, 0.030, 0.032)
PAVING = (0.115, 0.112, 0.105)
CURB = (0.20, 0.20, 0.19)
PAINT_WHITE = (0.72, 0.72, 0.70)
PAINT_YELLOW = (0.62, 0.40, 0.04)
WOOD = (0.16, 0.09, 0.05)
SKIN_TONES = [(0.62, 0.43, 0.32), (0.45, 0.30, 0.20), (0.28, 0.17, 0.11), (0.70, 0.52, 0.42)]
HAIR = (0.02, 0.016, 0.014)
FOLIAGE = (0.035, 0.07, 0.025)

NEON = {
    "pink": (1.0, 0.05, 0.32), "magenta": (0.85, 0.0, 1.0), "cyan": (0.0, 0.72, 1.0),
    "blue": (0.08, 0.22, 1.0), "red": (1.0, 0.03, 0.02), "orange": (1.0, 0.32, 0.02),
    "yellow": (1.0, 0.72, 0.05), "green": (0.08, 1.0, 0.22), "white": (1.0, 0.86, 0.72),
    "purple": (0.42, 0.04, 1.0), "teal": (0.0, 1.0, 0.68),
}
NEON_NAMES = list(NEON)
SHOP_LIGHT = [(1.0, 0.66, 0.36), (0.90, 0.93, 1.0), (0.78, 0.95, 1.0), (1.0, 0.52, 0.70),
              (1.0, 0.80, 0.55), (0.70, 0.85, 1.0)]

SIGN_WORDS = ["RAMEN", "NOODLE BAR", "SUSHI", "HOTEL", "KARAOKE", "PHARMACY", "BAR", "COCKTAILS",
              "CINEMA", "ARCADE", "TATTOO", "LAUNDRY", "MASSAGE", "OPEN 24H", "CAFE", "BOOKS",
              "PAWN", "DUMPLINGS", "TEA HOUSE", "JAZZ CLUB", "CURRY", "BBQ", "LIQUOR", "MOTEL",
              "DENTIST", "EXCHANGE", "PHONE REPAIR", "GOLD", "BEAUTY", "PIZZA", "NIGHT MARKET",
              "LIVE MUSIC", "DISCO", "GAME CENTER", "BUBBLE TEA", "PHO", "WOK", "DIM SUM", "BAKERY",
              "FLOWERS", "OPTICAL", "PARKING", "ATM", "BEER", "SAKE", "IZAKAYA", "YAKITORI", "UDON",
              "KEBAB", "TACOS", "HOSTEL", "CLINIC", "VINYL", "DELI", "POOL HALL", "CLUB", "LOTUS",
              "DRAGON", "PEARL", "ORCHID", "GOLDEN LOTUS", "NEON DRAGON", "KYOTO", "NOVA", "ZENITH"]
BLADE_WORDS = ["HOTEL", "BAR", "KARAOKE", "CLUB", "CAFE", "SAKE", "PHO", "INN", "LIVE", "RAMEN",
               "MOTEL", "GAMES", "TEA", "SUSHI", "JAZZ", "DISCO", "BEER", "WOK", "SPA", "BBQ"]


# ── Instanced prototypes ─────────────────────────────────────────────────────
# Facade modules use: X along the wall, +Y out of the wall, Z up (a proper rotation, so
# +X is the viewer's LEFT when facing the wall); the wall plane is y = 0 and the
# opening's bottom edge sits at z = 0.

def _reveal(g, w, h, d, col=REVEAL, k=K["concrete"]):
    x0, x1 = -w / 2.0, w / 2.0
    g.quad((x0, 0, 0), (x0, -d, 0), (x0, -d, h), (x0, 0, h), col, k, face=(1, 0, 0))
    g.quad((x1, 0, 0), (x1, -d, 0), (x1, -d, h), (x1, 0, h), col, k, face=(-1, 0, 0))
    g.quad((x0, 0, h), (x1, 0, h), (x1, -d, h), (x0, -d, h), col, k, face=(0, 0, -1))
    g.quad((x0, 0, 0), (x1, 0, 0), (x1, -d, 0), (x0, -d, 0), col, k, face=(0, 0, 1))


def _pane(g, w, h, y, style, x0=None, z0=0.0):
    """Glass. Its colour carries interior parameters, not albedo: r = room half-width / 4,
    g = window height / 4, b = interior style (0..1)."""
    x0 = -w / 2.0 if x0 is None else x0
    params = (w / 2.0 / 4.0, h / 4.0, style)
    g.quad((x0, y, z0), (x0 + w, y, z0), (x0 + w, y, z0 + h), (x0, y, z0 + h), params, K["glass"],
           face=(0, 1, 0))


def _bar(g, a, b, t, col, k):
    """Square bar between two points (axis-aligned or not)."""
    a, b = Vector(a), Vector(b)
    axis = b - a
    length = axis.length
    if length < 1e-6:
        return
    z = axis / length
    x = z.orthogonal().normalized()
    y = z.cross(x)
    m = Matrix(((x.x, y.x, z.x, a.x), (x.y, y.y, z.y, a.y), (x.z, y.z, z.z, a.z), (0, 0, 0, 1)))
    g.box(m, (t, t, length), col, k, center=(0.0, 0.0, length / 2.0))


def _frame(g, w, h, y, t, col, k, mullions=0, transoms=0, depth=0.05):
    m = Matrix.Translation((0.0, y, 0.0))
    for xc, zc, sx, sz in ((0.0, t / 2.0, w, t), (0.0, h - t / 2.0, w, t),
                           (-w / 2.0 + t / 2.0, h / 2.0, t, h), (w / 2.0 - t / 2.0, h / 2.0, t, h)):
        g.box(m, (sx, depth, sz), col, k, center=(xc, 0.0, zc))
    for i in range(1, mullions + 1):
        g.box(m, (t * 0.8, depth, h), col, k, center=(-w / 2.0 + w * i / (mullions + 1), 0.0, h / 2.0))
    for i in range(1, transoms + 1):
        g.box(m, (w, depth, t * 0.8), col, k, center=(0.0, 0.0, h * i / (transoms + 1)))


def make_window(kind, rng):
    g = Geo()
    if kind in ("res", "res_grille", "res_ac"):
        w, h, d = 1.5, 1.5, 0.18
        _reveal(g, w, h, d)
        _pane(g, w, h, -d + 0.02, 0.15)
        _frame(g, w, h, -d + 0.05, 0.05, ALU, K["chrome"], mullions=1)
        g.box(Matrix.Identity(4), (w + 0.16, 0.14, 0.06), REVEAL, K["concrete"], center=(0, 0.04, -0.03))
        if kind == "res_grille":                     # Hong Kong security cage
            cage = 0.32
            m = Matrix.Identity(4)
            for z in (-0.02, h + 0.02):
                _bar(g, (-w / 2 - 0.05, cage, z), (w / 2 + 0.05, cage, z), 0.03, METAL_GREY, K["metal"])
                _bar(g, (-w / 2 - 0.05, 0.0, z), (-w / 2 - 0.05, cage, z), 0.03, METAL_GREY, K["metal"])
                _bar(g, (w / 2 + 0.05, 0.0, z), (w / 2 + 0.05, cage, z), 0.03, METAL_GREY, K["metal"])
            for i in range(9):
                x = -w / 2 - 0.05 + (w + 0.1) * i / 8.0
                _bar(g, (x, cage, -0.02), (x, cage, h + 0.02), 0.018, METAL_GREY, K["metal"])
            for z in (h * 0.33, h * 0.66):
                _bar(g, (-w / 2 - 0.05, cage, z), (w / 2 + 0.05, cage, z), 0.016, METAL_GREY, K["metal"])
            g.quad((-w / 2 - 0.1, 0.0, h + 0.18), (w / 2 + 0.1, 0.0, h + 0.18),
                   (w / 2 + 0.1, cage + 0.08, h + 0.04), (-w / 2 - 0.1, cage + 0.08, h + 0.04),
                   METAL_GREY, K["metal"], face=(0, 0.3, 1))
        if kind == "res_ac":
            m = Matrix.Identity(4)
            g.box(m, (0.84, 0.48, 0.52), (0.62, 0.62, 0.60), K["metal"], center=(0.28, 0.27, -0.42))
            g.quad((-0.12, 0.512, -0.64), (0.68, 0.512, -0.64), (0.68, 0.512, -0.2), (-0.12, 0.512, -0.2),
                   RUBBER, K["rubber"], face=(0, 1, 0))
            for x in (-0.05, 0.6):
                _bar(g, (x, 0.0, -0.68), (x, 0.5, -0.68), 0.03, METAL_GREY, K["metal"])
            _bar(g, (0.6, 0.45, -0.6), (0.6, 0.45, -1.4), 0.012, RUBBER, K["rubber"])   # drip pipe
    elif kind == "office":
        w, h, d = 2.8, 2.0, 0.12
        _reveal(g, w, h, d, col=METAL_GREY, k=K["metal"])
        _pane(g, w, h, -d + 0.02, 0.85)
        _frame(g, w, h, -d + 0.05, 0.06, METAL_DARK, K["metal"], mullions=2)
    elif kind == "old":
        w, h, d = 1.1, 2.0, 0.24
        _reveal(g, w, h, d, col=(0.42, 0.40, 0.36))
        _pane(g, w, h, -d + 0.03, 0.4)
        white = (0.62, 0.60, 0.56)
        _frame(g, w, h, -d + 0.06, 0.06, white, K["wood"], mullions=1, transoms=2)
        g.box(Matrix.Identity(4), (w + 0.24, 0.16, 0.1), (0.45, 0.43, 0.39), K["concrete"], center=(0, 0.05, -0.05))
        g.box(Matrix.Identity(4), (w + 0.3, 0.1, 0.22), (0.45, 0.43, 0.39), K["concrete"], center=(0, 0.03, h + 0.11))
    elif kind == "shop_upper":
        w, h, d = 2.4, 1.8, 0.15
        _reveal(g, w, h, d)
        _pane(g, w, h, -d + 0.02, 0.6)
        _frame(g, w, h, -d + 0.05, 0.05, METAL_DARK, K["metal"], mullions=2, transoms=1)
        g.box(Matrix.Identity(4), (w + 0.2, 0.12, 0.06), REVEAL, K["concrete"], center=(0, 0.04, -0.03))
    elif kind == "curtain":
        w, h = 1.5, FLOOR_H
        _pane(g, w, h - 0.55, 0.0, 0.95, z0=0.45)
        g.quad((-w / 2, 0.0, 0.0), (w / 2, 0.0, 0.0), (w / 2, 0.0, 0.45), (-w / 2, 0.0, 0.45),
               (0.05, 0.06, 0.07), K["carglass"], face=(0, 1, 0))           # spandrel glass
        g.quad((-w / 2, 0.0, h - 0.1), (w / 2, 0.0, h - 0.1), (w / 2, 0.0, h), (-w / 2, 0.0, h),
               (0.05, 0.06, 0.07), K["carglass"], face=(0, 1, 0))
        m = Matrix.Identity(4)
        g.box(m, (0.07, 0.16, h), ALU, K["chrome"], center=(-w / 2 + 0.035, 0.08, h / 2))
        g.box(m, (w, 0.1, 0.06), ALU, K["chrome"], center=(0.0, 0.05, 0.45))
    return g


def make_balcony(rng, laundry):
    g = Geo()
    m = Matrix.Identity(4)
    w, dep = 2.6, 1.15
    g.box(m, (w, dep, 0.16), (0.36, 0.35, 0.33), K["concrete"], center=(0.0, dep / 2, -0.08))
    rail_h = 1.05
    for x0, y0, x1, y1 in ((-w / 2, dep, w / 2, dep), (-w / 2, 0.0, -w / 2, dep), (w / 2, 0.0, w / 2, dep)):
        _bar(g, (x0, y0, rail_h), (x1, y1, rail_h), 0.045, METAL_DARK, K["metal"])
        _bar(g, (x0, y0, 0.1), (x1, y1, 0.1), 0.03, METAL_DARK, K["metal"])
        n = max(2, int(math.hypot(x1 - x0, y1 - y0) / 0.14))
        for i in range(n + 1):
            x, y = lerp(x0, x1, i / n), lerp(y0, y1, i / n)
            _bar(g, (x, y, 0.0), (x, y, rail_h), 0.018, METAL_DARK, K["metal"])
    if laundry:
        _bar(g, (-w / 2 + 0.1, dep - 0.15, 2.2), (w / 2 - 0.1, dep - 0.15, 2.2), 0.01, METAL_GREY, K["metal"])
        x = -w / 2 + 0.25
        while x < w / 2 - 0.4:
            cw, ch = rng.uniform(0.3, 0.55), rng.uniform(0.4, 0.8)
            c = rng.choice([(0.5, 0.06, 0.06), (0.08, 0.15, 0.45), (0.55, 0.55, 0.52), (0.1, 0.32, 0.12),
                            (0.55, 0.42, 0.10), (0.06, 0.06, 0.07)])
            g.quad((x, dep - 0.15, 2.2 - ch), (x + cw, dep - 0.15, 2.2 - ch), (x + cw, dep - 0.15, 2.2),
                   (x, dep - 0.15, 2.2), c, K["fabric"])
            x += cw + rng.uniform(0.05, 0.2)
    else:
        for x in (-0.8, 0.3, 0.9):
            g.box(m, (0.3, 0.3, 0.3), (0.2, 0.09, 0.05), K["concrete"], center=(x, 0.25, 0.15))
            g.box(m, (0.42, 0.42, 0.45), FOLIAGE, K["foliage"], center=(x, 0.25, 0.5))
    return g


def make_fire_escape():
    """One storey of a steel fire escape: platform at z = 0, stair rising to the next floor."""
    g = Geo()
    m = Matrix.Identity(4)
    w, dep = 3.4, 1.25
    g.box(m, (w, dep, 0.05), METAL_DARK, K["metal"], center=(0.0, dep / 2, 0.0))
    for i in range(18):                          # grating slats read in silhouette
        x = -w / 2 + w * (i + 0.5) / 18
        g.box(m, (0.02, dep, 0.04), METAL_GREY, K["metal"], center=(x, dep / 2, -0.045))
    for x0, y0, x1, y1 in ((-w / 2, dep, w / 2, dep), (-w / 2, 0.0, -w / 2, dep), (w / 2, 0.0, w / 2, dep)):
        _bar(g, (x0, y0, 0.95), (x1, y1, 0.95), 0.035, METAL_DARK, K["metal"])
        n = max(2, int(math.hypot(x1 - x0, y1 - y0) / 0.6))
        for i in range(n + 1):
            x, y = lerp(x0, x1, i / n), lerp(y0, y1, i / n)
            _bar(g, (x, y, 0.0), (x, y, 0.95), 0.025, METAL_DARK, K["metal"])
    # stair from the left end of this platform up to the right end of the next
    steps = 13
    y0, y1 = 0.25, dep - 0.2
    xa, xb = -w / 2 + 0.3, w / 2 - 0.3
    for side in (y0, y1):
        _bar(g, (xa, side, 0.0), (xb, side, FLOOR_H), 0.06, METAL_DARK, K["metal"])
    for i in range(steps):
        t = (i + 0.5) / steps
        g.box(m, (0.22, y1 - y0, 0.03), METAL_GREY, K["metal"],
              center=(lerp(xa, xb, t), (y0 + y1) / 2, FLOOR_H * t))
    _bar(g, (xa, y1, 0.9), (xb, y1, FLOOR_H + 0.9), 0.03, METAL_DARK, K["metal"])
    return g


def make_ac_unit():
    g = Geo()
    m = Matrix.Identity(4)
    g.box(m, (0.86, 0.5, 0.6), (0.60, 0.60, 0.58), K["metal"], center=(0.0, 0.27, 0.3))
    g.lathe(Matrix.Translation((0.12, 0.525, 0.3)) @ Matrix.Rotation(-math.pi / 2, 4, 'X'),
            [(0.0, 0.0), (0.2, 0.0), (0.2, 0.005)], 10, RUBBER, K["rubber"])
    for x in (-0.35, 0.35):
        _bar(g, (x, 0.0, 0.0), (x, 0.5, 0.0), 0.035, METAL_GREY, K["metal"])
    return g


# ── Street furniture ─────────────────────────────────────────────────────────
# Local frame: +Y points toward the road (or along travel for signals), Z up.

def make_lamp():
    g = Geo()
    g.tube((0, 0, 0), (0, 0, 8.4), 0.13, 0.085, 10, METAL_GREY, K["metal"])
    g.tube((0, 0, 0), (0, 0, 0.9), 0.2, 0.18, 10, METAL_DARK, K["metal"], caps=True)
    pts = [(0, 0, 8.3), (0, 0.5, 8.75), (0, 1.3, 8.95), (0, 2.1, 8.95)]
    for a, b in zip(pts, pts[1:]):
        g.tube(a, b, 0.07, 0.06, 8, METAL_GREY, K["metal"])
    m = Matrix.Translation((0.0, 2.35, 8.88))
    g.box(m, (0.42, 0.85, 0.16), METAL_DARK, K["metal"])
    g.quad((-0.17, -0.38, -0.085), (0.17, -0.38, -0.085), (0.17, 0.38, -0.085), (-0.17, 0.38, -0.085),
           (1.0, 0.86, 0.66), K["emissive"], chan(0, 3), face=(0, 0, -1))
    return g


SIGNAL_HEAD = (0.36, 0.28, 1.02)


def make_signal(group):
    """Mast-arm traffic signal for signal `group` (0 avenue, 1 cross street).
    The mast reaches along +Y; lamp faces look toward −X (oncoming traffic)."""
    g = Geo()
    g.tube((0, 0, 0), (0, 0, 6.2), 0.14, 0.11, 10, METAL_DARK, K["metal"])
    g.tube((0, 0, 5.9), (0, 6.5, 5.9), 0.08, 0.06, 8, METAL_DARK, K["metal"])
    for y in (3.2, 6.0):
        m = Matrix.Translation((0.0, y, 5.3))
        g.box(m, SIGNAL_HEAD, METAL_DARK, K["metal"])
        g.box(m, (0.04, 0.5, 1.2), METAL_DARK, K["metal"], center=(0.2, 0.0, 0.0))       # back plate
        for lamp, z in ((0, 0.3), (1, 0.0), (2, -0.3)):
            col = ((1.0, 0.05, 0.02), (1.0, 0.55, 0.0), (0.1, 1.0, 0.45))[lamp]
            pts = [m @ Vector((-0.185, 0.11 * math.cos(TAU * j / 10), z + 0.11 * math.sin(TAU * j / 10)))
                   for j in range(10)]
            g.poly(pts, col, K["emissive"], chan(24 + lamp + 3 * group, 3), face=(-1, 0, 0))
            g.box(m @ Matrix.Translation((-0.24, 0.0, z + 0.1)), (0.14, 0.26, 0.03), METAL_DARK, K["metal"])
    # pedestrian signal on the pole
    m = Matrix.Translation((-0.2, 0.0, 2.8))
    g.box(m, (0.3, 0.3, 0.45), METAL_DARK, K["metal"])
    return g


def make_bench():
    g = Geo()
    m = Matrix.Identity(4)
    for i in range(5):
        g.box(m, (1.8, 0.09, 0.04), WOOD, K["wood"], center=(0.0, -0.2 + i * 0.1, 0.45))
    for i in range(3):
        g.box(m, (1.8, 0.04, 0.09), WOOD, K["wood"], center=(0.0, -0.26, 0.6 + i * 0.12))
    for x in (-0.75, 0.75):
        g.box(m, (0.06, 0.5, 0.45), METAL_DARK, K["metal"], center=(x, 0.0, 0.22))
        g.box(m, (0.06, 0.05, 0.4), METAL_DARK, K["metal"], center=(x, -0.27, 0.65))
    return g


def make_bin():
    g = Geo()
    g.lathe(Matrix.Identity(4), [(0.0, 0.0), (0.27, 0.0), (0.29, 0.9), (0.31, 0.95), (0.2, 1.0), (0.0, 1.02)],
            12, (0.05, 0.12, 0.08), K["metal"])
    return g


def make_hydrant():
    g = Geo()
    g.lathe(Matrix.Identity(4), [(0.0, 0.0), (0.16, 0.0), (0.14, 0.08), (0.12, 0.55), (0.15, 0.6),
                                 (0.12, 0.72), (0.06, 0.8), (0.0, 0.82)], 10, (0.5, 0.04, 0.02), K["metal"])
    g.tube((-0.2, 0, 0.42), (0.2, 0, 0.42), 0.05, 0.05, 8, (0.5, 0.04, 0.02), K["metal"], caps=True)
    return g


def make_bollard():
    g = Geo()
    g.lathe(Matrix.Identity(4), [(0.0, 0.0), (0.09, 0.0), (0.09, 0.85), (0.06, 0.95), (0.0, 0.97)],
            10, METAL_DARK, K["metal"])
    g.lathe(Matrix.Translation((0, 0, 0.7)), [(0.092, 0.0), (0.092, 0.06)], 10, (0.9, 0.7, 0.2), K["paint"])
    return g


def make_vending(rng):
    g = Geo()
    m = Matrix.Identity(4)
    body = rng.choice([(0.55, 0.05, 0.05), (0.05, 0.15, 0.45), (0.55, 0.55, 0.55), (0.06, 0.06, 0.07)])
    g.box(m, (1.0, 0.8, 1.85), body, K["metal"], center=(0.0, 0.0, 0.925))
    g.quad((-0.42, 0.401, 0.75), (0.42, 0.401, 0.75), (0.42, 0.401, 1.75), (-0.42, 0.401, 1.75),
           (0.85, 0.92, 1.0), K["emissive"], chan(0, 2), face=(0, 1, 0))
    for row in range(4):                         # product rows
        for i in range(6):
            c = rng.choice([(1.0, 0.1, 0.1), (0.1, 0.4, 1.0), (1.0, 0.8, 0.1), (0.1, 0.8, 0.3), (1, 1, 1)])
            x, z = -0.36 + i * 0.145, 0.85 + row * 0.24
            g.box(m, (0.07, 0.03, 0.15), c, K["emissive"], chan(0, 1), center=(x, 0.415, z + 0.08))
    g.quad((-0.42, 0.401, 0.15), (0.42, 0.401, 0.15), (0.42, 0.401, 0.45), (-0.42, 0.401, 0.45),
           RUBBER, K["rubber"], face=(0, 1, 0))
    return g


def make_planter(rng):
    g = Geo()
    m = Matrix.Identity(4)
    g.box(m, (1.4, 1.4, 0.55), (0.25, 0.25, 0.24), K["concrete"], center=(0.0, 0.0, 0.275))
    g.tube((0, 0, 0.5), (0.05, 0.03, 3.0), 0.08, 0.05, 7, (0.06, 0.045, 0.035), K["wood"])
    for i in range(7):
        a = TAU * i / 7.0
        r = rng.uniform(0.25, 0.7)
        c = Vector((math.cos(a) * r, math.sin(a) * r, rng.uniform(2.7, 3.7)))
        s = rng.uniform(0.55, 0.85)
        g.lathe(Matrix.Translation(c), [(0.0, -s), (s * 0.8, -s * 0.5), (s, 0.0), (s * 0.7, s * 0.5), (0.0, s * 0.8)],
                7, jitter(rng, FOLIAGE, 0.3), K["foliage"])
    return g


def make_shelter():
    g = Geo()
    m = Matrix.Identity(4)
    w, dep, h = 4.2, 1.6, 2.6
    for x in (-w / 2, w / 2):
        for y in (-dep / 2, dep / 2 - 0.1):
            g.box(m, (0.08, 0.08, h), ALU, K["chrome"], center=(x, y, h / 2))
    g.box(m, (w + 0.3, dep + 0.4, 0.12), METAL_DARK, K["metal"], center=(0.0, 0.0, h + 0.06))
    g.quad((-w / 2, -dep / 2, 0.15), (w / 2, -dep / 2, 0.15), (w / 2, -dep / 2, h - 0.1), (-w / 2, -dep / 2, h - 0.1),
           (0.03, 0.04, 0.045), K["carglass"], face=(0, 1, 0))
    # lit advertising panel at the end
    g.box(m, (0.12, 1.3, 1.9), METAL_DARK, K["metal"], center=(w / 2 + 0.1, 0.0, 1.25))
    g.quad((w / 2 + 0.161, -0.6, 0.35), (w / 2 + 0.161, 0.6, 0.35), (w / 2 + 0.161, 0.6, 2.1), (w / 2 + 0.161, -0.6, 2.1),
           (1.0, 0.55, 0.75), K["emissive"], chan(13, 1), face=(1, 0, 0))
    g.box(m, (w - 0.4, 0.4, 0.06), WOOD, K["wood"], center=(0.0, -dep / 2 + 0.3, 0.48))
    return g


def make_lantern():
    """Paper lantern hanging below the origin (the wire)."""
    g = Geo()
    m = Matrix.Translation((0.0, 0.0, -0.62))
    prof = [(0.0, 0.0), (0.12, 0.0), (0.24, 0.07), (0.29, 0.22), (0.24, 0.37), (0.12, 0.44), (0.0, 0.44)]
    g.lathe(m, prof, 12, (1.0, 0.12, 0.03), K["lantern"], chan(13, 2))
    g.lathe(Matrix.Translation((0.0, 0.0, -0.19)), [(0.0, 0.0), (0.12, 0.0), (0.12, 0.03), (0.0, 0.03)],
            8, (0.5, 0.35, 0.05), K["chrome"])
    g.lathe(Matrix.Translation((0.0, 0.0, -0.66)), [(0.0, 0.0), (0.11, 0.0), (0.11, 0.04), (0.0, 0.04)],
            8, (0.5, 0.35, 0.05), K["chrome"])
    g.tube((0, 0, -0.66), (0, 0, -0.95), 0.025, 0.04, 6, (0.8, 0.5, 0.05), K["fabric"])
    g.tube((0, 0, 0.0), (0, 0, -0.19), 0.006, 0.006, 4, RUBBER, K["rubber"])
    return g


def make_drone(police=False):
    g = Geo()
    m = Matrix.Identity(4)
    s = 1.6 if police else 1.0
    g.box(m, (0.5 * s, 0.7 * s, 0.22 * s), (0.08, 0.08, 0.09) if not police else (0.05, 0.06, 0.12),
          K["carpaint"], center=(0, 0, 0))
    for a in (0.785, 2.356, 3.927, 5.498):
        tip = Vector((math.cos(a) * 0.75 * s, math.sin(a) * 0.75 * s, 0.05 * s))
        g.tube((0, 0, 0), tip, 0.04 * s, 0.03 * s, 6, METAL_DARK, K["metal"])
        g.lathe(Matrix.Translation(tip + Vector((0, 0, 0.06 * s))),
                [(0.0, 0.0), (0.32 * s, 0.0), (0.32 * s, 0.01)], 12, (0.1, 0.1, 0.11), K["rubber"])
        col = (1.0, 0.05, 0.02) if a < 3 else (0.1, 1.0, 0.2)
        g.box(Matrix.Translation(tip), (0.07 * s, 0.07 * s, 0.05 * s), col, K["emissive"],
              chan(31 if police else 30, 3))
    if police:
        g.box(m, (0.28, 0.28, 0.08), (1.0, 0.03, 0.02), K["emissive"], chan(31, 3), center=(-0.25, 0.0, 0.2))
        g.box(m, (0.28, 0.28, 0.08), (0.05, 0.15, 1.0), K["emissive"], chan(31, 3), center=(0.25, 0.0, 0.2))
        g.lathe(Matrix.Translation((0, 0.3, -0.3)) @ Matrix.Rotation(math.pi, 4, 'X'),
                [(0.0, 0.0), (0.22, 0.0), (0.26, 0.3), (0.0, 0.3)], 12, METAL_DARK, K["metal"])
        g.lathe(Matrix.Translation((0, 0.3, -0.61)), [(0.0, 0.0), (0.25, 0.0), (0.0, 0.0)], 12,
                (1.0, 1.0, 1.0), K["headlight"])
    return g


def make_tower(kind):
    """Unit tower (1 × 1 footprint, height 1) for the skyline and the second building row."""
    g = Geo()
    m = Matrix.Identity(4)
    t = K["tower"]
    grey = (0.25, 0.26, 0.28)
    if kind == 0:                                 # slab with a crown
        g.box(m, (1.0, 1.0, 0.94), grey, t, center=(0, 0, 0.47), skip=("-z",))
        g.box(m, (0.86, 0.86, 0.06), (0.12, 0.12, 0.13), K["metal"], center=(0, 0, 0.97), skip=("-z",))
    elif kind == 1:                               # setbacks
        for i, (s, z0, z1) in enumerate(((1.0, 0.0, 0.6), (0.8, 0.6, 0.85), (0.55, 0.85, 1.0))):
            g.box(m, (s, s, z1 - z0), grey, t, center=(0, 0, (z0 + z1) / 2), skip=("-z",))
    elif kind == 2:                               # octagonal with a spire
        rings = []
        for z in (0.0, 0.92):
            rings.append([Vector((0.5 * math.cos(TAU * j / 8 + 0.39), 0.5 * math.sin(TAU * j / 8 + 0.39), z))
                          for j in range(8)])
        for j in range(8):
            j1 = (j + 1) % 8
            g.poly((rings[0][j], rings[0][j1], rings[1][j1], rings[1][j]), grey, t)
        g.poly(rings[1], (0.12, 0.12, 0.13), K["metal"])
        g.tube((0, 0, 0.92), (0, 0, 1.12), 0.04, 0.006, 6, METAL_GREY, K["metal"])
    else:                                         # plain slab
        g.box(m, (1.0, 1.0, 1.0), grey, t, center=(0, 0, 0.5), skip=("-z",))
    top = {0: 1.0, 1: 1.0, 2: 1.12, 3: 1.0}[kind]
    g.box(Matrix.Translation((0, 0, top)), (0.03, 0.03, 0.015), (1.0, 0.03, 0.02), K["emissive"], chan(30, 3))
    return g


# ── Vehicles ─────────────────────────────────────────────────────────────────
# Local frame: +Y forward, +X right, Z up, origin on the ground at the car's centre.
# Body panels are class "carpaint" in white — the app tints each car with its own paint.

CAR_KINDS = ["sedan", "hatch", "suv", "taxi", "van", "bus"]
CAR_SPEC = {   # length, width, height, clearance, belt, hood, deck, wheel radius, wheelbase
    "sedan": (4.65, 1.82, 1.45, 0.16, 0.86, 0.80, 0.92, 0.32, 2.75),
    "hatch": (4.10, 1.76, 1.48, 0.15, 0.88, 0.82, 0.98, 0.30, 2.55),
    "suv": (4.75, 1.92, 1.74, 0.22, 1.02, 1.00, 1.08, 0.36, 2.85),
    "taxi": (4.70, 1.80, 1.48, 0.16, 0.86, 0.80, 0.92, 0.32, 2.75),
    "van": (5.10, 1.95, 2.10, 0.18, 1.05, 1.05, 1.05, 0.34, 3.10),
    "bus": (11.5, 2.50, 3.10, 0.30, 1.10, 1.10, 1.10, 0.48, 6.00),
}
WHITE = (1.0, 1.0, 1.0)
CAR_GLASS = (0.02, 0.025, 0.03)


def _wheel(g, x, y, r, width, outer):
    x0, x1 = (x - width / 2, x + width / 2)
    g.tube((x0, y, r), (x1, y, r), r, r, 14, RUBBER, K["rubber"], caps=True)
    rim_x = x1 + 0.005 if outer > 0 else x0 - 0.005
    rot = Matrix.Rotation(math.pi / 2 * outer, 4, 'Y')
    g.lathe(Matrix.Translation((rim_x, y, r)) @ rot,
            [(0.0, 0.0), (r * 0.62, 0.0), (r * 0.62, 0.005)], 12, (0.55, 0.56, 0.58), K["chrome"])


def make_car(kind):
    L, W, H, clr, belt, hood, deck, wr, wb = CAR_SPEC[kind]
    g = Geo()
    hw = W / 2.0
    if kind in ("van", "bus"):
        m = Matrix.Identity(4)
        nose = 0.9 if kind == "van" else 0.12
        g.box(m, (W, L - nose, H - clr - 0.35), WHITE, K["carpaint"],
              center=(0.0, -nose / 2, clr + (H - clr - 0.35) / 2 + 0.0))
        g.box(m, (W - 0.04, L - nose - 0.1, 0.35), WHITE, K["carpaint"], center=(0.0, -nose / 2, H - 0.175))
        fy = L / 2 - nose
        if kind == "van":                         # sloped hood + windshield
            g.box(m, (W, nose, 0.75), WHITE, K["carpaint"], center=(0.0, fy + nose / 2, clr + 0.45))
            g.quad((-hw, fy + nose * 0.95, clr + 0.82), (hw, fy + nose * 0.95, clr + 0.82),
                   (hw * 0.95, fy, H - 0.12), (-hw * 0.95, fy, H - 0.12), CAR_GLASS, K["carglass"], face=(0, 1, 0.6))
            side_z0, side_z1, wy0, wy1 = 1.15, H - 0.2, fy - 1.4, fy - 0.1
        else:
            g.quad((-hw + 0.06, fy + 0.061, 1.0), (hw - 0.06, fy + 0.061, 1.0), (hw - 0.06, fy + 0.061, H - 0.35),
                   (-hw + 0.06, fy + 0.061, H - 0.35), CAR_GLASS, K["carglass"], face=(0, 1, 0))
            g.quad((-0.8, fy + 0.07, H - 0.32), (0.8, fy + 0.07, H - 0.32), (0.8, fy + 0.07, H - 0.06),
                   (-0.8, fy + 0.07, H - 0.06), (1.0, 0.45, 0.05), K["emissive"], chan(0, 2), face=(0, 1, 0))
            side_z0, side_z1, wy0, wy1 = 1.25, H - 0.4, -L / 2 + 0.6, fy - 1.0
        for s in (-1, 1):                         # side window band
            x = s * (hw + 0.005)
            g.quad((x, wy0, side_z0), (x, wy1, side_z0), (x, wy1, side_z1), (x, wy0, side_z1),
                   (0.05, 0.05, 0.05) if kind == "van" else (0.55, 0.62, 0.70),
                   K["carglass"] if kind == "van" else K["trainwin"], chan(0, 1), face=(s, 0, 0))
        front_y, rear_y = L / 2, -L / 2
        head_z, tail_z = clr + 0.45, clr + 0.55
        axles = (-wb / 2, wb / 2) if kind == "van" else (-wb / 2 - 0.4, wb / 2 - 0.2, -wb / 2 + 1.0)
    else:
        def ztop(y):
            t = (y + L / 2) / L                    # 0 rear … 1 front
            return lerp(deck, hood, smoothstep(0.35, 0.65, t))

        stations = [-L / 2, -L / 2 + 0.07, -L / 2 + 0.3, -L * 0.25, 0.0, L * 0.25, L / 2 - 0.35, L / 2 - 0.07, L / 2]
        rings = []
        for i, y in enumerate(stations):
            end = i in (0, len(stations) - 1)
            near = i in (1, len(stations) - 2)
            w = hw * (0.9 if end else 0.97 if near else 1.0)
            zb = clr + (0.1 if end else 0.03 if near else 0.0)
            zt = ztop(y) - (0.1 if end else 0.03 if near else 0.0)
            zm = (zb + zt) * 0.5
            rings.append([Vector(v) for v in (
                (w * 0.97, y, zb), (w, y, zb + 0.1), (w, y, zm), (w * 0.97, y, zt - 0.05), (w * 0.85, y, zt),
                (0.0, y, zt + 0.02), (-w * 0.85, y, zt), (-w * 0.97, y, zt - 0.05), (-w, y, zm),
                (-w, y, zb + 0.1), (-w * 0.97, y, zb), (0.0, y, zb))])
        g.loft(rings, WHITE, K["carpaint"])
        g.poly(rings[0], WHITE, K["carpaint"], face=(0, -1, 0))
        g.poly(rings[-1], WHITE, K["carpaint"], face=(0, 1, 0))

        # greenhouse: windshield, roof, rear window, side glass with pillars
        bw, rw = hw * 0.9, hw * 0.74
        if kind == "hatch":
            ya, yb, yc, yd = L * 0.12, -L * 0.08, -L * 0.40, -L * 0.48
        elif kind == "suv":
            ya, yb, yc, yd = L * 0.16, -L * 0.02, -L * 0.42, -L * 0.47
        else:
            ya, yb, yc, yd = L * 0.12, -L * 0.04, -L * 0.25, -L * 0.36
        za, zd = ztop(ya), ztop(yd)
        A, B = Vector((-bw, ya, za)), Vector((bw, ya, za))
        Cc, Dd = Vector((rw, yb, H)), Vector((-rw, yb, H))
        E, F = Vector((rw, yc, H)), Vector((-rw, yc, H))
        G2, Hh = Vector((bw, yd, zd)), Vector((-bw, yd, zd))
        g.quad(A, B, Cc, Dd, CAR_GLASS, K["carglass"], face=(0, 1, 1))
        g.quad(Dd, Cc, E, F, WHITE, K["carpaint"], face=(0, 0, 1))
        g.quad(F, E, G2, Hh, CAR_GLASS, K["carglass"], face=(0, -1, 1))
        for s in (-1, 1):
            a, b = Vector((s * bw, ya, za)), Vector((s * bw, yd, zd))
            c, d = Vector((s * rw, yc, H)), Vector((s * rw, yb, H))
            g.poly((a, b, c, d), CAR_GLASS, K["carglass"], face=(s, 0, 0.3))
            # pillars proud of the glass
            out = Vector((s * 0.012, 0.0, 0.0))
            for t in (0.5,):
                p0 = a.lerp(b, t) + out
                p1 = d.lerp(c, t) + out
                g.quad(p0 - Vector((0, 0.06, 0)), p0 + Vector((0, 0.06, 0)), p1 + Vector((0, 0.06, 0)),
                       p1 - Vector((0, 0.06, 0)), WHITE, K["carpaint"], face=(s, 0, 0.3))
            g.quad(a + out, a + out + Vector((0, -0.08, 0)), d + out + Vector((0, -0.08, 0)), d + out,
                   WHITE, K["carpaint"], face=(s, 0, 0.3))
            # side mirror
            g.box(Matrix.Translation((s * (hw + 0.1), ya - 0.15, za + 0.12)), (0.18, 0.1, 0.1), WHITE, K["carpaint"])
        front_y, rear_y = L / 2, -L / 2
        head_z, tail_z = hood - 0.12, deck - 0.1
        axles = (-wb / 2, wb / 2)
        m = Matrix.Identity(4)
        for y, s in ((front_y, 1), (rear_y, -1)):                  # bumpers
            g.box(m, (W * 0.96, 0.14, 0.22), RUBBER, K["rubber"], center=(0.0, y - s * 0.02, clr + 0.18))
        g.quad((-0.45, front_y + 0.004, hood - 0.33), (0.45, front_y + 0.004, hood - 0.33),
               (0.45, front_y + 0.004, hood - 0.17), (-0.45, front_y + 0.004, hood - 0.17),
               RUBBER, K["rubber"], face=(0, 1, 0))                                       # grille
        if kind == "taxi":
            g.box(Matrix.Translation((0.0, (yb + yc) / 2, H + 0.11)), (0.65, 0.3, 0.2), (1.0, 0.78, 0.1),
                  K["emissive"], chan(0, 2))
    # lamps
    for s in (-1, 1):
        x = s * (hw - 0.32 if kind not in ("bus",) else hw - 0.35)
        g.quad((x - 0.17, front_y + 0.012, head_z - 0.06), (x + 0.17, front_y + 0.012, head_z - 0.06),
               (x + 0.17, front_y + 0.012, head_z + 0.06), (x - 0.17, front_y + 0.012, head_z + 0.06),
               (1.0, 0.95, 0.85), K["headlight"], chan(0, 3), face=(0, 1, 0))
        g.quad((x - 0.16, rear_y - 0.012, tail_z - 0.06), (x + 0.16, rear_y - 0.012, tail_z - 0.06),
               (x + 0.16, rear_y - 0.012, tail_z + 0.06), (x - 0.16, rear_y - 0.012, tail_z + 0.06),
               (1.0, 0.02, 0.01), K["taillight"], chan(0, 2), face=(0, -1, 0))
    g.quad((-0.26, rear_y - 0.012, tail_z - 0.25), (0.26, rear_y - 0.012, tail_z - 0.25),
           (0.26, rear_y - 0.012, tail_z - 0.13), (-0.26, rear_y - 0.012, tail_z - 0.13),
           (0.75, 0.73, 0.65), K["paint"], face=(0, -1, 0))                               # plate
    for y in axles:
        for s in (-1, 1):
            _wheel(g, s * (hw - 0.14), y, wr, 0.24 if kind != "bus" else 0.3, s)
    return g


def make_pedestrian(rng, umbrella, skin):
    """Walker facing +Y. Channel marks the parts the app animates:
    1/2 left/right leg (pivot at the hip), 3 left arm (swings), 5 umbrella canopy and
    6 coat (both tinted per person in the app)."""
    g = Geo()
    hip, shoulder = 0.93, 1.43
    for s, ch in ((-1, 1), (1, 2)):
        x = s * 0.1
        g.tube((x, 0.0, hip), (x, 0.0, 0.08), 0.075, 0.055, 6, (0.04, 0.04, 0.05), K["fabric"], ch)
        g.box(Matrix.Translation((x, 0.06, 0.04)), (0.1, 0.26, 0.08), RUBBER, K["rubber"], ch)
    rings = []
    for z, rx, ry in ((0.62, 0.21, 0.15), (hip, 0.2, 0.14), (1.2, 0.2, 0.13), (shoulder, 0.22, 0.12),
                      (1.5, 0.08, 0.07)):
        rings.append([Vector((rx * math.cos(TAU * j / 8), ry * math.sin(TAU * j / 8), z)) for j in range(8)])
    g.loft(rings, (0.3, 0.3, 0.3), K["fabric"], 6)
    g.lathe(Matrix.Translation((0.0, 0.0, 1.5)),
            [(0.0, 0.0), (0.07, 0.01), (0.105, 0.09), (0.1, 0.17), (0.06, 0.23), (0.0, 0.245)],
            8, skin, K["skin"])
    g.lathe(Matrix.Translation((0.0, -0.01, 1.62)), [(0.0, 0.0), (0.108, 0.0), (0.09, 0.09), (0.0, 0.13)],
            8, HAIR, K["fabric"])
    g.tube((-0.24, 0.0, shoulder - 0.02), (-0.26, 0.0, 0.92), 0.05, 0.04, 6, (0.3, 0.3, 0.3), K["fabric"], 3)
    if umbrella:
        g.tube((0.22, 0.0, shoulder - 0.02), (0.18, 0.28, 1.18), 0.05, 0.04, 6, (0.3, 0.3, 0.3), K["fabric"], 6)
        g.tube((0.14, 0.3, 1.1), (0.08, 0.15, 2.05), 0.012, 0.012, 5, METAL_DARK, K["metal"])
        canopy = [(0.0, 0.32), (0.18, 0.28), (0.36, 0.17), (0.5, 0.03), (0.52, 0.0)]
        prof = [(r, z) for r, z in reversed(canopy)]
        g.lathe(Matrix.Translation((0.08, 0.15, 1.73)), prof, 8, WHITE, K["fabric"], 5)
    else:
        g.tube((0.24, 0.0, shoulder - 0.02), (0.26, 0.0, 0.92), 0.05, 0.04, 6, (0.3, 0.3, 0.3), K["fabric"], 4)
        g.lathe(Matrix.Translation((0.0, -0.02, 1.55)), [(0.12, 0.0), (0.13, 0.12), (0.1, 0.2), (0.0, 0.24)],
                8, (0.3, 0.3, 0.3), K["fabric"], 6)                     # raised hood
    return g


# ── Train ────────────────────────────────────────────────────────────────────
# Local frame: +Y along the track (forward), origin at rail-top level.

TRAIN_CAR_L = 19.5


def make_train_car(head):
    g = Geo()
    L, W, z0, z1 = TRAIN_CAR_L, 3.0, 0.95, 4.1
    m = Matrix.Identity(4)
    silver, stripe = (0.42, 0.44, 0.46), (0.02, 0.22, 0.55)
    g.box(m, (W, L, 1.05), silver, K["chrome"], center=(0.0, 0.0, z0 + 0.525))
    g.box(m, (W + 0.02, L, 0.18), stripe, K["paint"], center=(0.0, 0.0, z0 + 0.95))
    rings = []
    for y in (-L / 2, L / 2):
        rings.append([Vector(v) for v in ((W / 2, y, z1 - 0.95), (W / 2, y, z1 - 0.2), (W * 0.32, y, z1),
                                          (-W * 0.32, y, z1), (-W / 2, y, z1 - 0.2), (-W / 2, y, z1 - 0.95))])
    g.loft(rings, silver, K["chrome"], closed=False)
    win_z0, win_z1 = z0 + 1.15, z1 - 0.95
    for s in (-1, 1):
        x = s * W / 2
        g.quad((x, -L / 2, win_z0 - 0.1), (x, L / 2, win_z0 - 0.1), (x, L / 2, win_z0), (x, -L / 2, win_z0),
               silver, K["chrome"], face=(s, 0, 0))
        g.quad((x, -L / 2, win_z0), (x, L / 2, win_z0), (x, L / 2, win_z1), (x, -L / 2, win_z1),
               (1.0, 0.92, 0.80), K["trainwin"], chan(0, 2), face=(s, 0, 0))
        for i in range(9):                          # window pillars and doors
            y = -L / 2 + L * (i + 0.5) / 9
            door = i in (2, 6)
            g.box(Matrix.Translation((x + s * 0.02, y, (win_z0 + win_z1) / 2)),
                  (0.04, 0.9 if door else 0.25, win_z1 - win_z0 + (1.3 if door else 0.0)),
                  silver if not door else (0.3, 0.32, 0.34), K["chrome"])
    for y in (-L / 2 + 3.0, L / 2 - 3.0):           # bogies
        g.box(m, (2.4, 2.6, 0.55), METAL_DARK, K["metal"], center=(0.0, y, 0.45))
        for dy in (-0.9, 0.9):
            for s in (-1, 1):
                g.tube((s * 0.72, y + dy, 0.42), (s * 0.82, y + dy, 0.42), 0.42, 0.42, 10, METAL_GREY, K["metal"], caps=True)
    if head:
        f = L / 2
        g.quad((-W / 2 + 0.2, f + 0.01, z0 + 1.15), (W / 2 - 0.2, f + 0.01, z0 + 1.15),
               (W / 2 - 0.25, f + 0.01, z1 - 0.35), (-W / 2 + 0.25, f + 0.01, z1 - 0.35), CAR_GLASS, K["carglass"],
               face=(0, 1, 0))
        for s in (-1, 1):
            g.quad((s * 1.0 - 0.18, f + 0.012, z0 + 0.45), (s * 1.0 + 0.18, f + 0.012, z0 + 0.45),
                   (s * 1.0 + 0.18, f + 0.012, z0 + 0.62), (s * 1.0 - 0.18, f + 0.012, z0 + 0.62),
                   (1.0, 0.95, 0.85), K["headlight"], chan(0, 3), face=(0, 1, 0))
        dest, _ = text_geo("EXPRESS", 'solid')
        # text X → −X, up → Z, front → +Y: readable from in front of the train
        g.append(dest, Matrix.Translation((0.0, f + 0.03, z1 - 0.2)) @ Matrix.Rotation(math.pi, 4, 'Z')
                 @ Matrix.Rotation(math.pi / 2, 4, 'X') @ Matrix.Scale(0.22, 4),
                 k=K["emissive"], col=[1.0, 0.45, 0.04], ch=chan(0, 2))
    return g


# ── World: everything a build produces ───────────────────────────────────────

LIGHT_COLS = 13   # x y z radius · r g b (intensity included) · type (0 point, 1 spot) · dir xyz · cos(outer) · group


class World:
    def __init__(self, rng):
        self.rng = rng
        self.chunks = {}       # name → Geo (unique, merged geometry)
        self.protos = {}       # name → Geo (instanced or app-animated meshes)
        self.inst = {}         # prototype → [(x, y, z, rx, ry, rz, sx, sy, sz)]
        self.lights = []
        self.tables = {}       # name → rows of floats (camera, lanes, cars, peds, …)

    def chunk(self, name):
        return self.chunks.setdefault(name, Geo())

    def place(self, proto, pos, rz=0.0, scale=(1.0, 1.0, 1.0), rx=0.0, ry=0.0):
        self.inst.setdefault(proto, []).append((pos[0], pos[1], pos[2], rx, ry, rz, scale[0], scale[1], scale[2]))

    def light(self, pos, color, intensity, radius, spot=None, cos_outer=0.0, group=0):
        d = spot if spot is not None else (0.0, 0.0, 0.0)
        self.lights.append((pos[0], pos[1], pos[2], radius, color[0] * intensity, color[1] * intensity,
                            color[2] * intensity, 1.0 if spot is not None else 0.0, d[0], d[1], d[2],
                            cos_outer, float(group)))

    def table(self, name, row):
        self.tables.setdefault(name, []).append(tuple(float(v) for v in row))


# ── Buildings ────────────────────────────────────────────────────────────────

STYLES = {
    "office": dict(bay=(3.3, 3.7), win=("office",), wall=WALL_CONCRETE, k="concrete", floors=(7, 22), pier=True),
    "resi": dict(bay=(2.3, 2.6), win=("res", "res_grille", "res_ac"), wall=WALL_TILE, k="tile", floors=(10, 27),
                 pier=False),
    "brick": dict(bay=(2.2, 2.5), win=("old",), wall=WALL_BRICK, k="brick", floors=(4, 8), pier=False),
    "glass": dict(bay=(1.5, 1.5), win=("curtain",), wall=[(0.10, 0.11, 0.12)], k="metal", floors=(18, 40),
                  pier=False),
    "shophouse": dict(bay=(2.7, 3.1), win=("shop_upper",), wall=WALL_CONCRETE + WALL_TILE, k="concrete",
                      floors=(2, 5), pier=True),
}
STYLE_WEIGHTS = (("office", 0.22), ("resi", 0.30), ("brick", 0.15), ("glass", 0.11), ("shophouse", 0.22))
WIN_DIMS = {"res": (1.5, 1.5, 0.9), "res_grille": (1.5, 1.5, 0.9), "res_ac": (1.5, 1.5, 0.9),
            "office": (2.8, 2.0, 0.75), "old": (1.1, 2.0, 0.6), "shop_upper": (2.4, 1.8, 0.85)}
UP = Vector((0.0, 0.0, 1.0))


def pick(rng, weighted):
    r = rng.random() * sum(w for _, w in weighted)
    for item, w in weighted:
        r -= w
        if r <= 0.0:
            return item
    return weighted[-1][0]


def text_matrix(B, x, y, z, height, flip=False):
    """Letters `height` tall at facade-local (x, y, z) of frame B, readable from outside.
    Frame X is the viewer's left, so text runs along −X (or +X when `flip`)."""
    R, O, U = B.col[0].xyz, B.col[1].xyz, B.col[2].xyz
    if flip:
        R = -R
    o = B @ Vector((x, y, z))
    h = height
    return Matrix(((-R.x * h, U.x * h, O.x * h, o.x),
                   (-R.y * h, U.y * h, O.y * h, o.y),
                   (-R.z * h, U.z * h, O.z * h, o.z),
                   (0.0, 0.0, 0.0, 1.0)))


def neon_group(rng):
    r = rng.random()
    if r < 0.14:
        return rng.randint(1, 8)           # faulty, flickering tube
    if r < 0.22:
        return rng.randint(9, 12)          # chasing letters
    if r < 0.32:
        return rng.randint(13, 16)         # slow pulse
    return 0


def place_text(g, word, mode, m, color, group, tier, k=None):
    geo, _ = text_geo(word, mode)
    g.append(geo, m, k=K["emissive"] if k is None else k, col=list(color), ch=chan(group, tier))


def fit_height(word, mode, avail_w, h_max):
    _, wu = text_geo(word, mode)
    return min(h_max, avail_w / max(wu, 0.1))


def shop_sign(w, g, rng, B, a, b):
    """Fascia sign above a shopfront spanning facade-local x ∈ [a, b]."""
    word = rng.choice(SIGN_WORDS)
    cx, span = (a + b) / 2.0, b - a - 0.3
    colname = rng.choice(NEON_NAMES)
    color = NEON[colname]
    group = neon_group(rng)
    O = B.col[1].xyz
    if rng.random() < 0.5:                                         # neon tubes on a dark raceway
        # mounted on the fascia band (its face is at y = 0.12): a raceway flush with it z-fought and blinked
        g.box(B, (span, 0.12, 1.0), METAL_DARK, K["metal"], center=(cx, 0.18, 4.0))
        h = fit_height(word, 'tube', span * 0.92, 0.72)
        place_text(g, word, 'tube', text_matrix(B, cx, 0.32, 4.0, h), color, group, 2)
        w.light(B @ Vector((cx, 1.2, 3.9)), color, 1.6, 7.5, group=group)
    else:                                                          # lightbox with dark letters
        face = rng.choice([(0.95, 0.92, 0.85), color, (1.0, 0.85, 0.3), (0.9, 0.95, 1.0)])
        g.box(B, (span, 0.3, 1.05), METAL_DARK, K["metal"], center=(cx, 0.15, 4.0), skip=("+y",))
        g.quad(B @ Vector((cx - span / 2, 0.3, 3.475)), B @ Vector((cx + span / 2, 0.3, 3.475)),
               B @ Vector((cx + span / 2, 0.3, 4.525)), B @ Vector((cx - span / 2, 0.3, 4.525)),
               face, K["emissive"], chan(0, 1), face=O)
        h = fit_height(word, 'solid', span * 0.85, 0.62)
        ink = rng.choice([(0.02, 0.02, 0.025), (0.5, 0.02, 0.02), (0.02, 0.05, 0.3)])
        place_text(g, word, 'solid', text_matrix(B, cx, 0.32, 4.0, h), ink, 0, 0, k=K["paint"])
        w.light(B @ Vector((cx, 1.3, 3.9)), face, 1.2, 7.0)


def blade_sign(w, g, rng, B, x, z0, max_h):
    """Vertical sign sticking out of the facade, letters stacked on both faces."""
    word = rng.choice([t for t in BLADE_WORDS if len(t) * 1.0 + 0.6 <= max_h] or ["BAR"])
    letter = 0.82
    height = len(word) * 1.0 + 0.6
    y0, y1 = 0.35, 1.55
    cy = (y0 + y1) / 2.0
    color = NEON[rng.choice(NEON_NAMES)]
    trim = NEON[rng.choice(NEON_NAMES)]
    group = neon_group(rng)
    neon = rng.random() < 0.55
    body = METAL_DARK if neon else jitter(rng, (0.9, 0.88, 0.82), 0.1)
    g.box(B, (0.36, y1 - y0, height), body, K["metal"] if neon else K["emissive"],
          0 if neon else chan(0, 1), center=(x, cy, z0 + height / 2.0))
    for yb in (0.0, y0):
        g.box(B, (0.08, 0.08, 0.08), METAL_GREY, K["metal"], center=(x, yb + 0.15, z0 + 0.4))
        g.box(B, (0.08, 0.08, 0.08), METAL_GREY, K["metal"], center=(x, yb + 0.15, z0 + height - 0.4))
    _bar(g, B @ Vector((x, 0.0, z0 + 0.4)), B @ Vector((x, y0, z0 + 0.4)), 0.05, METAL_GREY, K["metal"])
    _bar(g, B @ Vector((x, 0.0, z0 + height - 0.4)), B @ Vector((x, y0, z0 + height - 0.4)), 0.05, METAL_GREY,
         K["metal"])
    R, O, U = B.col[0].xyz, B.col[1].xyz, B.col[2].xyz
    for side in (1.0, -1.0):
        n = R * side
        xs = x + side * 0.19
        for i, ch_ in enumerate(word):
            zc = z0 + height - 0.8 - i * 1.0
            o = B @ Vector((xs, cy, zc))
            ox = O * side                                       # text runs outward on the +R face
            m = Matrix(((ox.x * letter, U.x * letter, n.x * letter, o.x),
                        (ox.y * letter, U.y * letter, n.y * letter, o.y),
                        (ox.z * letter, U.z * letter, n.z * letter, o.z),
                        (0.0, 0.0, 0.0, 1.0)))
            if neon:
                place_text(g, ch_, 'tube', m, color, group, 2)
            else:
                place_text(g, ch_, 'solid', m, (0.5, 0.02, 0.02), 0, 0, k=K["paint"])
        if neon:                                                # trim tube around the face
            for (ya, za), (yb, zb) in (((y0 + 0.08, z0 + 0.1), (y1 - 0.08, z0 + 0.1)),
                                       ((y1 - 0.08, z0 + 0.1), (y1 - 0.08, z0 + height - 0.1)),
                                       ((y1 - 0.08, z0 + height - 0.1), (y0 + 0.08, z0 + height - 0.1))):
                g.tube(B @ Vector((xs, ya, za)), B @ Vector((xs, yb, zb)), 0.025, 0.025, 6, trim, K["emissive"],
                       chan(group, 2))
    w.light(B @ Vector((x, 1.9, z0 + height / 2.0)), color if neon else (1.0, 0.85, 0.7), 2.2, 9.0, group=group)


def awning(g, rng, B, a, b):
    O = B.col[1].xyz
    col = rng.choice([(0.35, 0.03, 0.03), (0.04, 0.15, 0.08), (0.05, 0.06, 0.18), (0.32, 0.2, 0.04),
                      (0.18, 0.18, 0.18), (0.3, 0.06, 0.12)])
    dep = rng.uniform(1.2, 1.9)
    za, zb = 3.32, 2.75
    p = lambda x, y, z: B @ Vector((x, y, z))
    g.quad(p(a, 0.0, za), p(b, 0.0, za), p(b, dep, zb), p(a, dep, zb), col, K["fabric"], face=O + UP)
    g.quad(p(a, dep, zb), p(b, dep, zb), p(b, dep, zb - 0.28), p(a, dep, zb - 0.28), col, K["fabric"], face=O)
    for x in (a, b):
        g.poly((p(x, 0.0, za), p(x, dep, zb), p(x, dep, zb - 0.28)), col, K["fabric"])


def shopfronts(w, g, rng, B, width, wall_col, wall_k):
    O, R = B.col[1].xyz, B.col[0].xyz
    p = lambda x, y, z: B @ Vector((x, y, z))
    n = int(clamp(width // rng.uniform(5.0, 8.5), 1, 4))
    pier = 0.45
    span = (width - pier * (n + 1)) / n
    xs = []
    x = pier
    for _ in range(n):
        xs.append((x, x + span))
        x += span + pier
    # piers (slightly proud) and the fascia band
    for i in range(n + 1):
        a = 0.0 if i == 0 else xs[i - 1][1]
        b = xs[i][0] if i < n else width
        g.box(B, (b - a, 0.12, 3.35), wall_col, wall_k, center=((a + b) / 2, 0.06, 1.675), skip=("-y", "-z"))
    g.quad(p(0, 0.12, 3.35), p(width, 0.12, 3.35), p(width, 0.12, GROUND_H), p(0, 0.12, GROUND_H),
           wall_col, wall_k, face=O)
    g.quad(p(0, 0.0, GROUND_H), p(width, 0.0, GROUND_H), p(width, 0.12, GROUND_H), p(0, 0.12, GROUND_H),
           wall_col, wall_k, face=UP)
    for a, b in xs:
        d = 0.55
        g.quad(p(a, 0.0, 0.0), p(a, -d, 0.0), p(a, -d, 3.35), p(a, 0.0, 3.35), REVEAL, K["concrete"], face=R)
        g.quad(p(b, 0.0, 0.0), p(b, -d, 0.0), p(b, -d, 3.35), p(b, 0.0, 3.35), REVEAL, K["concrete"], face=-R)
        g.quad(p(a, 0.0, 3.35), p(b, 0.0, 3.35), p(b, -d, 3.35), p(a, -d, 3.35), REVEAL, K["concrete"], face=-UP)
        g.quad(p(a, 0.0, 0.02), p(b, 0.0, 0.02), p(b, -d, 0.02), p(a, -d, 0.02), PAVING, K["paving"], face=UP)
        if rng.random() < 0.82:                                    # open, lit shop
            light_col = rng.choice(SHOP_LIGHT)
            g.quad(p(a, -d, 0.0), p(b, -d, 0.0), p(b, -d, 3.35), p(a, -d, 3.35), light_col, K["shopglass"],
                   face=O)
            mid = (a + b) / 2
            for xm in (a + 0.04, b - 0.04, mid - 0.55, mid + 0.55):
                g.box(B, (0.07, 0.07, 3.35), METAL_DARK, K["metal"], center=(xm, -d + 0.04, 1.675))
            g.box(B, (b - a, 0.07, 0.07), METAL_DARK, K["metal"], center=(mid, -d + 0.04, 2.5))
            g.box(B, (b - a, 0.07, 0.3), METAL_DARK, K["metal"], center=(mid, -d + 0.04, 0.15))
            w.light(p(mid, 1.4, 2.7), light_col, 3.2, 7.5)
            if rng.random() < 0.45:
                awning(g, rng, B, a + 0.05, b - 0.05)
        else:                                                      # closed: roller shutter
            g.quad(p(a, -0.15, 0.0), p(b, -0.15, 0.0), p(b, -0.15, 3.35), p(a, -0.15, 3.35),
                   (0.22, 0.23, 0.24), K["metal"], face=O)
            for k in range(26):
                z = 0.12 + k * 0.125
                g.box(B, (b - a, 0.025, 0.03), (0.3, 0.31, 0.32), K["metal"], center=((a + b) / 2, -0.13, z))
        shop_sign(w, g, rng, B, a, b)


def punched_facade(w, g, rng, B, width, floors, style, wall_col, wall_k, top):
    spec = STYLES[style]
    O, R = B.col[1].xyz, B.col[0].xyz
    p = lambda x, y, z: B @ Vector((x, y, z))
    yaw = yaw_of(O)
    kinds = spec["win"]
    ow, oh, sill = WIN_DIMS[kinds[0]]
    edge = 0.55
    usable = width - 2 * edge
    nb = max(1, int(usable // rng.uniform(*spec["bay"])))
    bay = usable / nb
    if ow > bay - 0.35:
        ow = bay - 0.35
    xs = [edge + bay * (i + 0.5) for i in range(nb)]
    rows = [GROUND_H + f * FLOOR_H + sill for f in range(floors)]
    # horizontal wall bands between window rows
    bands = [(GROUND_H, rows[0])] + [(rows[f] + oh, rows[f + 1]) for f in range(floors - 1)] + [(rows[-1] + oh, top)]
    for z0, z1 in bands:
        if z1 > z0 + 1e-3:
            g.quad(p(0, 0, z0), p(width, 0, z0), p(width, 0, z1), p(0, 0, z1), wall_col, wall_k, face=O)
    # piers between openings
    gaps = [(0.0, xs[0] - ow / 2)] + [(xs[i] + ow / 2, xs[i + 1] - ow / 2) for i in range(nb - 1)] + \
           [(xs[-1] + ow / 2, width)]
    for z in rows:
        for a, b in gaps:
            if b > a + 1e-3:
                g.quad(p(a, 0, z), p(b, 0, z), p(b, 0, z + oh), p(a, 0, z + oh), wall_col, wall_k, face=O)
    if spec["pier"]:                                               # proud full-height columns
        for a, b in gaps[1:-1]:
            g.box(B, (b - a, 0.3, top - GROUND_H), jitter(rng, wall_col, 0.06), wall_k,
                  center=((a + b) / 2, 0.0, (GROUND_H + top) / 2), skip=("-y", "-z"))
    # windows (instanced modules)
    col_kind = [rng.choice(kinds) for _ in xs]
    balcony_cols = set(i for i in range(nb) if style == "resi" and rng.random() < 0.28)
    fire_col = nb // 2 if style == "brick" and nb >= 3 else None
    for f, z in enumerate(rows):
        for i, x in enumerate(xs):
            kind = col_kind[i] if style != "resi" else (col_kind[i] if rng.random() < 0.7 else rng.choice(kinds))
            w.place("win_" + kind, p(x, 0, z), yaw)
            if i in balcony_cols and f > 0:
                w.place("balcony_laundry" if rng.random() < 0.35 else "balcony", p(x, 0, z - sill), yaw)
            elif style in ("resi", "brick", "shophouse") and rng.random() < 0.16 and kind != "res_ac":
                ax = x + (ow / 2 + 0.55) * (1 if rng.random() < 0.5 else -1)
                if 0.6 < ax < width - 0.6:
                    w.place("ac_unit", p(ax, 0, z - 0.75), yaw)
        if fire_col is not None and f < floors - 1:
            w.place("fire_escape", p(xs[fire_col], 0, rows[f] - sill + 0.05), yaw)
    # cornice
    g.box(B, (width + 0.3, 0.45, 0.35), jitter(rng, wall_col, 0.1), wall_k, center=(width / 2, 0.1, top - 0.2))


def curtain_facade(w, g, rng, B, width, floors, top):
    O = B.col[1].xyz
    p = lambda x, y, z: B @ Vector((x, y, z))
    yaw = yaw_of(O)
    edge = 0.35
    cols = max(2, round((width - 2 * edge) / 1.5))
    pw = (width - 2 * edge) / cols
    sx = pw / 1.5
    for f in range(floors):
        for i in range(cols):
            w.place("win_curtain", p(edge + pw * (i + 0.5), 0.0, GROUND_H + f * FLOOR_H), yaw, (sx, 1.0, 1.0))
    trim = (0.13, 0.14, 0.15)
    for a, b in ((0.0, edge), (width - edge, width)):
        g.box(B, (b - a, 0.3, top - GROUND_H), trim, K["metal"], center=((a + b) / 2, 0.0, (GROUND_H + top) / 2),
              skip=("-y",))
    zt = GROUND_H + floors * FLOOR_H
    g.quad(p(0, 0, zt), p(width, 0, zt), p(width, 0, top), p(0, 0, top), trim, K["metal"], face=O)


def facade(w, g, rng, B, width, floors, style, wall_col, wall_k, top, blade=True):
    shopfronts(w, g, rng, B, width, wall_col, wall_k)
    if style == "glass":
        curtain_facade(w, g, rng, B, width, floors, top)
    else:
        punched_facade(w, g, rng, B, width, floors, style, wall_col, wall_k, top)
    if blade and style in ("resi", "brick", "shophouse", "office") and rng.random() < 0.55 and width > 6:
        x = rng.choice([0.75, width - 0.75])
        max_h = min(top - GROUND_H - 1.0, 10.5)
        if max_h > 3.5:
            blade_sign(w, g, rng, B, x, GROUND_H + 0.5, max_h)
    if style in ("office", "resi", "glass") and top > 35 and rng.random() < 0.28:
        word = rng.choice(["HOTEL", "KARAOKE", "PEARL", "LOTUS", "NOVA", "CASINO", "PLAZA", "ZENITH", "TOWER"])
        h = fit_height(word, 'tube', width * 0.8, 3.2)
        z = top - 2.4
        place_text(g, word, 'tube', text_matrix(B, width / 2, 0.35, z, h), NEON[rng.choice(NEON_NAMES)],
                   neon_group(rng), 3)
        w.light(B @ Vector((width / 2, 4.0, z)), NEON["white"], 4.0, 16.0)


def rooftop(w, g, rng, B, width, depth, top, style):
    p = lambda x, y, z: B @ Vector((x, y, z))
    O = B.col[1].xyz
    yaw = yaw_of(O)
    if style != "glass" and rng.random() < 0.5:                    # water tank on legs
        x, y = rng.uniform(2.5, width - 2.5) if width > 5 else width / 2, -rng.uniform(4.0, depth - 4.0)
        base = p(x, y, top)
        for dx, dy in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            g.tube(base + Vector((dx * 1.2, dy * 1.2, 0)), base + Vector((dx * 1.2, dy * 1.2, 3.0)), 0.1, 0.1, 6,
                   METAL_DARK, K["metal"])
        g.lathe(Matrix.Translation(base + Vector((0, 0, 3.0))),
                [(0.0, 0.0), (1.8, 0.0), (1.8, 3.6), (1.6, 4.1), (0.0, 4.4)], 14, (0.18, 0.12, 0.08), K["wood"])
    for _ in range(rng.randint(1, 4)):                             # condensers
        x, y = rng.uniform(1.0, max(1.1, width - 1.0)), -rng.uniform(2.0, depth - 2.0)
        g.box(Matrix.Translation(p(x, y, top)) @ Matrix.Rotation(yaw, 4, 'Z'),
              (rng.uniform(1.5, 3.0), rng.uniform(1.0, 2.0), rng.uniform(0.8, 1.6)), (0.42, 0.42, 0.4), K["metal"],
              center=(0, 0, 0.6))
    if rng.random() < 0.35:
        base = p(rng.uniform(1, max(1.1, width - 1)), -rng.uniform(2, depth - 2), top)
        g.tube(base, base + Vector((0, 0, rng.uniform(5, 12))), 0.08, 0.03, 6, METAL_GREY, K["metal"])
    if top > 45.0:
        g.box(Matrix.Translation(p(width / 2, -depth / 2, top + 0.5)), (0.25, 0.25, 0.25), (1.0, 0.03, 0.02),
              K["emissive"], chan(30, 3))


def billboard(w, g, rng, B, width, depth, top):
    """Big LED screen on a roof frame, turned to face down the avenue."""
    O = B.col[1].xyz
    bw, bh = rng.uniform(12.0, 18.0), rng.uniform(6.0, 9.0)
    c = B @ Vector((width / 2, -depth * 0.4, top))
    face = Vector((O.x * 0.55, -0.85 if c.y > 0 else 0.85, 0.0)).normalized()
    if abs(O.y) > 0.5:                                             # cross-street roofs face the avenue
        face = Vector((-1.0 if c.x > 0 else 1.0, O.y * 0.3, 0.0)).normalized()
    right = face.cross(UP)
    F = frame(c, right, face, UP)
    for x in (-bw * 0.35, bw * 0.35):
        g.box(F, (0.35, 0.35, 4.0 + bh), METAL_DARK, K["metal"], center=(x, -0.6, (4.0 + bh) / 2))
    g.box(F, (bw + 0.5, 0.5, bh + 0.5), METAL_DARK, K["metal"], center=(0.0, -0.25, 4.0 + bh / 2))
    group = rng.randint(13, 16)
    pts = [F @ Vector(v) for v in ((-bw / 2, 0.02, 4.0), (bw / 2, 0.02, 4.0), (bw / 2, 0.02, 4.0 + bh),
                                   (-bw / 2, 0.02, 4.0 + bh))]
    # colour = (u, v, content seed); local X is the viewer's left, so u runs along −X
    seed, base = rng.random(), g.nv
    for q, (u, v) in zip(pts, ((1.0, 0.0), (0.0, 0.0), (0.0, 1.0), (1.0, 1.0))):
        g.vert(q, face, (u, v, seed), K["screen"], chan(group, 3))
    g.idx.extend((base, base + 2, base + 1, base, base + 3, base + 2))
    for x in (-bw / 3, 0.0, bw / 3):
        w.light(F @ Vector((x, 6.0, 4.0 + bh / 2)), (0.9, 0.5, 1.0), 9.0, 30.0, group=group)
    w.table("screens", (*c, *face, bw, bh))


def build_building(w, g, rng, origin, R, O, width, depth, style, floors, left_h, right_h, corner_side=None):
    """One building on a lot. origin = front corner on the building line; the lot runs
    along R for `width` and back into the block (−O) for `depth`. corner_side ∈ {None,
    'left', 'right'}: that side also faces a street and gets a full facade."""
    B = frame(origin, R, O, UP)
    p = lambda x, y, z: B @ Vector((x, y, z))
    spec = STYLES[style]
    wall_col = jitter(rng, rng.choice(spec["wall"]), 0.12)
    wall_k = K[spec["k"]]
    top = GROUND_H + floors * FLOOR_H + 0.9
    facade(w, g, rng, B, width, floors, style, wall_col, wall_k, top)
    # roof slab + parapet cap
    g.quad(p(0, 0, top), p(width, 0, top), p(width, -depth, top), p(0, -depth, top), (0.1, 0.1, 0.1),
           K["concrete"], face=UP)
    g.box(B, (width, 0.3, 1.0), wall_col, wall_k, center=(width / 2, -0.15, top + 0.5))
    # sides: full facade on a street corner, otherwise blank wall above the neighbour
    for side, x, nh, n in (("left", 0.0, left_h, -R), ("right", width, right_h, R)):
        if corner_side == side:
            if side == "right":
                SB = frame(p(width, 0, 0), -O, R, UP)
            else:
                SB = frame(p(0, -depth, 0), O, -R, UP)
            facade(w, g, rng, SB, depth, floors, style, wall_col, wall_k, top, blade=False)
        elif nh < top + 1.0:
            z0 = max(nh, 0.0)
            g.quad(p(x, 0, z0), p(x, -depth, z0), p(x, -depth, top + 1.0), p(x, 0, top + 1.0),
                   wall_col, K["tower"], face=n)
    g.quad(p(0, -depth, 0), p(width, -depth, 0), p(width, -depth, top), p(0, -depth, top), wall_col, K["tower"],
           face=-O)
    rooftop(w, g, rng, B, width, depth, top, style)
    if rng.random() < 0.12 and top < 70:
        billboard(w, g, rng, B, width, depth, top + 1.0)
    return top + 1.0


def frontage(w, g, rng, start, T, O, length, corner_start=False, corner_end=False, tall=1.0):
    """Lots along a building line from `start` running along T; buildings face O."""
    R = O.cross(UP)                                   # building-frame X (viewer's left)
    lots, u = [], 0.0
    while u < length - 1.0:
        lw = rng.uniform(9.0, 24.0)
        if length - (u + lw) < 8.0:
            lw = length - u
        lots.append((u, u + lw))
        u += lw
    plans = []
    for i, (u0, u1) in enumerate(lots):
        style = pick(rng, STYLE_WEIGHTS)
        lo, hi = STYLES[style]["floors"]
        floors = max(2, int(rng.randint(lo, hi) * tall))
        is_corner = (i == 0 and corner_start) or (i == len(lots) - 1 and corner_end)
        depth = CORNER_DEPTH if is_corner else rng.uniform(19.0, 30.0)
        plans.append((u0, u1, style, floors, depth, is_corner))
    heights = [GROUND_H + f * FLOOR_H + 1.9 for _, _, _, f, _, _ in plans]
    same = T.dot(R) > 0.0
    for i, (u0, u1, style, floors, depth, is_corner) in enumerate(plans):
        prev_h = heights[i - 1] if i > 0 else 0.0
        next_h = heights[i + 1] if i + 1 < len(plans) else 0.0
        if same:                                      # frame X runs with the frontage
            origin, left_h, right_h = start + T * u0, prev_h, next_h
            corner = ("left" if i == 0 else "right") if is_corner else None
        else:
            origin, left_h, right_h = start + T * u1, next_h, prev_h
            corner = ("right" if i == 0 else "left") if is_corner else None
        build_building(w, g, rng, origin, R, O, u1 - u0, depth, style, floors, left_h, right_h, corner)


def build_city_blocks(w, rng):
    """Front-row buildings along the avenue and the cross street."""
    for s in (-1, 1):
        O = Vector((-s, 0.0, 0.0))
        T = Vector((0.0, 1.0, 0.0))
        segs = ((STREET_Y0, CROSS_Y - FRONT, False, True), (CROSS_Y + FRONT, RAIL_Y - RAIL_GAP, True, False),
                (RAIL_Y + RAIL_GAP, STREET_Y1, False, False))
        for n, (y0, y1, c0, c1) in enumerate(segs):
            g = w.chunk("bld_av%s_%d" % ("L" if s < 0 else "R", n))
            frontage(w, g, rng, Vector((s * FRONT, y0, 0.0)), T, O, y1 - y0, c0, c1)
    for sy in (-1, 1):                                # cross street, both sides of the avenue
        O = Vector((0.0, -sy, 0.0))
        for sx in (-1, 1):
            g = w.chunk("bld_x%s%s" % ("S" if sy < 0 else "N", "W" if sx < 0 else "E"))
            x0 = sx * (FRONT + CORNER_DEPTH)
            T = Vector((float(sx), 0.0, 0.0))
            frontage(w, g, rng, Vector((x0, CROSS_Y + sy * FRONT, 0.0)), T, O, CROSS_X1 - FRONT - CORNER_DEPTH,
                     tall=0.8)


# ── Streets ──────────────────────────────────────────────────────────────────

ROAD_Y0, ROAD_Y1 = -300.0, 1000.0


def _rect(g, x0, y0, x1, y1, z, col, k):
    g.quad((x0, y0, z), (x1, y0, z), (x1, y1, z), (x0, y1, z), col, k, face=UP)


def _strip(g, x0, y0, x1, y1, step, z, col, k):
    """Rectangle split along Y so long roads keep vertex lighting and fog precise."""
    y = y0
    while y < y1 - 1e-6:
        yn = min(y + step, y1)
        _rect(g, x0, y, x1, yn, z, col, k)
        y = yn


def build_streets(w, rng):
    g = w.chunk("street")
    cy0, cy1 = CROSS_Y - ROAD_HALF, CROSS_Y + ROAD_HALF
    # avenue asphalt (the intersection square belongs to it)
    _strip(g, -ROAD_HALF, ROAD_Y0, ROAD_HALF, ROAD_Y1, 20.0, 0.0, ASPHALT, K["asphalt"])
    for sx in (-1, 1):                                            # cross street asphalt
        x0, x1 = sorted((sx * ROAD_HALF, sx * CROSS_X1))
        y = cy0
        _rect(g, x0, cy0, x1, cy1, 0.0, ASPHALT, K["asphalt"])
    # sidewalks with curbs
    walks = []
    for s in (-1, 1):
        xa, xb = sorted((s * ROAD_HALF, s * FRONT))
        walks += [(xa, ROAD_Y0, xb, cy0), (xa, cy1, xb, ROAD_Y1)]
        for sy in (-1, 1):
            ya, yb = sorted((CROSS_Y + sy * ROAD_HALF, CROSS_Y + sy * FRONT))
            xa2, xb2 = sorted((s * FRONT, s * CROSS_X1))
            walks.append((xa2, ya, xb2, yb))
            xa3, xb3 = sorted((s * ROAD_HALF, s * FRONT))
            walks.append((xa3, ya, xb3, yb))
    for x0, y0, x1, y1 in walks:
        _strip(g, x0, y0, x1, y1, 20.0, CURB_H, PAVING, K["paving"])
    # curb faces + curb stones along the road edges
    for s in (-1, 1):
        x = s * ROAD_HALF
        for y0, y1 in ((ROAD_Y0, cy0), (cy1, ROAD_Y1)):
            g.quad((x, y0, 0.0), (x, y1, 0.0), (x, y1, CURB_H), (x, y0, CURB_H), CURB, K["concrete"],
                   face=(-s, 0, 0))
            xa, xb = sorted((x, x + s * 0.22))
            _strip(g, xa, y0, xb, y1, 20.0, CURB_H + 0.004, CURB, K["concrete"])
        for sy in (-1, 1):
            y = CROSS_Y + sy * ROAD_HALF
            xa, xb = sorted((s * ROAD_HALF, s * CROSS_X1))
            g.quad((xa, y, 0.0), (xb, y, 0.0), (xb, y, CURB_H), (xa, y, CURB_H), CURB, K["concrete"],
                   face=(0, -sy, 0))
    # markings
    def mark(x0, y0, x1, y1, col=PAINT_WHITE):
        _rect(g, x0, y0, x1, y1, 0.006, col, K["paint"])
    for y0, y1 in ((ROAD_Y0, cy0 - 13.0), (cy1 + 13.0, ROAD_Y1)):
        for xo in (-0.17, 0.05):
            _strip(g, xo, y0, xo + 0.12, y1, 40.0, 0.006, PAINT_YELLOW, K["paint"])
        for s in (-1, 1):
            xe = s * (ROAD_HALF - 0.35)
            _strip(g, min(xe, xe + 0.13), y0, max(xe, xe + 0.13), y1, 40.0, 0.006, PAINT_WHITE, K["paint"])
            y = y0
            while y < y1 - 3.0:
                mark(s * LANE_W - 0.06, y, s * LANE_W + 0.06, y + 3.0)
                y += 9.0
    for sy in (-1, 1):                                            # zebra crossings + stop lines
        yc = CROSS_Y + sy * (ROAD_HALF + 2.3)
        x = -ROAD_HALF + 0.6
        while x < ROAD_HALF - 0.6:
            mark(x, yc - 1.8, x + 0.55, yc + 1.8)
            x += 1.1
        ys = CROSS_Y + sy * (ROAD_HALF + 5.0)
        xa, xb = sorted((0.0, -sy * ROAD_HALF))
        mark(xa, min(ys, ys + 0.45 * sy), xb, max(ys, ys + 0.45 * sy))
    for sx in (-1, 1):
        xc = sx * (ROAD_HALF + 2.3)
        y = cy0 + 0.6
        while y < cy1 - 0.6:
            mark(xc - 1.8, y, xc + 1.8, y + 0.55)
            y += 1.1
    # manholes and storm drains
    for y in (40.0, 155.0, 320.0, 505.0, 880.0, -150.0):
        x = rng.uniform(-3.0, 3.0)
        pts = [(x + 0.42 * math.cos(TAU * j / 12), y + 0.42 * math.sin(TAU * j / 12), 0.008) for j in range(12)]
        g.poly(pts, (0.06, 0.06, 0.065), K["metal"], face=UP)
        w.table("steam", (x, y, 0.0))
    y = ROAD_Y0 + 10.0
    while y < ROAD_Y1:
        for s in (-1, 1):
            if abs(y - CROSS_Y) > 14.0:
                g.box(Matrix.Translation((s * (ROAD_HALF - 0.35), y, 0.0)), (0.45, 0.9, 0.02), METAL_DARK, K["metal"])
        y += 45.0


def build_viaduct(w, rng):
    g = w.chunk("viaduct")
    x0, x1 = -520.0, 520.0
    yc = RAIL_Y
    conc = (0.24, 0.235, 0.22)
    m = Matrix.Identity(4)
    g.box(m, (x1 - x0, 10.0, 1.3), conc, K["concrete"], center=(0.0, yc, RAIL_Z - 0.75))
    for s in (-1, 1):
        g.box(m, (x1 - x0, 0.3, 1.25), conc, K["concrete"], center=(0.0, yc + s * 4.85, RAIL_Z + 0.5))
    for t in (-1, 1):
        ty = yc + t * 2.0
        g.box(m, (x1 - x0, 2.8, 0.25), (0.06, 0.055, 0.05), K["concrete"], center=(0.0, ty, RAIL_Z - 0.0))
        for r in (-0.72, 0.72):
            g.box(m, (x1 - x0, 0.08, 0.16), (0.36, 0.36, 0.37), K["chrome"], center=(0.0, ty + r, RAIL_Z + 0.2))
    x = -506.0
    while x < 520.0:
        if abs(x) > FRONT + 2.0:
            for s in (-1, 1):
                g.box(m, (1.4, 1.4, RAIL_Z - 1.4), conc, K["concrete"], center=(x, yc + s * 3.0, (RAIL_Z - 1.4) / 2))
            g.box(m, (1.8, 9.0, 1.0), conc, K["concrete"], center=(x, yc, RAIL_Z - 1.9))
        x += 30.0
    x = -500.0
    while x < 520.0:                                              # catenary masts and wires
        for s in (-1, 1):
            g.tube((x, yc + s * 4.6, RAIL_Z + 1.0), (x, yc + s * 4.6, RAIL_Z + 6.4), 0.12, 0.1, 6, METAL_GREY,
                   K["metal"])
        _bar(g, (x, yc - 4.6, RAIL_Z + 6.2), (x, yc + 4.6, RAIL_Z + 6.2), 0.12, METAL_GREY, K["metal"])
        x += 40.0
    for t in (-1, 1):
        _bar(g, (x0, yc + t * 2.0, RAIL_Z + 5.4), (x1, yc + t * 2.0, RAIL_Z + 5.4), 0.025, RUBBER, K["rubber"])
    # under-deck lights over the avenue
    for xx in (-6.0, 0.0, 6.0):
        g.box(m, (1.2, 0.3, 0.06), (1.0, 0.92, 0.8), K["emissive"], chan(0, 2), center=(xx, yc, RAIL_Z - 2.43))
        w.light((xx, yc, RAIL_Z - 2.6), (1.0, 0.9, 0.75), 5.0, 14.0, spot=(0.0, 0.0, -1.0), cos_outer=0.35)


def catenary_points(a, b, sag, n):
    out = []
    for i in range(n + 1):
        t = i / n
        p = a.lerp(b, t)
        p.z -= sag * 4.0 * t * (1.0 - t)
        out.append(p)
    return out


def build_overhead(w, rng):
    g = w.chunk("wires")
    y = LANTERN_Y[0]
    while y <= LANTERN_Y[1]:
        pts = catenary_points(Vector((-FRONT, y, 8.2)), Vector((FRONT, y + 1.0, 8.4)), 0.9, 12)
        for a, b in zip(pts, pts[1:]):
            g.tube(a, b, 0.012, 0.012, 4, RUBBER, K["rubber"])
        for i in range(1, 16):
            t = i / 16.0
            p = catenary_points(Vector((-FRONT, y, 8.2)), Vector((FRONT, y + 1.0, 8.4)), 0.9, 16)[i]
            w.place("lantern", p, rng.uniform(0, TAU))
        w.light((0.0, y + 0.5, 7.2), (1.0, 0.18, 0.05), 5.0, 12.0, group=13)
        for s in (-1, 1):
            w.light((s * 7.0, y + 0.5, 7.4), (1.0, 0.2, 0.05), 3.0, 9.0, group=14)
        y += 7.0
    for y in (40.0, 330.0, 560.0, 800.0):                         # plain cables across the street
        pts = catenary_points(Vector((-FRONT, y, 9.5)), Vector((FRONT, y - 2.0, 10.2)), 0.6, 10)
        for a, b in zip(pts, pts[1:]):
            g.tube(a, b, 0.015, 0.015, 4, RUBBER, K["rubber"])


def build_street_furniture(w, rng):
    lamp_cols = [(1.0, 0.62, 0.30), (0.95, 0.92, 0.88)]
    y = ROAD_Y0 + 8.0
    k = 0
    while y < ROAD_Y1:
        for s in (-1, 1):
            if abs(y - CROSS_Y) < 16.0 or abs(y - RAIL_Y) < 7.0:
                continue
            yy = y + (12.0 if s > 0 else 0.0)
            x = s * (ROAD_HALF + 0.55)
            w.place("lamp", (x, yy, CURB_H), yaw_of((-s, 0, 0)))
            col = lamp_cols[(k // 3) % 2]
            w.light((s * (ROAD_HALF + 0.55 - 2.35), yy, 8.8), col, 6.0, 17.0, spot=(0.0, 0.0, -1.0), cos_outer=0.42)
        y += 24.0
        k += 1
    for sx in (-1, 1):                                            # cross street lamps
        x = sx * (FRONT + 14.0)
        while abs(x) < CROSS_X1:
            for sy in (-1, 1):
                yy = CROSS_Y + sy * (ROAD_HALF + 0.55)
                w.place("lamp", (x, yy, CURB_H), yaw_of((0, -sy, 0)))
                w.light((x, CROSS_Y + sy * (ROAD_HALF + 0.55 - 2.35), 8.8), lamp_cols[0], 6.0, 17.0,
                        spot=(0.0, 0.0, -1.0), cos_outer=0.42)
            x += sx * 26.0
    # traffic signals (see make_signal for the orientation)
    for proto, pos, rz in (("signal_av", (ROAD_HALF + 1.2, CROSS_Y - FRONT - 1.5), math.pi / 2),
                           ("signal_av", (-ROAD_HALF - 1.2, CROSS_Y + FRONT + 1.5), -math.pi / 2),
                           ("signal_x", (-FRONT - 1.5, CROSS_Y - ROAD_HALF - 1.2), 0.0),
                           ("signal_x", (FRONT + 1.5, CROSS_Y + ROAD_HALF + 1.2), math.pi)):
        w.place(proto, (pos[0], pos[1], CURB_H), rz)
        grp = 0 if proto == "signal_av" else 1
        c, s_ = math.cos(rz), math.sin(rz)
        for my in (3.2, 6.0):
            hx, hy = pos[0] - s_ * my, pos[1] + c * my
            for lamp, z, col in ((0, 0.3, (1.0, 0.05, 0.02)), (2, -0.3, (0.1, 1.0, 0.45))):
                w.light((hx - c * 0.4, hy - s_ * 0.4, CURB_H + 5.3 + z), col, 1.6, 6.0, group=24 + lamp + 3 * grp)
    # sidewalk furniture
    def free(y):
        return abs(y - CROSS_Y) > 18.0 and abs(y - RAIL_Y) > 10.0
    for s in (-1, 1):
        y = STREET_Y0 + rng.uniform(0, 10)
        while y < STREET_Y1:
            if free(y):
                r = rng.random()
                curb_x = s * (ROAD_HALF + 1.0)
                wall_x = s * (FRONT - 0.6)
                if r < 0.18:
                    w.place("bench", (s * (ROAD_HALF + 1.6), y, CURB_H), yaw_of((s, 0, 0)))
                elif r < 0.34:
                    w.place("bin", (curb_x, y, CURB_H))
                elif r < 0.44:
                    w.place("hydrant", (curb_x, y, CURB_H), rng.uniform(0, TAU))
                elif r < 0.60:
                    for i in range(3):
                        w.place("bollard", (curb_x, y + i * 1.4, CURB_H))
                elif r < 0.72:
                    w.place("vending_%d" % rng.randint(0, 2), (wall_x, y, CURB_H), yaw_of((-s, 0, 0)))
                    w.light((s * (FRONT - 2.0), y, 1.4), (0.85, 0.92, 1.0), 1.4, 5.0)
                elif r < 0.86:
                    w.place("planter_%d" % rng.randint(0, 1), (s * (ROAD_HALF + 1.9), y, CURB_H), rng.uniform(0, TAU))
            y += rng.uniform(7.0, 15.0)
    for y in (60.0, 365.0):                                        # bus shelters
        w.place("shelter", (ROAD_HALF + 2.6, y, CURB_H), yaw_of((-1, 0, 0)))
        w.light((ROAD_HALF + 2.6, y + 2.4, 1.5), (1.0, 0.6, 0.8), 1.8, 6.0, group=13)


def build_skyline(w, rng):
    """Second building row behind the frontage and towers out to the horizon (procedural windows)."""
    for s in (-1, 1):                                              # second row
        for _ in range(MIDROW_PER_SIDE):
            for _attempt in range(20):
                y = rng.uniform(STREET_Y0 - 150, STREET_Y1 + 300)
                x = s * rng.uniform(52.0, 110.0)
                if abs(y - RAIL_Y) < 14.0 or abs(y - CROSS_Y) < 50.0:
                    continue
                break
            wd, dp = rng.uniform(22, 38), rng.uniform(22, 38)
            h = rng.uniform(38.0, 125.0)
            w.place("tower_%d" % rng.choice((0, 0, 1, 3)), (x, y, 0.0), rng.choice((0.0, math.pi / 2)), (wd, dp, h))
    for s in (-1, 1):                                              # avenue beyond the detailed blocks
        for y0, y1 in ((ROAD_Y0, STREET_Y0), (STREET_Y1, ROAD_Y1)):
            y = y0
            while y < y1:
                wd = rng.uniform(14.0, 30.0)
                h = rng.uniform(20.0, 90.0)
                w.place("tower_3", (s * (FRONT + 14.0), y + wd / 2, 0.0), 0.0, (26.0, wd - 0.5, h))
                y += wd
    for _ in range(SKYLINE_TOWERS):                                # skyline
        for _attempt in range(30):
            a = rng.uniform(0, TAU)
            r = rng.uniform(260.0, 1700.0)
            x, y = math.cos(a) * r, 300.0 + math.sin(a) * r
            if abs(x) < 130.0 and -320.0 < y < 1000.0:
                continue
            break
        wd = rng.uniform(25, 60)
        h = rng.uniform(70.0, 330.0) * (1.3 if r > 900 else 1.0)
        w.place("tower_%d" % rng.randint(0, 3), (x, y, 0.0), rng.uniform(0, TAU), (wd, wd * rng.uniform(0.7, 1.3), h))
    for x, y, wd, h in ((-60.0, 1180.0, 55.0, 420.0), (90.0, 1320.0, 48.0, 360.0), (-180.0, 1500.0, 60.0, 300.0)):
        w.place("tower_2", (x, y, 0.0), 0.3, (wd, wd, h))           # landmark towers at the avenue's end


# ── Moving actors: traffic, pedestrians, trains, drones, camera ──────────────

LANE_COLS = 8   # start x, start y, dir x, dir y, length, desired speed, stop-line s, signal group
CAR_COLS = 7    # lane, s, kind, r, g, b, desired-speed factor
SIGNAL_PERIOD = 30.0
SIGNAL_OFFSET = 4.0


def signal_state(group, t):
    """0 green, 1 yellow, 2 red for signal group 0 (avenue) or 1 (cross street)."""
    u = (t + SIGNAL_OFFSET) % SIGNAL_PERIOD
    if group == 0:
        return 0 if u < 14.0 else 1 if u < 17.0 else 2
    return 2 if u < 18.0 else 0 if u < 27.0 else 1 if u < 29.5 else 2


def build_traffic(w, rng):
    lanes = []
    ay0, ay1 = ROAD_Y0 + 40.0, ROAD_Y1 - 60.0
    for x, d in ((LANE_W / 2, 1), (LANE_W * 1.5, 1), (-LANE_W / 2, -1), (-LANE_W * 1.5, -1)):
        start_y = ay0 if d > 0 else ay1
        stop_y = CROSS_Y - d * (ROAD_HALF + 5.2)
        lanes.append((x, start_y, 0.0, float(d), ay1 - ay0, rng.uniform(11.5, 14.0), abs(stop_y - start_y), 0))
    for y, d in ((CROSS_Y - LANE_W / 2, 1), (CROSS_Y - LANE_W * 1.5, 1), (CROSS_Y + LANE_W / 2, -1),
                 (CROSS_Y + LANE_W * 1.5, -1)):
        start_x = -CROSS_X1 - 40.0 if d > 0 else CROSS_X1 + 40.0
        stop_x = -d * (ROAD_HALF + 5.2)
        lanes.append((start_x, y, float(d), 0.0, 2 * CROSS_X1 + 80.0, rng.uniform(10.0, 12.5),
                      abs(stop_x - start_x), 1))
    for ln in lanes:
        w.table("lanes", ln)
    paints = [(0.02, 0.02, 0.022), (0.02, 0.02, 0.022), (0.75, 0.75, 0.74), (0.75, 0.75, 0.74), (0.45, 0.46, 0.48),
              (0.2, 0.21, 0.22), (0.35, 0.02, 0.02), (0.02, 0.05, 0.2), (0.1, 0.12, 0.13), (0.55, 0.36, 0.1),
              (0.03, 0.12, 0.08)]
    kinds = (("sedan", 0.34), ("hatch", 0.2), ("suv", 0.2), ("taxi", 0.13), ("van", 0.08), ("bus", 0.05))
    for li, ln in enumerate(lanes):
        n = CARS_PER_AVENUE_LANE if ln[7] == 0 else CARS_PER_CROSS_LANE
        length = ln[4]
        spacing = length / n
        for i in range(n):
            kind = pick(rng, kinds)
            if kind == "bus" and li % 4 not in (1, 3):
                kind = "van"
            col = (0.85, 0.62, 0.03) if kind == "taxi" else (0.9, 0.9, 0.88) if kind == "bus" else rng.choice(paints)
            s = (i + rng.uniform(-0.25, 0.25)) * spacing % length
            w.table("cars", (li, s, CAR_KINDS.index(kind), *col, rng.uniform(0.9, 1.08)))


def car_length(kind_index):
    return CAR_SPEC[CAR_KINDS[kind_index]][0]


def simulate_traffic(lanes, cars, t_end, dt=SIM_DT, record=None):
    """Car-following (IDM) model with stop lines at red/yellow signals. Deterministic; the app
    runs the same model. `record(t, states)` is called every step when given."""
    A, Bd, T_HW, S0 = 1.6, 3.0, 1.3, 2.4
    state = [[c[1], lanes[int(c[0])][5] * c[6] * 0.9, 0.0] for c in cars]     # s, v, accel
    by_lane = {}
    for i, c in enumerate(cars):
        by_lane.setdefault(int(c[0]), []).append(i)
    t = -SIM_WARMUP
    while t < t_end - 1e-9:
        acc = [0.0] * len(cars)
        for li, idx in by_lane.items():
            lane = lanes[li]
            length, v0l, stop_s, grp = lane[4], lane[5], lane[6], int(lane[7])
            order = sorted(idx, key=lambda i: state[i][0])
            sig = signal_state(grp, t)
            for n, i in enumerate(order):
                s, v, _ = state[i]
                v0 = v0l * cars[i][6]
                lead = order[(n + 1) % len(order)]
                gap = (state[lead][0] - s) % length - car_length(int(cars[lead][2])) / 2 - car_length(int(cars[i][2])) / 2
                dv = v - state[lead][1]
                if sig != 0:                                       # stop line ahead?
                    to_line = stop_s - (s + car_length(int(cars[i][2])) / 2)
                    if 0.0 < to_line < 90.0 and (sig == 2 or to_line > v * v / (2 * Bd)):
                        if to_line < gap:
                            gap, dv = to_line, v
                gap = max(gap, 0.1)
                s_star = S0 + max(0.0, v * T_HW + v * dv / (2 * math.sqrt(A * Bd)))
                a = A * (1.0 - (v / v0) ** 4 - (s_star / gap) ** 2)
                acc[i] = max(a, -9.0)
        for i, st in enumerate(state):
            length = lanes[int(cars[i][0])][4]
            v = max(0.0, st[1] + acc[i] * dt)
            st[0] = (st[0] + v * dt) % length
            st[1] = v
            st[2] = acc[i]
        t += dt
        if record is not None and t >= 0.0:
            record(t, state)
    return state


def build_pedestrians(w, rng):
    """Walkers on both avenue sidewalks; a few people standing under awnings and at the stop."""
    segments = []
    for s in (-1, 1):
        for y0, y1 in ((STREET_Y0, CROSS_Y - FRONT), (CROSS_Y + FRONT, STREET_Y1)):
            segments.append((s, y0, y1))
    for i in range(PED_COUNT):
        s, y0, y1 = segments[i % len(segments)]
        lane = rng.uniform(1.2, 4.6)
        x = s * (ROAD_HALF + lane)
        d = 1.0 if rng.random() < 0.5 else -1.0
        speed = 0.0 if rng.random() < 0.14 else rng.uniform(1.05, 1.55)
        umb = NEON[rng.choice(NEON_NAMES)] if rng.random() < 0.15 else rng.choice(
            [(0.02, 0.02, 0.025), (0.02, 0.02, 0.025), (0.5, 0.05, 0.05), (0.05, 0.1, 0.35), (0.6, 0.6, 0.6),
             (0.35, 0.28, 0.05), (0.06, 0.2, 0.1)])
        variant = rng.randint(0, 3)
        phase = rng.uniform(0.0, y1 - y0)
        # start x, start y, dir y, segment length, speed, phase, variant, umbrella rgb, coat shade
        w.table("peds", (x, y0, d, y1 - y0, speed, phase, variant, *umb, rng.uniform(0.15, 0.6)))


TRAIN_SPEED = 21.0


def build_trains(w):
    # track y offset, direction, time its head crosses x = 0
    for row in ((-2.0, 1.0, 46.0), (2.0, -1.0, 19.0)):
        w.table("trains", (RAIL_Y + row[0], row[1], row[2], TRAIN_SPEED, TRAIN_CARS))


def build_drones(w):
    # centre x, y, z, radius, angular speed, phase, police flag
    for row in ((-30.0, 120.0, 34.0, 45.0, 0.12, 0.0, 0), (25.0, 330.0, 42.0, 60.0, -0.09, 2.0, 0),
                (0.0, 560.0, 38.0, 35.0, 0.15, 4.0, 0), (0.0, 0.0, 26.0, 0.0, 0.0, 0.0, 1)):
        w.table("drones", row)


CAMERA_KEYS = [  # t, eye xyz, look-at xyz, vertical fov (deg) — low shots stay over the centre line
    (0.0, (0.3, -70.0, 4.3), (-2.0, 40.0, 5.0), 52.0),
    (8.0, (-0.4, 2.0, 4.8), (-9.0, 95.0, 7.0), 52.0),
    (16.0, (0.5, 80.0, 4.4), (10.0, 165.0, 5.5), 54.0),
    (24.0, (0.2, 160.0, 4.5), (-3.0, 255.0, 6.5), 56.0),
    (30.0, (-0.6, 232.0, 5.6), (-24.0, 270.0, 4.0), 58.0),
    (36.0, (0.6, 296.0, 6.6), (4.0, 400.0, 12.0), 54.0),
    (44.0, (0.3, 384.0, 6.4), (-3.0, 440.0, 10.5), 58.0),
    (50.0, (0.0, 452.0, 8.6), (2.0, 530.0, 15.0), 54.0),
    (55.0, (0.0, 498.0, 20.0), (0.0, 640.0, 28.0), 52.0),
    (60.0, (0.0, 545.0, 34.0), (0.0, 780.0, 52.0), 50.0),
]


def build_camera_keys(w):
    for t, eye, tgt, fov in CAMERA_KEYS:
        w.table("camera", (t, *eye, *tgt, fov))


def catmull(keys, t, idx):
    """Centripetal-free uniform Catmull-Rom over key tuples at component `idx` (a 3-tuple)."""
    n = len(keys)
    t = clamp(t, keys[0][0], keys[-1][0])
    i = 0
    while i < n - 2 and keys[i + 1][0] < t:
        i += 1
    t0, t1 = keys[i][0], keys[i + 1][0]
    u = (t - t0) / (t1 - t0)
    p0 = Vector(keys[max(i - 1, 0)][idx]); p1 = Vector(keys[i][idx])
    p2 = Vector(keys[i + 1][idx]); p3 = Vector(keys[min(i + 2, n - 1)][idx])
    u2, u3 = u * u, u * u * u
    return 0.5 * ((2 * p1) + (-p0 + p2) * u + (2 * p0 - 5 * p1 + 4 * p2 - p3) * u2 + (-p0 + 3 * p1 - 3 * p2 + p3) * u3)


def build_prototypes(w, rng):
    P = w.protos
    for kind in ("res", "res_grille", "res_ac", "office", "old", "shop_upper", "curtain"):
        P["win_" + kind] = make_window(kind, rng)
    P["balcony"] = make_balcony(rng, False)
    P["balcony_laundry"] = make_balcony(rng, True)
    P["fire_escape"] = make_fire_escape()
    P["ac_unit"] = make_ac_unit()
    P["lamp"] = make_lamp()
    P["signal_av"] = make_signal(0)
    P["signal_x"] = make_signal(1)
    P["bench"] = make_bench()
    P["bin"] = make_bin()
    P["hydrant"] = make_hydrant()
    P["bollard"] = make_bollard()
    for i in range(3):
        P["vending_%d" % i] = make_vending(rng)
    for i in range(2):
        P["planter_%d" % i] = make_planter(rng)
    P["shelter"] = make_shelter()
    P["lantern"] = make_lantern()
    for i in range(4):
        P["tower_%d" % i] = make_tower(i)
    # actors — animated by the app (and keyframed in Blender)
    for kind in CAR_KINDS:
        P["car_" + kind] = make_car(kind)
    for i in range(4):
        P["ped_%d" % i] = make_pedestrian(rng, i < 3, SKIN_TONES[i])
    P["train_head"] = make_train_car(True)
    P["train_car"] = make_train_car(False)
    P["drone"] = make_drone(False)
    P["drone_police"] = make_drone(True)


ACTORS = {"car_" + k for k in CAR_KINDS} | {"ped_%d" % i for i in range(4)} | \
         {"train_head", "train_car", "drone", "drone_police"}


# ── Blender scene ────────────────────────────────────────────────────────────

def reset_scene():
    scene = bpy.context.scene
    scene.name = "NeonCity"
    for ob in list(scene.objects):
        bpy.data.objects.remove(ob, do_unlink=True)
    for coll in list(scene.collection.children):
        scene.collection.children.unlink(coll)
    for blocks in (bpy.data.objects, bpy.data.collections, bpy.data.meshes, bpy.data.materials,
                   bpy.data.node_groups, bpy.data.lights, bpy.data.cameras, bpy.data.worlds, bpy.data.actions,
                   bpy.data.curves):
        for block in list(blocks):
            if block.name.startswith(("NC_", "LV_")) or block.users == 0:
                blocks.remove(block)
    return scene


def new_collection(name, parent):
    coll = bpy.data.collections.new(name)
    parent.children.link(coll)
    return coll


def _nodes(mat):
    try:
        mat.use_nodes = True
    except (AttributeError, TypeError):
        pass
    nt = mat.node_tree
    for n in list(nt.nodes):
        nt.nodes.remove(n)
    return nt, nt.nodes, nt.links


def _math(N, L, op, a, b=None, value=None):
    n = N.new('ShaderNodeMath')
    n.operation = op
    for i, src in enumerate((a, b)):
        if src is None:
            continue
        if isinstance(src, (int, float)):
            n.inputs[i].default_value = src
        else:
            L.new(src, n.inputs[i])
    return n.outputs[0]


def class_material(name):
    """One material per vertex class. Base colour = vertex colour; alpha carries the channel."""
    m = bpy.data.materials.new("NC_" + name)
    nt, N, L = _nodes(m)
    out = N.new('ShaderNodeOutputMaterial')
    bsdf = N.new('ShaderNodeBsdfPrincipled')
    L.new(bsdf.outputs[0], out.inputs['Surface'])
    col = N.new('ShaderNodeVertexColor')
    col.layer_name = "Col"
    obj = N.new('ShaderNodeObjectInfo')
    geo = N.new('ShaderNodeNewGeometry')

    def tier_strength(base):
        tier = _math(N, L, 'FLOOR', _math(N, L, 'MULTIPLY', col.outputs['Alpha'], 127.0 / 32.0))
        return _math(N, L, 'MULTIPLY', _math(N, L, 'POWER', 2.5, tier), base)

    rough = {"concrete": 0.6, "brick": 0.65, "tile": 0.25, "metal": 0.35, "asphalt": 0.18, "paving": 0.3,
             "paint": 0.25, "carpaint": 0.15, "carglass": 0.02, "rubber": 0.6, "chrome": 0.12, "fabric": 0.8,
             "skin": 0.5, "foliage": 0.6, "wood": 0.5, "tower": 0.3}.get(name, 0.4)
    bsdf.inputs['Roughness'].default_value = rough
    if name in ("emissive", "lantern", "headlight", "taillight", "trainwin", "screen", "shopglass"):
        L.new(col.outputs['Color'], bsdf.inputs['Emission Color'])
        base = {"emissive": 6.0, "lantern": 4.0, "headlight": 10.0, "taillight": 5.0, "trainwin": 0.9,
                "screen": 5.0, "shopglass": 1.4}[name]
        if name == "shopglass":                                     # ceiling-lit interiors, shelves
            sep = N.new('ShaderNodeSeparateXYZ')
            L.new(geo.outputs['Position'], sep.inputs[0])
            shelves = N.new('ShaderNodeTexWave')
            shelves.wave_type = 'BANDS'
            shelves.inputs['Scale'].default_value = 1.6
            shelves.inputs['Distortion'].default_value = 2.0
            L.new(geo.outputs['Position'], shelves.inputs['Vector'])
            grad = _math(N, L, 'MULTIPLY', _math(N, L, 'ADD', _math(N, L, 'MULTIPLY', sep.outputs['Z'], 0.28), 0.25),
                         _math(N, L, 'ADD', _math(N, L, 'MULTIPLY', shelves.outputs['Fac'], 0.6), 0.55))
            tint = N.new('ShaderNodeMix')
            tint.data_type = 'RGBA'
            tint.blend_type = 'MULTIPLY'
            tint.inputs['Factor'].default_value = 1.0
            L.new(col.outputs['Color'], tint.inputs['A'])
            L.new(shelves.outputs['Color'], tint.inputs['B'])
            L.new(tint.outputs['Result'], bsdf.inputs['Emission Color'])
            base = _math(N, L, 'MULTIPLY', grad, base)
        if name == "screen":                                        # animated-looking ad content
            wave = N.new('ShaderNodeTexWave')
            wave.inputs['Scale'].default_value = 0.15
            wave.inputs['Distortion'].default_value = 6.0
            L.new(geo.outputs['Position'], wave.inputs['Vector'])
            ramp = N.new('ShaderNodeValToRGB')
            ramp.color_ramp.elements[0].color = (1.0, 0.05, 0.5, 1.0)
            ramp.color_ramp.elements[1].color = (0.05, 0.6, 1.0, 1.0)
            L.new(wave.outputs['Fac'], ramp.inputs['Fac'])
            L.new(ramp.outputs['Color'], bsdf.inputs['Emission Color'])
        L.new(tier_strength(base), bsdf.inputs['Emission Strength'])
        bsdf.inputs['Base Color'].default_value = (0.02, 0.02, 0.02, 1.0)
    elif name == "glass":                                          # windows: lit rooms at random
        bsdf.inputs['Base Color'].default_value = (0.01, 0.012, 0.015, 1.0)
        bsdf.inputs['Roughness'].default_value = 0.04
        lit = _math(N, L, 'GREATER_THAN', obj.outputs['Random'], 0.52)
        hue = N.new('ShaderNodeValToRGB')
        hue.color_ramp.elements[0].color = (1.0, 0.62, 0.3, 1.0)
        hue.color_ramp.elements[1].color = (0.8, 0.92, 1.0, 1.0)
        L.new(_math(N, L, 'FRACT', _math(N, L, 'MULTIPLY', obj.outputs['Random'], 7.13)), hue.inputs['Fac'])
        L.new(hue.outputs['Color'], bsdf.inputs['Emission Color'])
        L.new(_math(N, L, 'MULTIPLY', lit, 2.2), bsdf.inputs['Emission Strength'])
    elif name == "tower":                                          # procedural lit windows by world position
        sep = N.new('ShaderNodeSeparateXYZ')
        L.new(geo.outputs['Position'], sep.inputs[0])
        u = _math(N, L, 'DIVIDE', _math(N, L, 'ADD', sep.outputs['X'], sep.outputs['Y']), 1.7)
        v = _math(N, L, 'DIVIDE', sep.outputs['Z'], 3.5)
        mask = _math(N, L, 'MULTIPLY',
                     _math(N, L, 'LESS_THAN', _math(N, L, 'ABSOLUTE', _math(N, L, 'SUBTRACT', _math(N, L, 'FRACT', u), 0.5)), 0.32),
                     _math(N, L, 'LESS_THAN', _math(N, L, 'ABSOLUTE', _math(N, L, 'SUBTRACT', _math(N, L, 'FRACT', v), 0.55)), 0.28))
        cell = N.new('ShaderNodeCombineXYZ')
        L.new(_math(N, L, 'FLOOR', u), cell.inputs['X'])
        L.new(_math(N, L, 'FLOOR', v), cell.inputs['Y'])
        wn = N.new('ShaderNodeTexWhiteNoise')
        L.new(cell.outputs[0], wn.inputs['Vector'])
        lit = _math(N, L, 'MULTIPLY', mask, _math(N, L, 'GREATER_THAN', wn.outputs['Value'], 0.6))
        bsdf.inputs['Emission Color'].default_value = (1.0, 0.75, 0.45, 1.0)
        L.new(_math(N, L, 'MULTIPLY', lit, 2.5), bsdf.inputs['Emission Strength'])
        L.new(col.outputs['Color'], bsdf.inputs['Base Color'])
    elif name in ("carpaint", "fabric"):                            # per-object tint
        mix = N.new('ShaderNodeMix')
        mix.data_type = 'RGBA'
        mix.blend_type = 'MULTIPLY'
        mix.inputs['Factor'].default_value = 1.0
        L.new(col.outputs['Color'], mix.inputs['A'])
        L.new(obj.outputs['Color'], mix.inputs['B'])
        L.new(mix.outputs['Result'], bsdf.inputs['Base Color'])
        if name == "carpaint" and 'Coat Weight' in bsdf.inputs:
            bsdf.inputs['Coat Weight'].default_value = 1.0
    elif name in ("asphalt", "paving"):                            # wet with puddles
        tex = N.new('ShaderNodeTexNoise')
        tex.inputs['Scale'].default_value = 0.35 if name == "asphalt" else 0.6
        tex.inputs['Detail'].default_value = 4.0
        L.new(geo.outputs['Position'], tex.inputs['Vector'])
        puddle = _math(N, L, 'GREATER_THAN', tex.outputs['Fac'], 0.56)
        L.new(_math(N, L, 'SUBTRACT', rough, _math(N, L, 'MULTIPLY', puddle, rough - 0.02)), bsdf.inputs['Roughness'])
        L.new(col.outputs['Color'], bsdf.inputs['Base Color'])
    else:
        L.new(col.outputs['Color'], bsdf.inputs['Base Color'])
        if name in ("metal", "chrome"):
            bsdf.inputs['Metallic'].default_value = 0.7 if name == "metal" else 1.0
        if name == "carglass":
            bsdf.inputs['Base Color'].default_value = (0.01, 0.01, 0.012, 1.0)
    return m


def build_materials():
    return [class_material(name) for name in CLASSES]


def geo_to_mesh(name, g, mats):
    me = bpy.data.meshes.new("NC_" + name)
    nv, nt = g.nv, g.nt
    me.vertices.add(nv)
    me.vertices.foreach_set("co", g.p)
    me.loops.add(3 * nt)
    me.loops.foreach_set("vertex_index", g.idx)
    me.polygons.add(nt)
    me.polygons.foreach_set("loop_start", range(0, 3 * nt, 3))
    try:
        me.polygons.foreach_set("loop_total", [3] * nt)
    except (AttributeError, TypeError, RuntimeError):
        pass
    for mat in mats:
        me.materials.append(mat)
    me.polygons.foreach_set("material_index", [g.k[g.idx[3 * t]] for t in range(nt)])
    rgba = []
    for i in range(nv):
        rgba.extend((g.c[3 * i], g.c[3 * i + 1], g.c[3 * i + 2], g.ch[i] / 127.0))
    me.color_attributes.new("Col", 'FLOAT_COLOR', 'POINT').data.foreach_set("color", rgba)
    me.update(calc_edges=True)
    try:
        me.shade_smooth()
    except AttributeError:
        me.polygons.foreach_set("use_smooth", [True] * nt)
    return me


def instancer_group(name, proto_obj):
    ng = bpy.data.node_groups.new("NC_" + name + "_GN", 'GeometryNodeTree')
    ng.interface.new_socket("Geometry", in_out='INPUT', socket_type='NodeSocketGeometry')
    ng.interface.new_socket("Geometry", in_out='OUTPUT', socket_type='NodeSocketGeometry')
    N, L = ng.nodes, ng.links
    gi, go = N.new('NodeGroupInput'), N.new('NodeGroupOutput')
    info = N.new('GeometryNodeObjectInfo')
    info.inputs['Object'].default_value = proto_obj
    inst = N.new('GeometryNodeInstanceOnPoints')

    def attr(attr_name, dtype):
        n = N.new('GeometryNodeInputNamedAttribute')
        n.data_type = dtype
        n.inputs['Name'].default_value = attr_name
        return n.outputs['Attribute']

    rot = attr("nc_rot", 'FLOAT_VECTOR')
    if hasattr(bpy.types, 'FunctionNodeEulerToRotation'):
        e2r = N.new('FunctionNodeEulerToRotation')
        L.new(rot, e2r.inputs[0])
        rot = e2r.outputs[0]
    L.new(gi.outputs[0], inst.inputs['Points'])
    L.new(info.outputs['Geometry'], inst.inputs['Instance'])
    L.new(rot, inst.inputs['Rotation'])
    L.new(attr("nc_scale", 'FLOAT_VECTOR'), inst.inputs['Scale'])
    L.new(inst.outputs['Instances'], go.inputs[0])
    return ng


def make_instancer(name, rows, proto_obj, coll):
    me = bpy.data.meshes.new("NC_pts_" + name)
    me.vertices.add(len(rows))
    me.vertices.foreach_set("co", [v for r in rows for v in r[0:3]])
    me.attributes.new("nc_rot", 'FLOAT_VECTOR', 'POINT').data.foreach_set("vector", [v for r in rows for v in r[3:6]])
    me.attributes.new("nc_scale", 'FLOAT_VECTOR', 'POINT').data.foreach_set("vector", [v for r in rows for v in r[6:9]])
    ob = bpy.data.objects.new("NC_inst_" + name, me)
    coll.objects.link(ob)
    ob.modifiers.new("NC_Instances", 'NODES').node_group = instancer_group(name, proto_obj)
    return ob


def _bulk_keys(ob, path, index, frames, values):
    ad = ob.animation_data or ob.animation_data_create()
    if ad.action is None:
        ad.action = bpy.data.actions.new("NC_act_" + ob.name)
    fc = None
    try:
        fc = ad.action.fcurves.new(path, index=index)
    except (AttributeError, RuntimeError, TypeError):
        pass
    if fc is None:                                     # layered actions (Blender 5): fall back to keyframe_insert
        for f, v in zip(frames, values):
            vec = list(getattr(ob, path))
            vec[index] = v
            setattr(ob, path, vec)
            ob.keyframe_insert(path, index=index, frame=f)
        return
    fc.keyframe_points.add(len(frames))
    fc.keyframe_points.foreach_set("co", [c for fv in zip(frames, values) for c in fv])
    fc.keyframe_points.foreach_set("interpolation", [2] * len(frames))   # LINEAR
    fc.update()


def blender_actors(w, protos_obj, coll, mats):
    lanes = w.tables["lanes"]
    cars = w.tables["cars"]
    step = 3
    frames, poses = [], [[] for _ in cars]

    def rec(t, state):
        f = int(round(t * FPS))
        if f % step:
            return
        frames.append(f + 1)
        for i, st in enumerate(state):
            ln = lanes[int(cars[i][0])]
            x = ln[0] + ln[2] * st[0]
            y = ln[1] + ln[3] * st[0]
            poses[i].append((x, y, math.atan2(-ln[2], ln[3]), st[2]))

    simulate_traffic(lanes, cars, DURATION, record=rec)
    for i, c in enumerate(cars):
        kind = CAR_KINDS[int(c[2])]
        ob = bpy.data.objects.new("NC_car_%03d" % i, protos_obj["car_" + kind].data)
        ob.color = (c[3], c[4], c[5], 1.0)
        coll.objects.link(ob)
        ps = poses[i]
        _bulk_keys(ob, "location", 0, frames, [p[0] for p in ps])
        _bulk_keys(ob, "location", 1, frames, [p[1] for p in ps])
        _bulk_keys(ob, "rotation_euler", 2, frames, [p[2] for p in ps])
        L = CAR_SPEC[kind][0]
        hl = bpy.data.lights.new("NC_hl_%03d" % i, 'SPOT')
        hl.energy, hl.spot_size, hl.color = 900.0, math.radians(70), (1.0, 0.92, 0.8)
        hl.use_shadow = False
        lo = bpy.data.objects.new("NC_hl_%03d" % i, hl)
        lo.parent = ob
        lo.location = (0.0, L / 2 + 0.3, 0.75)
        lo.rotation_euler = (math.radians(-86), 0.0, 0.0)
        coll.objects.link(lo)
    # pedestrians
    for i, r in enumerate(w.tables["peds"]):
        x, y0, d, seg, speed, phase, variant = r[0:7]
        ob = bpy.data.objects.new("NC_ped_%03d" % i, protos_obj["ped_%d" % int(variant)].data)
        ob.color = (r[7], r[8], r[9], 1.0)
        ob.rotation_euler = (0.0, 0.0, 0.0 if d > 0 else math.pi)
        ob.location.z = CURB_H
        coll.objects.link(ob)
        fr = list(range(1, FRAMES + 2, 15))
        _bulk_keys(ob, "location", 0, fr, [x] * len(fr))
        _bulk_keys(ob, "location", 1, fr, [y0 + (phase + d * speed * (f - 1) / FPS) % seg for f in fr])
    # trains
    for ti, (ty, d, t_cross, speed, ncars) in enumerate(w.tables["trains"]):
        for ci in range(int(ncars)):
            head = ci == 0
            ob = bpy.data.objects.new("NC_train_%d_%d" % (ti, ci), protos_obj["train_head" if head else "train_car"].data)
            ob.rotation_euler = (0.0, 0.0, -math.pi / 2 if d > 0 else math.pi / 2)
            coll.objects.link(ob)
            off = -(ci * (TRAIN_CAR_L + 0.8) + TRAIN_CAR_L / 2)
            fr = [1, FRAMES + 1]
            xs = [d * (speed * ((f - 1) / FPS - t_cross) + off) for f in fr]
            _bulk_keys(ob, "location", 0, fr, xs)
            _bulk_keys(ob, "location", 1, fr, [ty, ty])
            _bulk_keys(ob, "location", 2, fr, [RAIL_Z, RAIL_Z])


def blender_lights(w, coll):
    for i, r in enumerate(w.lights):
        x, y, z, rad, cr, cg, cb, kind, dx, dy, dz, cos_o, group = r
        inten = max(cr, cg, cb)
        if inten <= 0.0:
            continue
        lt = bpy.data.lights.new("NC_l_%04d" % i, 'SPOT' if kind > 0.5 else 'POINT')
        lt.color = (cr / inten, cg / inten, cb / inten)
        lt.energy = inten * (380.0 if kind > 0.5 else 240.0)
        lt.use_shadow = kind > 0.5 and i % 4 == 0
        if hasattr(lt, "use_custom_distance"):
            lt.use_custom_distance = True
            lt.cutoff_distance = rad * 1.5
        if kind > 0.5:
            lt.spot_size = 2.0 * math.acos(clamp(cos_o, -1.0, 1.0))
            lt.spot_blend = 0.6
        ob = bpy.data.objects.new("NC_l_%04d" % i, lt)
        ob.location = (x, y, z)
        if kind > 0.5:
            ob.rotation_euler = Vector((dx, dy, dz)).to_track_quat('-Z', 'Y').to_euler()
        coll.objects.link(ob)


def blender_rain(coll, rng):
    me = bpy.data.meshes.new("NC_rain")
    verts, faces = [], []
    for i in range(RAIN_STREAKS):
        x = rng.uniform(-16.0, 16.0)
        y = rng.uniform(-110.0, 620.0)
        z = rng.uniform(0.0, 90.0)
        ln = rng.uniform(0.35, 0.7)
        b = len(verts)
        verts += [(x - 0.004, y, z), (x + 0.004, y, z), (x + 0.004 + 0.06, y, z + ln), (x - 0.004 + 0.06, y, z + ln)]
        faces.append((b, b + 1, b + 2, b + 3))
    me.from_pydata(verts, [], faces)
    mat = bpy.data.materials.new("NC_RainMat")
    nt, N, L = _nodes(mat)
    out = N.new('ShaderNodeOutputMaterial')
    bsdf = N.new('ShaderNodeBsdfPrincipled')
    bsdf.inputs['Base Color'].default_value = (0.8, 0.85, 0.9, 1.0)
    bsdf.inputs['Roughness'].default_value = 0.05
    bsdf.inputs['Alpha'].default_value = 0.22
    L.new(bsdf.outputs[0], out.inputs['Surface'])
    for prop, value in (("surface_render_method", 'BLENDED'), ("blend_method", 'BLEND')):
        try:
            setattr(mat, prop, value)
        except (AttributeError, TypeError):
            pass
    me.materials.append(mat)
    ob = bpy.data.objects.new("NC_Rain", me)
    coll.objects.link(ob)
    fr = list(range(1, FRAMES + 2, 30))
    _bulk_keys(ob, "location", 2, fr, [-(((f - 1) / FPS) * 9.0) % 45.0 for f in fr])   # sawtooth fall
    return ob


def blender_world(scene):
    world = bpy.data.worlds.new("NC_World")
    scene.world = world
    nt, N, L = _nodes(world)
    out = N.new('ShaderNodeOutputWorld')
    bg = N.new('ShaderNodeBackground')
    grad = N.new('ShaderNodeValToRGB')
    grad.color_ramp.elements[0].color = (0.10, 0.05, 0.07, 1.0)       # city glow on low cloud
    grad.color_ramp.elements[1].color = (0.006, 0.008, 0.016, 1.0)
    grad.color_ramp.elements[1].position = 0.35
    tc = N.new('ShaderNodeTexCoord')
    sep = N.new('ShaderNodeSeparateXYZ')
    L.new(tc.outputs['Generated'], sep.inputs[0])
    L.new(sep.outputs['Z'], grad.inputs['Fac'])
    clouds = N.new('ShaderNodeTexNoise')
    clouds.inputs['Scale'].default_value = 3.0
    clouds.inputs['Detail'].default_value = 8.0
    mix = N.new('ShaderNodeMix')
    mix.data_type = 'RGBA'
    mix.blend_type = 'MULTIPLY'
    L.new(clouds.outputs['Fac'], mix.inputs['Factor'])
    L.new(grad.outputs['Color'], mix.inputs['A'])
    mix.inputs['B'].default_value = (1.6, 1.3, 1.4, 1.0)
    L.new(mix.outputs['Result'], bg.inputs['Color'])
    bg.inputs['Strength'].default_value = 1.0
    L.new(bg.outputs[0], out.inputs['Surface'])
    vol = N.new('ShaderNodeVolumePrincipled')
    vol.inputs['Density'].default_value = 0.006
    vol.inputs['Anisotropy'].default_value = 0.45
    vol.inputs['Color'].default_value = (0.75, 0.78, 0.85, 1.0)
    L.new(vol.outputs[0], out.inputs['Volume'])


def blender_camera(scene, coll):
    cam = bpy.data.cameras.new("NC_Camera")
    cam.sensor_fit = 'VERTICAL'
    cam.clip_start, cam.clip_end = 0.1, 6000.0
    ob = bpy.data.objects.new("NC_Camera", cam)
    target = bpy.data.objects.new("NC_CameraTarget", None)
    coll.objects.link(ob)
    coll.objects.link(target)
    con = ob.constraints.new('TRACK_TO')
    con.target = target
    con.track_axis = 'TRACK_NEGATIVE_Z'
    con.up_axis = 'UP_Y'
    for f in range(1, FRAMES + 2, 6):
        t = (f - 1) / FPS
        ob.location = catmull(CAMERA_KEYS, t, 1)
        target.location = catmull(CAMERA_KEYS, t, 2)
        ob.keyframe_insert("location", frame=f)
        target.keyframe_insert("location", frame=f)
        i = max(k for k in range(len(CAMERA_KEYS)) if CAMERA_KEYS[k][0] <= t + 1e-9)
        j = min(i + 1, len(CAMERA_KEYS) - 1)
        span = (CAMERA_KEYS[j][0] - CAMERA_KEYS[i][0]) or 1.0
        fov = lerp(CAMERA_KEYS[i][3], CAMERA_KEYS[j][3], (t - CAMERA_KEYS[i][0]) / span)
        cam.angle = math.radians(fov)
        cam.keyframe_insert("lens", frame=f)
    scene.camera = ob
    scene.frame_start, scene.frame_end = 1, FRAMES
    scene.render.fps = FPS


def configure_render(scene):
    scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x, scene.render.resolution_y = 1920, 1080
    ee = scene.eevee
    for prop, value in (("taa_render_samples", 48), ("taa_samples", 8), ("use_raytracing", True),
                        ("use_shadows", True), ("volumetric_end", 450.0), ("volumetric_tile_size", '8'),
                        ("use_volumetric_shadows", True), ("clamp_surface_indirect", 10.0)):
        if hasattr(ee, prop):
            try:
                setattr(ee, prop, value)
            except (TypeError, ValueError):
                pass
    if hasattr(scene.render, "use_motion_blur"):
        scene.render.use_motion_blur = True
    try:
        scene.view_settings.view_transform = 'AgX'
        scene.view_settings.look = 'AgX - Punchy'
    except TypeError:
        pass


def build_blender_scene(w):
    scene = bpy.context.scene
    root = new_collection("NC_NeonCity", scene.collection)
    static = new_collection("NC_Static", root)
    inst_coll = new_collection("NC_Instances", root)
    proto_coll = new_collection("NC_Prototypes", root)
    actors = new_collection("NC_Actors", root)
    light_coll = new_collection("NC_Lights", root)
    mats = build_materials()
    for name, g in w.chunks.items():
        ob = bpy.data.objects.new("NC_" + name, geo_to_mesh(name, g, mats))
        static.objects.link(ob)
    protos_obj = {}
    for name, g in w.protos.items():
        ob = bpy.data.objects.new("NC_proto_" + name, geo_to_mesh(name, g, mats))
        proto_coll.objects.link(ob)
        protos_obj[name] = ob
    layer = bpy.context.view_layer.layer_collection.children["NC_NeonCity"].children["NC_Prototypes"]
    layer.exclude = True
    for name, rows in w.inst.items():
        make_instancer(name, rows, protos_obj[name], inst_coll)
    blender_actors(w, protos_obj, actors, mats)
    blender_lights(w, light_coll)
    blender_rain(actors, random.Random(SEED + 5))
    blender_world(scene)
    blender_camera(scene, root)
    configure_render(scene)
    scene.frame_set(int(30 * FPS))


# ── Phone export (app GPU benchmark, Test 2) ─────────────────────────────────
#
# Little-endian binary read by NeonCityAsset.kt:
#   "NCSC", u32 version
#   u32 meshCount, per mesh: name[16], u32 vertexCount, u32 indexCount, u32 flags (bit 0 = u32
#       indices, bit 1 = static chunk drawn once in world space), f32 maxZ, vertices, indices
#       (u16 runs padded to 4 bytes)
#     vertex = f32 x, y, z | i8 nx, ny, nz, channel | u8 r, g, b (sRGB), class
#   u32 groupCount, per group: name[16], u32 meshIndex, u32 count, count × f32 {pos, euler XYZ, scale}
#   u32 tableCount, per table: name[16], u32 rows, u32 cols, rows × cols f32
# Prototypes are drawn through their group or animated by the app (actors).

ASSET_VERSION = 1


def name16(s):
    b = s.encode("ascii")[:16]
    return b + b"\0" * (16 - len(b))


def pack_geo(name, g, chunk):
    import numpy as np
    nv = g.nv
    v = np.zeros(nv, dtype=[("p", "<f4", 3), ("n", "i1", 4), ("c", "u1", 4)])
    v["p"] = np.asarray(g.p, dtype=np.float32).reshape(nv, 3)
    n = np.clip(np.rint(np.asarray(g.n, dtype=np.float32).reshape(nv, 3) * 127.0), -127, 127).astype(np.int8)
    v["n"][:, 0:3] = n
    v["n"][:, 3] = np.asarray(g.ch, dtype=np.int8)
    c = np.asarray(g.c, dtype=np.float32).reshape(nv, 3)
    v["c"][:, 0:3] = np.rint(np.clip(c, 0.0, 1.0) ** (1.0 / 2.2) * 255.0).astype(np.uint8)
    v["c"][:, 3] = np.asarray(g.k, dtype=np.uint8)
    wide = nv > 0xFFFF
    idx = np.asarray(g.idx, dtype=np.uint32 if wide else np.uint16)
    max_z = max(g.p[2::3]) if nv else 0.0
    out = bytearray(name16(name))
    out += struct.pack("<3If", nv, len(g.idx), (1 if wide else 0) | (2 if chunk else 0), max_z)
    out += v.tobytes()
    out += idx.tobytes()
    if not wide and len(g.idx) % 2:
        out += b"\0\0"
    return out


def export_app_scene(path, w):
    t0 = time.time()
    names = list(w.chunks) + list(w.protos)
    geos = [w.chunks[n] for n in w.chunks] + [w.protos[n] for n in w.protos]
    out = bytearray(b"NCSC") + struct.pack("<I", ASSET_VERSION)
    out += struct.pack("<I", len(names))
    for n, g in zip(names, geos):
        out += pack_geo(n, g, n in w.chunks)
    groups = [(n, rows) for n, rows in w.inst.items()]
    out += struct.pack("<I", len(groups))
    for n, rows in groups:
        out += name16(n) + struct.pack("<2I", names.index(n), len(rows))
        out += struct.pack("<%df" % (9 * len(rows)), *[v for r in rows for v in r])
    tables = dict(w.tables)
    tables["lights"] = sorted(w.lights, key=lambda r: -max(r[4], r[5], r[6]))
    out += struct.pack("<I", len(tables))
    for n, rows in tables.items():
        cols = len(rows[0]) if rows else 0
        out += name16(n) + struct.pack("<2I", len(rows), cols)
        out += struct.pack("<%df" % (cols * len(rows)), *[v for r in rows for v in r])
    with open(path, "wb") as f:
        f.write(out)
    print("app scene → %s (%.1f MB, %.1fs)" % (path, len(out) / 1e6, time.time() - t0))


# ── Build ────────────────────────────────────────────────────────────────────

def build_world(rng):
    w = World(rng)
    build_prototypes(w, rng)
    build_city_blocks(w, rng)
    build_streets(w, rng)
    build_viaduct(w, rng)
    build_overhead(w, rng)
    build_street_furniture(w, rng)
    build_skyline(w, rng)
    build_traffic(w, rng)
    build_pedestrians(w, rng)
    build_trains(w)
    build_drones(w)
    build_camera_keys(w)
    return w


def report(w):
    chunk_tris = sum(g.nt for g in w.chunks.values())
    inst_tris = {n: w.protos[n].nt * len(rows) for n, rows in w.inst.items()}
    cars = w.tables["cars"]
    car_tris = sum(w.protos["car_" + CAR_KINDS[int(c[2])]].nt for c in cars)
    ped_tris = sum(w.protos["ped_%d" % int(p[6])].nt for p in w.tables["peds"])
    train_tris = 2 * TRAIN_CARS * w.protos["train_car"].nt
    print("  unique geometry: %s tris in %d chunks (%s)" % (f"{chunk_tris:,}", len(w.chunks),
          ", ".join("%s %dk" % (n, g.nt // 1000) for n, g in w.chunks.items())))
    for n, t in sorted(inst_tris.items(), key=lambda kv: -kv[1]):
        print("  %-16s %6d x %5d tris = %s" % (n, len(w.inst[n]), w.protos[n].nt, f"{t:,}"))
    print("  cars %d (%s tris), peds %d (%s), trains %s" % (len(cars), f"{car_tris:,}", len(w.tables["peds"]),
          f"{ped_tris:,}", f"{train_tris:,}"))
    total = chunk_tris + sum(inst_tris.values()) + car_tris + ped_tris + train_tris
    print(f"  main-pass triangles ≈ {total:,}; static lights {len(w.lights)}")
    return total


def main(save_path=None, export_path=None, blender=True):
    t0 = time.time()
    _text_cache.clear()
    reset_scene()
    rng = random.Random(SEED)
    w = build_world(rng)
    print("NeonCity built in %.1fs" % (time.time() - t0))
    total = report(w)
    if export_path:
        export_app_scene(export_path, w)
    if blender:
        t1 = time.time()
        build_blender_scene(w)
        print("Blender scene assembled in %.1fs" % (time.time() - t1))
        bpy.context.scene["nc_triangles"] = total
    if save_path:
        bpy.ops.wm.save_as_mainfile(filepath=save_path)
        print("saved", save_path)
    return w


if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    main(argv[argv.index("--save") + 1] if "--save" in argv else None,
         argv[argv.index("--export") + 1] if "--export" in argv else None,
         "--no-blender" not in argv)
