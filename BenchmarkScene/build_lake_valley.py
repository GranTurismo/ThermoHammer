"""ThermoHammer GPU benchmark — "Lake Valley" outdoor scene generator.

Golden-hour mountain lake: log-polar terrain (dense at the camera, coarse at
the horizon), an instanced pine/broadleaf forest, boulders, grass clumps, a
dock and a log cabin. Everything is procedural and seeded, so every run builds
the identical scene and the benchmark workload never drifts between exports.

Run in Blender 5.2+:
  - Scripting tab > open this file > Run Script, or
  - blender --background --python build_lake_valley.py -- --save lake_valley.blend
  - ... -- --export ../ThermoHammerAndroid/app/src/main/assets/lake_valley.bin
    writes the phone version of the scene (lighter meshes, see export_app_scene)
    for the app's real-time GPU benchmark.

The active scene is cleared and rebuilt; data-blocks are prefixed "LV_".
"""

import math
import random
import struct
import sys
import time

import bmesh
import bpy
from mathutils import Matrix, Vector, noise

# ── Layout ───────────────────────────────────────────────────────────────────

SEED = 7
WATER_Z = 0.0
LAKE_C = (40.0, 80.0)          # lake centre (m)
LAKE_R = (120.0, 72.0)         # lake ellipse radii (m)
CAM_POS = (0.0, 0.0)           # camera start — also the terrain's log-polar centre
CAM_EYE = 1.7                  # eye height above ground
CAM_YAW = 12.0                 # view bearing, degrees clockwise from +Y
CAM_PITCH = 5.0
CAM_LENS = 28.0
CAM_DOLLY = 16.0               # metres the camera slides right and back over the loop
FRAMES = 720                   # 30 s camera loop at 24 fps

# ── Lighting ─────────────────────────────────────────────────────────────────

SUN_ELEV = 24.0                # late afternoon — high enough to clear the western hills
SUN_BEARING = -105.0           # behind-left of the camera, long shadows across the valley
SUN_STRENGTH = 9.0
SUN_COLOR = (1.0, 0.78, 0.55)
SKY_STRENGTH = 0.14
FOG_DENSITY = 0.00016          # aerial perspective — ~27 % haze at 2 km

# ── Workload budget ──────────────────────────────────────────────────────────

TERRAIN_CENTER = LAKE_C        # log-polar centre: densest around the lake the app camera orbits
TERRAIN_SEGMENTS = 768         # angular resolution of the log-polar terrain
TERRAIN_INNER = 12.0           # first ring radius (m) — the middle of the lake bed
TERRAIN_NEAR_STEP = 1.0        # ring spacing floor (m)
TERRAIN_RADIUS = 3500.0
WATER_GRID = 96
PINE_COUNT = 2400              # full-detail trees within NEAR_FOREST_RADIUS
BROADLEAF_COUNT = 400
FAR_PINE_COUNT = 9000          # low-poly LOD pines out to FAR_FOREST_RADIUS
NEAR_FOREST_RADIUS = 420.0
FAR_FOREST_RADIUS = 1500.0
ROCK_COUNT = 240
GRASS_COUNT = 16000
GRASS_RADIUS = 95.0
REED_COUNT = 2000              # shoreline reed clumps
BOAT_POS = (22.0, 92.0, 140.0)  # x, y, heading (deg) of the rowboat in the Blender scene

# ── Phone export (app GPU benchmark) ─────────────────────────────────────────

APP_SEGMENTS = 512             # terrain angular resolution on the phone

TAU = 2.0 * math.pi
EXCLUDE = []                   # (x, y, r) keep-out circles around the dock and cabin


# ── Noise & shaping ──────────────────────────────────────────────────────────

_noise = noise.noise


def clamp(x, a, b):
    return a if x < a else b if x > b else x


def smoothstep(e0, e1, x):
    t = clamp((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3.0 - 2.0 * t)


def mix3(a, b, t):
    return (a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)


def fbm(x, y, octaves, z=0.0):
    s = norm = 0.0
    a = 1.0
    for i in range(octaves):
        s += a * _noise((x, y, z + i * 17.31))
        norm += a
        a *= 0.5
        x *= 2.03
        y *= 2.03
    return s / norm


def fbm3(x, y, z, octaves):
    s = norm = 0.0
    a = 1.0
    for _ in range(octaves):
        s += a * _noise((x, y, z))
        norm += a
        a *= 0.5
        x, y, z = x * 2.03 + 5.1, y * 2.03 + 1.7, z * 2.03 + 3.3
    return s / norm


def ridged(x, y, octaves):
    s, a, w = 0.0, 0.5, 1.0
    for i in range(octaves):
        n = 1.0 - abs(_noise((x, y, 50.0 + i * 7.13)))
        n *= n * w
        w = clamp(n * 2.0, 0.0, 1.0)
        s += n * a
        a *= 0.5
        x *= 2.07
        y *= 2.07
    return s


def lake_e(x, y):
    """Elliptical distance from the lake centre: <1 is water, with a noisy shoreline."""
    dx = (x - LAKE_C[0]) / LAKE_R[0]
    dy = (y - LAKE_C[1]) / LAKE_R[1]
    return math.hypot(dx, dy) + 0.16 * fbm(x / 55.0, y / 55.0, 3, 3.3)


def forest(x, y):
    return smoothstep(0.0, 0.3, fbm(x / 160.0, y / 160.0, 4, 21.0) + 0.22)


def height(x, y):
    e = lake_e(x, y)
    # meadow by the shore, rolling hills further out
    hills = 1.6 + 55.0 * smoothstep(1.4, 5.0, e)
    h = 0.9 + 7.0 * smoothstep(1.0, 2.6, e) + hills * (0.5 + 0.5 * fbm(x / 170.0, y / 170.0, 6, 1.0))
    # ridged mountain ring, taller to the north
    d = math.hypot(x - LAKE_C[0], y - LAKE_C[1])
    m = smoothstep(380.0, 1500.0, d)
    if m > 0.0:
        north = 0.75 + 0.25 * (y - LAKE_C[1]) / d
        h += m * north * 720.0 * ridged(x / 1100.0, y / 1100.0, 6)
    # lake basin
    shore = smoothstep(0.80, 1.0, e)
    if shore < 1.0:
        bed = -4.5 + 1.2 * fbm(x / 30.0, y / 30.0, 2, 9.0)
        h = h * shore + bed * (1.0 - shore)
    return h


def slope_at(x, y):
    d = 0.8
    gx = (height(x + d, y) - height(x - d, y)) / (2.0 * d)
    gy = (height(x, y + d) - height(x, y - d)) / (2.0 * d)
    return math.degrees(math.atan(math.hypot(gx, gy)))


def angle_diff(a, b):
    return (a - b + 180.0) % 360.0 - 180.0


def cam_path():
    b = math.radians(CAM_YAW)
    return [(CAM_POS[0] + math.cos(b) * s, CAM_POS[1] - math.sin(b) * s)
            for s in (0.0, CAM_DOLLY * 0.25, CAM_DOLLY * 0.5, CAM_DOLLY * 0.75, CAM_DOLLY)]


def near_path(x, y, radius, bearing=None, cone=180.0):
    """True if (x, y) lies within `radius` of the camera path, inside `cone` degrees of `bearing`."""
    for px, py in cam_path():
        if (x - px) ** 2 + (y - py) ** 2 < radius * radius and (
                bearing is None or abs(angle_diff(math.degrees(math.atan2(x - px, y - py)), bearing)) < cone):
            return True
    return False


def excluded(x, y, pad):
    return any((x - ex) ** 2 + (y - ey) ** 2 < (er + pad) ** 2 for ex, ey, er in EXCLUDE)


# ── Scene reset ──────────────────────────────────────────────────────────────

def reset_scene():
    scene = bpy.context.scene
    scene.name = "LakeValley"
    for ob in list(scene.objects):
        bpy.data.objects.remove(ob, do_unlink=True)
    for coll in list(scene.collection.children):
        scene.collection.children.unlink(coll)
    for blocks in (bpy.data.objects, bpy.data.collections, bpy.data.meshes, bpy.data.materials,
                   bpy.data.node_groups, bpy.data.lights, bpy.data.cameras, bpy.data.worlds):
        for block in list(blocks):
            if block.name.startswith("LV_") or block.users == 0:
                blocks.remove(block)
    return scene


def new_collection(name, parent):
    coll = bpy.data.collections.new(name)
    parent.children.link(coll)
    return coll


# ── Sky (shared by the world and the aerial-perspective fog) ─────────────────

def sun_vector():
    el, b = math.radians(SUN_ELEV), math.radians(SUN_BEARING)
    return Vector((math.sin(b) * math.cos(el), math.cos(b) * math.cos(el), math.sin(el)))


def configure_sky(node):
    types = [i.identifier for i in node.bl_rna.properties["sky_type"].enum_items]
    node.sky_type = 'MULTIPLE_SCATTERING' if 'MULTIPLE_SCATTERING' in types else types[0]
    node.sun_elevation = math.radians(SUN_ELEV)
    node.sun_rotation = math.radians(SUN_BEARING)   # 0 = +Y, 90 = +X
    node.sun_disc = False                           # direct light comes from the sun lamp
    for prop, value in (("altitude", 1200.0), ("air_density", 1.0),
                        ("aerosol_density", 1.0), ("ozone_density", 1.0)):
        if hasattr(node, prop):
            setattr(node, prop, value)


# ── Materials ────────────────────────────────────────────────────────────────

def aerial_group():
    """Blend a shader toward the sky colour along the view ray — distance haze."""
    ng = bpy.data.node_groups.new("LV_AerialPerspective", 'ShaderNodeTree')
    ng.interface.new_socket("Shader", in_out='INPUT', socket_type='NodeSocketShader')
    ng.interface.new_socket("Shader", in_out='OUTPUT', socket_type='NodeSocketShader')
    N, L = ng.nodes, ng.links
    gi, go = N.new('NodeGroupInput'), N.new('NodeGroupOutput')

    geo = N.new('ShaderNodeNewGeometry')
    view = N.new('ShaderNodeVectorMath')
    view.operation = 'SCALE'
    view.inputs['Scale'].default_value = -1.0
    L.new(geo.outputs['Incoming'], view.inputs[0])
    sep = N.new('ShaderNodeSeparateXYZ')
    L.new(view.outputs['Vector'], sep.inputs[0])
    lift = N.new('ShaderNodeMath')                  # keep below-horizon rays on the sky
    lift.operation = 'MAXIMUM'
    lift.inputs[1].default_value = 0.035
    L.new(sep.outputs['Z'], lift.inputs[0])
    comb = N.new('ShaderNodeCombineXYZ')
    L.new(sep.outputs['X'], comb.inputs['X'])
    L.new(sep.outputs['Y'], comb.inputs['Y'])
    L.new(lift.outputs[0], comb.inputs['Z'])
    unit = N.new('ShaderNodeVectorMath')
    unit.operation = 'NORMALIZE'
    L.new(comb.outputs[0], unit.inputs[0])
    sky = N.new('ShaderNodeTexSky')
    configure_sky(sky)
    L.new(unit.outputs['Vector'], sky.inputs['Vector'])
    haze = N.new('ShaderNodeEmission')
    haze.inputs['Strength'].default_value = SKY_STRENGTH
    L.new(sky.outputs['Color'], haze.inputs['Color'])

    cam = N.new('ShaderNodeCameraData')
    k = N.new('ShaderNodeMath')
    k.operation = 'MULTIPLY'
    k.inputs[1].default_value = -FOG_DENSITY
    L.new(cam.outputs['View Distance'], k.inputs[0])
    ex = N.new('ShaderNodeMath')
    ex.operation = 'EXPONENT'
    L.new(k.outputs[0], ex.inputs[0])
    fac = N.new('ShaderNodeMath')
    fac.operation = 'SUBTRACT'
    fac.inputs[0].default_value = 1.0
    L.new(ex.outputs[0], fac.inputs[1])

    mix = N.new('ShaderNodeMixShader')
    L.new(fac.outputs[0], mix.inputs['Fac'])
    L.new(gi.outputs['Shader'], mix.inputs[1])
    L.new(haze.outputs[0], mix.inputs[2])
    L.new(mix.outputs[0], go.inputs['Shader'])
    return ng


def make_material(name, fog, *, tint=(1.0, 1.0, 1.0), rough=0.8, rand=0.0, detail=0.0,
                  detail_scale=0.3, bump=0.0, bump_scale=2.0, spec=0.5, ior=None,
                  emission=None, two_sided=True, base=None):
    m = bpy.data.materials.new(name)
    try:
        m.use_nodes = True
    except (AttributeError, TypeError):
        pass
    m.use_backface_culling = not two_sided
    nt = m.node_tree
    N, L = nt.nodes, nt.links
    for n in list(N):
        N.remove(n)
    out = N.new('ShaderNodeOutputMaterial')
    bsdf = N.new('ShaderNodeBsdfPrincipled')
    coords = N.new('ShaderNodeTexCoord')

    if base is not None:
        color = None
        bsdf.inputs['Base Color'].default_value = (*base, 1.0)
    else:
        col = N.new('ShaderNodeVertexColor')
        col.layer_name = "Col"
        mul = N.new('ShaderNodeVectorMath')
        mul.operation = 'MULTIPLY'
        mul.inputs[1].default_value = tint
        L.new(col.outputs['Color'], mul.inputs[0])
        color = mul.outputs['Vector']
    if rand and color is not None:                  # per-instance variation
        info = N.new('ShaderNodeObjectInfo')
        rng = N.new('ShaderNodeMapRange')
        rng.inputs['To Min'].default_value = 1.0 - rand
        rng.inputs['To Max'].default_value = 1.0 + rand
        L.new(info.outputs['Random'], rng.inputs['Value'])
        sc = N.new('ShaderNodeVectorMath')
        sc.operation = 'SCALE'
        L.new(color, sc.inputs[0])
        L.new(rng.outputs['Result'], sc.inputs['Scale'])
        color = sc.outputs['Vector']
    if detail and color is not None:                # large-scale albedo breakup
        tex = N.new('ShaderNodeTexNoise')
        tex.inputs['Scale'].default_value = detail_scale
        tex.inputs['Detail'].default_value = 6.0
        L.new(coords.outputs['Object'], tex.inputs['Vector'])
        rng = N.new('ShaderNodeMapRange')
        rng.inputs['To Min'].default_value = 1.0 - detail
        rng.inputs['To Max'].default_value = 1.0 + detail
        L.new(tex.outputs['Fac'], rng.inputs['Value'])
        sc = N.new('ShaderNodeVectorMath')
        sc.operation = 'SCALE'
        L.new(color, sc.inputs[0])
        L.new(rng.outputs['Result'], sc.inputs['Scale'])
        color = sc.outputs['Vector']
    if color is not None:
        L.new(color, bsdf.inputs['Base Color'])
    if bump:
        tex = N.new('ShaderNodeTexNoise')
        tex.inputs['Scale'].default_value = bump_scale
        tex.inputs['Detail'].default_value = 8.0
        L.new(coords.outputs['Object'], tex.inputs['Vector'])
        bn = N.new('ShaderNodeBump')
        bn.inputs['Strength'].default_value = bump
        bn.inputs['Distance'].default_value = 0.1
        L.new(tex.outputs['Fac'], bn.inputs['Height'])
        L.new(bn.outputs['Normal'], bsdf.inputs['Normal'])

    bsdf.inputs['Roughness'].default_value = rough
    if 'Specular IOR Level' in bsdf.inputs:
        bsdf.inputs['Specular IOR Level'].default_value = spec
    if ior is not None:
        bsdf.inputs['IOR'].default_value = ior
    if emission is not None:
        bsdf.inputs['Emission Color'].default_value = (*emission[0], 1.0)
        bsdf.inputs['Emission Strength'].default_value = emission[1]

    group = N.new('ShaderNodeGroup')
    group.node_tree = fog
    L.new(bsdf.outputs['BSDF'], group.inputs[0])
    L.new(group.outputs[0], out.inputs['Surface'])
    return m


def build_materials():
    fog = aerial_group()
    return {
        "terrain": make_material("LV_Terrain", fog, rough=0.92, detail=0.22, detail_scale=0.35,
                                 bump=0.25, bump_scale=1.8, spec=0.3, two_sided=False),
        "water": make_material("LV_Water", fog, base=(0.006, 0.018, 0.020), rough=0.035, ior=1.333,
                               bump=0.06, bump_scale=0.6, two_sided=False),
        "bark": make_material("LV_Bark", fog, rough=0.9, rand=0.15, detail=0.2, detail_scale=4.0,
                              bump=0.4, bump_scale=6.0, spec=0.25, two_sided=False),
        "needles": make_material("LV_Needles", fog, rough=0.75, rand=0.18, detail=0.15,
                                 detail_scale=2.0, bump=0.2, bump_scale=5.0, spec=0.35),
        "leaves": make_material("LV_Leaves", fog, rough=0.65, rand=0.25, detail=0.18,
                                detail_scale=1.5, bump=0.25, bump_scale=4.0, spec=0.4),
        "rock": make_material("LV_Rock", fog, rough=0.85, rand=0.12, detail=0.3, detail_scale=1.5,
                              bump=0.9, bump_scale=4.0, spec=0.35, two_sided=False),
        "grass": make_material("LV_Grass", fog, rough=0.55, rand=0.2, spec=0.4),
        "wood": make_material("LV_Wood", fog, rough=0.8, detail=0.25, detail_scale=2.5, bump=0.2,
                              bump_scale=8.0, spec=0.3, two_sided=False),
        "roof": make_material("LV_Roof", fog, rough=0.85, bump=0.3, bump_scale=4.0, spec=0.3,
                              two_sided=False),
        "stone": make_material("LV_Stone", fog, rough=0.9, detail=0.3, detail_scale=3.0, bump=0.5,
                               bump_scale=5.0, spec=0.3, two_sided=False),
        "window": make_material("LV_WindowGlow", fog, base=(0.05, 0.03, 0.02), rough=0.3,
                                emission=((1.0, 0.55, 0.25), 8.0), two_sided=False),
    }


# ── Mesh helpers ─────────────────────────────────────────────────────────────

def bm_new():
    bm = bmesh.new()
    return bm, bm.verts.layers.float_color.new("Col")


def bm_finish(bm, name, mats):
    me = bpy.data.meshes.new(name)
    bm.to_mesh(me)
    bm.free()
    for m in mats:
        me.materials.append(m)
    return me


def ring(bm, col, center, axis, r, seg, color, phase=0.0, radius_fn=None):
    """Vertex ring around `axis`, counter-clockwise seen from the axis tip."""
    axis = Vector(axis).normalized()
    u = axis.orthogonal().normalized()
    v = axis.cross(u)
    out = []
    for j in range(seg):
        a = phase + TAU * j / seg
        rr = r if radius_fn is None else radius_fn(j, a)
        vert = bm.verts.new(Vector(center) + (u * math.cos(a) + v * math.sin(a)) * rr)
        vert[col] = (*color, 1.0)
        out.append(vert)
    return out


def bridge(bm, lo, hi, mat, smooth=True):
    n = len(lo)
    for j in range(n):
        j1 = (j + 1) % n
        f = bm.faces.new((lo[j], lo[j1], hi[j1], hi[j]))
        f.material_index = mat
        f.smooth = smooth


def fan(bm, rim, tip, mat, smooth=True):
    n = len(rim)
    for j in range(n):
        f = bm.faces.new((rim[j], rim[(j + 1) % n], tip))
        f.material_index = mat
        f.smooth = smooth


def point(bm, col, co, color):
    v = bm.verts.new(co)
    v[col] = (*color, 1.0)
    return v


def tube(bm, col, p0, p1, r0, r1, seg, color, mat, caps=False):
    p0, p1 = Vector(p0), Vector(p1)
    axis = p1 - p0
    a = ring(bm, col, p0, axis, r0, seg, color)
    b = ring(bm, col, p1, axis, r1, seg, color)
    bridge(bm, a, b, mat)
    if caps:
        for rim, flip in ((a, True), (b, False)):
            f = bm.faces.new(list(reversed(rim)) if flip else rim)
            f.material_index = mat
            f.smooth = False


def box(bm, col, matrix, size, color, mat):
    hx, hy, hz = size[0] / 2.0, size[1] / 2.0, size[2] / 2.0
    corners = ((-1, -1, -1), (1, -1, -1), (1, 1, -1), (-1, 1, -1),
               (-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1))
    vs = [point(bm, col, matrix @ Vector((ix * hx, iy * hy, iz * hz)), color) for ix, iy, iz in corners]
    for quad in ((0, 3, 2, 1), (4, 5, 6, 7), (0, 1, 5, 4), (1, 2, 6, 5), (2, 3, 7, 6), (3, 0, 4, 7)):
        f = bm.faces.new([vs[i] for i in quad])
        f.material_index = mat
        f.smooth = False


def at(x, y, z, rz=0.0, rx=0.0):
    return Matrix.Translation((x, y, z)) @ Matrix.Rotation(rz, 4, 'Z') @ Matrix.Rotation(rx, 4, 'X')


# ── Terrain & water ──────────────────────────────────────────────────────────

GRASS_A = (0.060, 0.105, 0.025)
GRASS_B = (0.110, 0.140, 0.040)
FOREST_FLOOR = (0.030, 0.048, 0.022)
DIRT = (0.110, 0.085, 0.055)
SAND = (0.190, 0.160, 0.115)
WET = (0.090, 0.075, 0.055)
LAKEBED = (0.045, 0.050, 0.040)
ROCK = (0.140, 0.130, 0.120)
ROCK_DARK = (0.050, 0.048, 0.045)
ALPINE = (0.075, 0.085, 0.035)
SNOW = (0.780, 0.800, 0.840)


def terrain_color(x, y, z, nz):
    slope = math.degrees(math.acos(clamp(nz, -1.0, 1.0)))
    patch = fbm(x / 9.0, y / 9.0, 3, 40.0) * 0.5 + 0.5
    c = mix3(GRASS_A, GRASS_B, smoothstep(0.35, 0.75, patch))
    if z < 340.0:                                    # reads as canopy from afar
        c = mix3(c, FOREST_FLOOR, 0.85 * forest(x, y) * (1.0 - smoothstep(280.0, 340.0, z)))
    c = mix3(c, DIRT, smoothstep(28.0, 40.0, slope))
    alpine = smoothstep(280.0, 380.0, z)
    if alpine > 0.0:                                 # high meadows on the gentler shoulders
        c = mix3(c, ALPINE, alpine * (1.0 - smoothstep(18.0, 30.0, slope)))
    rocky = max(smoothstep(38.0, 52.0, slope), alpine * smoothstep(18.0, 32.0, slope))
    if rocky > 0.0:
        strata = 0.5 + 0.5 * fbm(x / 90.0, y / 90.0 + z / 40.0, 4, 64.0)
        c = mix3(c, mix3(ROCK, ROCK_DARK, clamp(strata * 1.6 - 0.3, 0.0, 1.0)), rocky)
    c = mix3(c, SAND, 1.0 - smoothstep(0.45, 1.0, z))
    c = mix3(c, WET, 1.0 - smoothstep(-0.2, 0.15, z))
    c = mix3(c, LAKEBED, 1.0 - smoothstep(-2.5, -0.8, z))
    if z > 300.0:
        snowline = 420.0 + 70.0 * fbm(x / 300.0, y / 300.0, 3, 60.0)
        snow = smoothstep(snowline, snowline + 40.0, z) * (1.0 - smoothstep(38.0, 50.0, slope))
        c = mix3(c, SNOW, snow)
    return c


def terrain_mesh(name, n):
    """Log-polar terrain: vertex 0 is the centre, then rings of `n` vertices. Returns (mesh, rings)."""
    step = TAU / n
    radii = []
    r = TERRAIN_INNER
    while r < TERRAIN_RADIUS:
        radii.append(r)
        r += max(TERRAIN_NEAR_STEP, r * step)       # square-ish quads at every radius
    radii.append(TERRAIN_RADIUS)

    cx, cy = TERRAIN_CENTER
    cos_t = [math.cos(j * step) for j in range(n)]
    sin_t = [math.sin(j * step) for j in range(n)]
    verts = [(cx, cy, height(cx, cy))]
    for r in radii:
        for j in range(n):
            x, y = cx + r * cos_t[j], cy + r * sin_t[j]
            verts.append((x, y, height(x, y)))
    faces = [(0, 1 + j, 1 + (j + 1) % n) for j in range(n)]
    for k in range(len(radii) - 1):
        a, b = 1 + k * n, 1 + (k + 1) * n
        for j in range(n):
            j1 = (j + 1) % n
            faces.append((a + j, b + j, b + j1, a + j1))

    me = bpy.data.meshes.new(name)
    me.from_pydata(verts, [], faces)
    me.shade_smooth()
    normals = [0.0] * (3 * len(verts))
    me.vertex_normals.foreach_get("vector", normals)
    colors = []
    for i, (x, y, z) in enumerate(verts):
        colors.extend((*terrain_color(x, y, z, normals[3 * i + 2]), 1.0))
    me.color_attributes.new("Col", 'FLOAT_COLOR', 'POINT').data.foreach_set("color", colors)
    return me, len(radii)


def build_terrain(mats, coll):
    me, _ = terrain_mesh("LV_Terrain", TERRAIN_SEGMENTS)
    me.materials.append(mats["terrain"])
    ob = bpy.data.objects.new("LV_Terrain", me)
    coll.objects.link(ob)
    return ob


def build_water(mats, coll):
    hx, hy = LAKE_R[0] * 1.3, LAKE_R[1] * 1.35
    g = WATER_GRID
    verts = [(LAKE_C[0] - hx + 2 * hx * i / g, LAKE_C[1] - hy + 2 * hy * j / g, WATER_Z)
             for j in range(g + 1) for i in range(g + 1)]
    faces = [(j * (g + 1) + i, j * (g + 1) + i + 1, (j + 1) * (g + 1) + i + 1, (j + 1) * (g + 1) + i)
             for j in range(g) for i in range(g)]
    me = bpy.data.meshes.new("LV_Water")
    me.from_pydata(verts, [], faces)
    me.materials.append(mats["water"])
    ob = bpy.data.objects.new("LV_Water", me)
    coll.objects.link(ob)
    return ob


# ── Vegetation & rock prototypes ─────────────────────────────────────────────

BARK = (0.085, 0.060, 0.042)


def make_pine(name, rng, h, radius, tiers, seg, mats, trunk_seg=8):
    bm, col = bm_new()
    tr = h * 0.022
    zs = (-0.5, 0.0, 0.12 * h, 0.35 * h, 0.6 * h, 0.85 * h)
    rings = [ring(bm, col, (0, 0, z), (0, 0, 1), tr * (1.0 - 0.9 * max(z, 0.0) / h), trunk_seg,
                  mix3(BARK, (0.04, 0.03, 0.02), 0.5 if z <= 0 else 0.0)) for z in zs]
    for lo, hi in zip(rings, rings[1:]):
        bridge(bm, lo, hi, 0)
    fan(bm, rings[-1], point(bm, col, (0, 0, 0.97 * h), BARK), 0)

    for i in range(tiers):
        t = i / (tiers - 1)
        z0 = h * (0.16 + 0.74 * t)
        rad = radius * (1.0 - t) ** 0.85 * rng.uniform(0.88, 1.08) + 0.35
        th = h * 0.19 * (1.0 - 0.45 * t)
        phase = rng.uniform(0, TAU)
        off = rng.uniform(0, 100)

        def jag(j, a, frac, rad=rad, off=off):
            tip = 1.22 if j % 2 == 0 else 0.88
            wob = 1.0 + 0.12 * _noise((math.cos(a) * 2 + off, math.sin(a) * 2, frac * 3))
            return rad * frac * (tip if frac == 1.0 else 1.0) * wob

        inner = ring(bm, col, (0, 0, z0 + th * 0.70), (0, 0, 1), 0, seg, (0.022, 0.045, 0.020),
                     phase, lambda j, a: jag(j, a, 0.33))
        mid = ring(bm, col, (0, 0, z0 + th * 0.33), (0, 0, 1), 0, seg, (0.030, 0.065, 0.025),
                   phase, lambda j, a: jag(j, a, 0.68))
        outer = ring(bm, col, (0, 0, z0), (0, 0, 1), 0, seg, (0.050, 0.090, 0.030),
                     phase, lambda j, a: jag(j, a, 1.0))
        for j, v in enumerate(outer):               # long branch tips droop and catch light
            if j % 2 == 0:
                v.co.z -= 0.22 * rad
                v[col] = (0.065, 0.100, 0.035, 1.0)
        bridge(bm, outer, mid, 1)
        bridge(bm, mid, inner, 1)
        fan(bm, inner, point(bm, col, (0, 0, z0 + th), (0.020, 0.040, 0.018)), 1)
    return bm_finish(bm, name, (mats["bark"], mats["needles"]))


def make_pine_lod(name, rng, h, radius, tiers, seg, mats):
    """Distant pine: one jagged cone per tier on a 3-sided trunk — a few dozen triangles."""
    bm, col = bm_new()
    trunk = [ring(bm, col, (0, 0, z), (0, 0, 1), h * 0.02, 3, BARK) for z in (-0.5, 0.3 * h)]
    bridge(bm, trunk[0], trunk[1], 0)
    for i in range(tiers):
        t = i / (tiers - 1)
        z0 = h * (0.18 + 0.72 * t)
        rad = radius * (1.0 - t) ** 0.85 * rng.uniform(0.9, 1.08) + 0.4
        rim = ring(bm, col, (0, 0, z0), (0, 0, 1), 0, seg, (0.050, 0.090, 0.030), rng.uniform(0, TAU),
                   lambda j, a, rad=rad: rad * (1.2 if j % 2 == 0 else 0.85))
        fan(bm, rim, point(bm, col, (0, 0, z0 + h * 0.24 * (1.0 - 0.4 * t)), (0.022, 0.045, 0.020)), 1)
    return bm_finish(bm, name, (mats["bark"], mats["needles"]))


def make_broadleaf(name, rng, h, mats, blob_subdiv=3):
    bm, col = bm_new()
    trunk_top = 0.45 * h
    base_r = 0.022 * h
    zs = (-0.5, 0.0, 0.15 * h, 0.3 * h, trunk_top)
    rings = [ring(bm, col, (0, 0, z), (0, 0, 1), base_r * (1.15 if z <= 0 else 1.0 - 0.5 * z / trunk_top),
                  10, BARK) for z in zs]
    for lo, hi in zip(rings, rings[1:]):
        bridge(bm, lo, hi, 0)
    fan(bm, rings[-1], point(bm, col, (0, 0, trunk_top + 0.4), BARK), 0)

    blobs = [((0.0, 0.0, 0.78 * h), 0.30 * h)]
    for k in range(5):
        a = TAU * k / 5 + rng.uniform(-0.3, 0.3)
        reach = rng.uniform(0.18, 0.26) * h
        z1 = rng.uniform(0.55, 0.68) * h
        p0 = (0.0, 0.0, rng.uniform(0.36, 0.44) * h)
        p1 = (math.cos(a) * reach, math.sin(a) * reach, z1)
        tube(bm, col, p0, p1, base_r * 0.45, base_r * 0.18, 6, BARK, 0)
        blobs.append(((p1[0] * 1.1, p1[1] * 1.1, z1 + 0.06 * h), rng.uniform(0.17, 0.23) * h))
    for k in range(2):
        a = rng.uniform(0, TAU)
        blobs.append(((math.cos(a) * 0.12 * h, math.sin(a) * 0.12 * h, 0.9 * h), 0.18 * h))

    for center, rad in blobs:
        res = bmesh.ops.create_icosphere(bm, subdivisions=blob_subdiv, radius=rad,
                                         matrix=Matrix.Translation(center))
        off = rng.uniform(0, 100)
        faces = set()
        for v in res['verts']:
            d = (v.co - Vector(center)) / rad
            n = d.normalized()
            wob = 1.0 + 0.28 * fbm3(n.x * 1.6 + off, n.y * 1.6, n.z * 1.6, 3)
            v.co = Vector(center) + n * rad * wob
            light = clamp(0.5 + 0.5 * n.z, 0.0, 1.0)
            v[col] = (*mix3((0.030, 0.060, 0.018), (0.075, 0.120, 0.030), light), 1.0)
            faces.update(v.link_faces)
        for f in faces:
            f.material_index = 1
            f.smooth = True
    return bm_finish(bm, name, (mats["bark"], mats["leaves"]))


def make_rock(name, rng, subdiv, mats):
    bm, col = bm_new()
    bmesh.ops.create_icosphere(bm, subdivisions=subdiv, radius=1.0)
    off = rng.uniform(0, 100)
    planes = [(Vector((rng.uniform(-1, 1), rng.uniform(-1, 1), rng.uniform(-0.4, 1))).normalized(),
               rng.uniform(0.68, 0.86)) for _ in range(6)]
    for v in bm.verts:
        n = v.co.normalized()
        d = 1.0 + 0.30 * fbm3(n.x * 1.3 + off, n.y * 1.3, n.z * 1.3, 5) \
            + 0.06 * fbm3(n.x * 7.0 + off, n.y * 7.0, n.z * 7.0, 3)     # weathered pitting
        p = n * d
        for pn, o in planes:                        # chiselled facets
            dist = p.dot(pn)
            if dist > o:
                p -= pn * (dist - o) * 0.85
        p.z *= 0.62
        if p.z < -0.22:
            p.z = -0.22 + (p.z + 0.22) * 0.3
        v.co = p
        grey = 0.07 + 0.06 * (fbm3(p.x * 4 + off, p.y * 4, p.z * 4, 3) * 0.5 + 0.5)
        c = mix3((grey, grey * 0.96, grey * 0.92), (0.11, 0.12, 0.065), smoothstep(0.55, 0.85, n.z) * 0.6)
        c = mix3(c, (0.05, 0.048, 0.045), smoothstep(0.95, 0.8, d))
        v[col] = (*c, 1.0)
    for f in bm.faces:
        f.material_index = 0
        f.smooth = True
    return bm_finish(bm, name, (mats["rock"],))


def make_grass(name, rng, blades, tip_color, h_range, mats):
    bm, col = bm_new()
    root_c, mid_c = (0.025, 0.050, 0.012), (0.070, 0.140, 0.030)
    for _ in range(blades):
        a = rng.uniform(0, TAU)
        rr = 0.17 * math.sqrt(rng.random())
        rx, ry = rr * math.cos(a), rr * math.sin(a)
        lean_a = a + rng.uniform(-0.7, 0.7)
        dx, dy = math.cos(lean_a), math.sin(lean_a)
        px, py = -dy, dx
        h = rng.uniform(*h_range)
        lean = rng.uniform(0.12, 0.42) * h
        w = rng.uniform(0.028, 0.05)
        shade = rng.uniform(0.8, 1.2)
        segs = 4
        left, right = [], []
        for s in range(segs):
            t = s / segs
            cx, cy = rx + dx * lean * t * t, ry + dy * lean * t * t
            cz = -0.03 + h * (t - 0.25 * t * t * lean / h)
            hw = w * (1.0 - 0.85 * t) / 2.0
            c = mix3(root_c, mid_c, t * 2.0) if t < 0.5 else mix3(mid_c, tip_color, (t - 0.5) * 2.0)
            c = (c[0] * shade, c[1] * shade, c[2] * shade)
            left.append(point(bm, col, (cx - px * hw, cy - py * hw, cz), c))
            right.append(point(bm, col, (cx + px * hw, cy + py * hw, cz), c))
        tip = point(bm, col, (rx + dx * lean, ry + dy * lean, -0.03 + h * (1.0 - 0.25 * lean / h)),
                    (tip_color[0] * shade, tip_color[1] * shade, tip_color[2] * shade))
        for s in range(segs - 1):
            f = bm.faces.new((left[s], right[s], right[s + 1], left[s + 1]))
            f.smooth = True
        f = bm.faces.new((left[-1], right[-1], tip))
        f.smooth = True
    return bm_finish(bm, name, (mats["grass"],))


def make_reeds(name, rng, stalks, cattails, mats):
    """Shoreline reed clump; everything is slot 0 (foliage — it sways in the app)."""
    bm, col = bm_new()
    base_c, mid_c, tip_c = (0.035, 0.055, 0.018), (0.075, 0.115, 0.032), (0.17, 0.18, 0.07)
    for _ in range(stalks):
        a = rng.uniform(0, TAU)
        rr = 0.22 * math.sqrt(rng.random())
        rx, ry = rr * math.cos(a), rr * math.sin(a)
        lean_a = rng.uniform(0, TAU)
        dx, dy = math.cos(lean_a), math.sin(lean_a)
        px, py = -dy, dx
        h = rng.uniform(0.9, 1.7)
        lean = rng.uniform(0.04, 0.22) * h
        w = rng.uniform(0.025, 0.04)
        left, right = [], []
        for s in range(3):
            t = s / 3
            cx, cy = rx + dx * lean * t * t, ry + dy * lean * t * t
            hw = w * (1.0 - 0.7 * t) / 2.0
            c = mix3(base_c, mid_c, t * 1.5) if t < 0.67 else mix3(mid_c, tip_c, (t - 0.67) * 3.0)
            left.append(point(bm, col, (cx - px * hw, cy - py * hw, -0.3 + h * t), c))
            right.append(point(bm, col, (cx + px * hw, cy + py * hw, -0.3 + h * t), c))
        tip = point(bm, col, (rx + dx * lean, ry + dy * lean, -0.3 + h), tip_c)
        for s in range(2):
            bm.faces.new((left[s], right[s], right[s + 1], left[s + 1])).smooth = True
        bm.faces.new((left[-1], right[-1], tip)).smooth = True
    for _ in range(cattails):
        a = rng.uniform(0, TAU)
        rr = 0.15 * math.sqrt(rng.random())
        x, y = rr * math.cos(a), rr * math.sin(a)
        h = rng.uniform(1.2, 1.7)
        tube(bm, col, (x, y, -0.3), (x, y, h - 0.3), 0.012, 0.009, 3, mid_c, 0)
        tube(bm, col, (x, y, h - 0.25), (x, y, h), 0.035, 0.03, 5, (0.10, 0.055, 0.025), 0, caps=True)
    return bm_finish(bm, name, (mats["grass"],))


def make_boat(name, mats):
    """Wooden rowboat, ~3.6 m, bow along +Y, waterline at z = 0."""
    bm, col = bm_new()
    length, stations = 3.6, 10
    wood = (0.24, 0.13, 0.065)
    sections = []
    for i in range(stations + 1):
        s = i / stations                              # 0 = transom stern, 1 = bow
        y = (s - 0.5) * length
        if s >= 0.42:
            w = 0.7 * math.sqrt(max(0.0, 1.0 - ((s - 0.42) / 0.58) ** 2))
        else:
            w = 0.7 * (1.0 - 0.35 * ((0.42 - s) / 0.42) ** 2)
        w = max(w, 0.015)
        sheer = 0.38 + (0.24 * ((s - 0.42) / 0.58) ** 2 if s >= 0.42 else 0.06 * ((0.42 - s) / 0.42))
        keel = -0.17 * (0.55 + 0.45 * math.sin(math.pi * s))
        c = mix3(wood, (0.33, 0.20, 0.10), 0.5 + 0.5 * math.sin(i * 1.7))
        sections.append([point(bm, col, (sx * w, y, z), c) for sx, z in
                         ((-1.0, sheer), (-0.82, 0.02), (0.0, keel), (0.82, 0.02), (1.0, sheer))])
    for a, b in zip(sections, sections[1:]):
        for k in range(4):
            f = bm.faces.new((a[k], b[k], b[k + 1], a[k + 1]))
            f.material_index = 0
            f.smooth = True
    transom = bm.faces.new(sections[0])
    transom.smooth = False
    for ty in (-0.35, 0.45):                          # thwarts
        box(bm, col, at(0, ty, 0.24), (1.2, 0.22, 0.05), (0.30, 0.19, 0.10), 0)
    for side in (-1, 1):                              # oars resting along the gunwales
        box(bm, col, at(side * 0.5, 0.1, 0.36, rz=side * 0.12), (0.06, 2.6, 0.04), (0.36, 0.25, 0.13), 0)
    return bm_finish(bm, name, (mats["wood"],))


def build_prototypes(mats, coll_root, rng, rng_x):
    """Instance sources. Child order = instance index used by the scatter attribute."""
    sets = {}
    for key, builders in (
        ("trees", (("LV_0_PineTall", lambda: make_pine("LV_0_PineTall", rng, 15.0, 3.0, 13, 26, mats)),
                   ("LV_1_PineFull", lambda: make_pine("LV_1_PineFull", rng, 11.0, 3.4, 10, 22, mats)),
                   ("LV_2_Broadleaf", lambda: make_broadleaf("LV_2_Broadleaf", rng, 11.0, mats)),
                   ("LV_3_PineLOD", lambda: make_pine("LV_3_PineLOD", rng, 14.0, 3.1, 5, 8, mats)))),
        ("rocks", (("LV_0_Boulder", lambda: make_rock("LV_0_Boulder", rng, 5, mats)),
                   ("LV_1_Rock", lambda: make_rock("LV_1_Rock", rng, 4, mats)),
                   ("LV_2_Rock", lambda: make_rock("LV_2_Rock", rng, 4, mats)))),
        ("grass", (("LV_0_GrassLush", lambda: make_grass("LV_0_GrassLush", rng, 18, (0.16, 0.22, 0.05),
                                                          (0.16, 0.38), mats)),
                   ("LV_1_GrassDry", lambda: make_grass("LV_1_GrassDry", rng, 14, (0.30, 0.25, 0.10),
                                                         (0.25, 0.50), mats)))),
        ("reeds", (("LV_0_Reeds", lambda: make_reeds("LV_0_Reeds", rng_x, 10, 2, mats)),)),
    ):
        coll = new_collection("LV_Proto_" + key, coll_root)
        objs = []
        for name, build in builders:
            ob = bpy.data.objects.new(name, build())
            coll.objects.link(ob)
            objs.append(ob)
        sets[key] = (coll, objs)
    return sets


# ── Scatter (geometry-nodes instancing, exports as GPU instances) ───────────

def scatter_group(name, coll):
    ng = bpy.data.node_groups.new(name, 'GeometryNodeTree')
    ng.interface.new_socket("Geometry", in_out='INPUT', socket_type='NodeSocketGeometry')
    ng.interface.new_socket("Geometry", in_out='OUTPUT', socket_type='NodeSocketGeometry')
    N, L = ng.nodes, ng.links
    gi, go = N.new('NodeGroupInput'), N.new('NodeGroupOutput')
    info = N.new('GeometryNodeCollectionInfo')
    info.inputs['Collection'].default_value = coll
    info.inputs['Separate Children'].default_value = True
    info.inputs['Reset Children'].default_value = True
    inst = N.new('GeometryNodeInstanceOnPoints')
    inst.inputs['Pick Instance'].default_value = True

    def attr(attr_name, dtype):
        n = N.new('GeometryNodeInputNamedAttribute')
        n.data_type = dtype
        n.inputs['Name'].default_value = attr_name
        return n.outputs['Attribute']

    rot = attr("lv_rot", 'FLOAT_VECTOR')
    if hasattr(bpy.types, 'FunctionNodeEulerToRotation'):
        e2r = N.new('FunctionNodeEulerToRotation')
        L.new(rot, e2r.inputs[0])
        rot = e2r.outputs[0]
    L.new(gi.outputs[0], inst.inputs['Points'])
    L.new(info.outputs['Instances'], inst.inputs['Instance'])
    L.new(attr("lv_variant", 'INT'), inst.inputs['Instance Index'])
    L.new(rot, inst.inputs['Rotation'])
    L.new(attr("lv_scale", 'FLOAT_VECTOR'), inst.inputs['Scale'])
    L.new(inst.outputs['Instances'], go.inputs[0])
    return ng


def make_points(name, items, proto_coll, coll):
    me = bpy.data.meshes.new(name)
    me.vertices.add(len(items))
    me.vertices.foreach_set("co", [c for it in items for c in it[0]])
    me.attributes.new("lv_rot", 'FLOAT_VECTOR', 'POINT').data.foreach_set(
        "vector", [c for it in items for c in it[1]])
    me.attributes.new("lv_scale", 'FLOAT_VECTOR', 'POINT').data.foreach_set(
        "vector", [c for it in items for c in it[2]])
    me.attributes.new("lv_variant", 'INT', 'POINT').data.foreach_set("value", [it[3] for it in items])
    ob = bpy.data.objects.new(name, me)
    coll.objects.link(ob)
    ob.modifiers.new("LV_Scatter", 'NODES').node_group = scatter_group(name + "_GN", proto_coll)
    return ob


def tree_spot(rng, x, y):
    """Terrain height if a tree may grow at (x, y), else None."""
    if lake_e(x, y) < 1.05 or excluded(x, y, 4.0):
        return None
    z = height(x, y)
    if z < 1.2:
        return None
    treeline = 300.0 + 40.0 * fbm(x / 250.0, y / 250.0, 2, 77.0)
    p = (0.03 + 0.97 * forest(x, y)) * (1.0 - smoothstep(treeline - 60.0, treeline, z))
    if rng.random() > p or slope_at(x, y) > 38.0:
        return None
    return z


def tree_item(rng, x, y, z, variant, s):
    sxy = s * rng.uniform(0.88, 1.12)
    return ((x, y, z - 0.25),
            (rng.uniform(-0.04, 0.04), rng.uniform(-0.04, 0.04), rng.uniform(0, TAU)),
            (sxy, sxy, s), variant)


def scatter_trees(rng):
    items, pines, broad, tries = [], 0, 0, 0
    while (pines < PINE_COUNT or broad < BROADLEAF_COUNT) and tries < 80000:
        tries += 1
        a = rng.uniform(0, TAU)
        r = 20.0 + (NEAR_FOREST_RADIUS - 20.0) * rng.random() ** 1.2
        x, y = CAM_POS[0] + r * math.cos(a), CAM_POS[1] + r * math.sin(a)
        if near_path(x, y, 12.0) or near_path(x, y, 55.0, CAM_YAW, 50.0):
            continue                                 # keep the foreground view open
        if near_path(x, y, 60.0, SUN_BEARING, 40.0):
            continue                                 # ...and sunlit
        z = tree_spot(rng, x, y)
        if z is None:
            continue
        if z < 40.0 and broad < BROADLEAF_COUNT and rng.random() < 0.35:
            items.append(tree_item(rng, x, y, z, 2, rng.uniform(0.75, 1.25)))
            broad += 1
        elif pines < PINE_COUNT:
            items.append(tree_item(rng, x, y, z, 0 if rng.random() < 0.55 else 1, rng.uniform(0.6, 1.35)))
            pines += 1

    far, tries = 0, 0                                # LOD band: area-uniform out to the mountains
    r0, r1 = NEAR_FOREST_RADIUS - 40.0, FAR_FOREST_RADIUS
    while far < FAR_PINE_COUNT and tries < 120000:
        tries += 1
        a = rng.uniform(0, TAU)
        r = math.sqrt(r0 * r0 + rng.random() * (r1 * r1 - r0 * r0))
        x, y = CAM_POS[0] + r * math.cos(a), CAM_POS[1] + r * math.sin(a)
        z = tree_spot(rng, x, y)
        if z is not None:
            items.append(tree_item(rng, x, y, z, 3, rng.uniform(0.7, 1.4)))
            far += 1
    return items


def scatter_rocks(rng):
    items, tries = [], 0
    while len(items) < ROCK_COUNT and tries < 40000:
        tries += 1
        if rng.random() < 0.4:                       # shoreline boulders
            t = rng.uniform(0, TAU)
            e = rng.uniform(0.9, 1.15)
            x, y = LAKE_C[0] + LAKE_R[0] * e * math.cos(t), LAKE_C[1] + LAKE_R[1] * e * math.sin(t)
            if not 0.95 < lake_e(x, y) < 1.2:
                continue
            s = rng.uniform(0.5, 1.8)
        else:
            a = rng.uniform(0, TAU)
            r = 8.0 + 700.0 * rng.random() ** 1.5
            x, y = CAM_POS[0] + r * math.cos(a), CAM_POS[1] + r * math.sin(a)
            if lake_e(x, y) < 1.0:
                continue
            if near_path(x, y, 35.0, CAM_YAW, 40.0):
                continue                             # nothing big right in front of the lens
            steep = slope_at(x, y) > 16.0
            if not ((steep and rng.random() < 0.6) or (r < 120.0 and rng.random() < 0.15)):
                continue
            s = rng.uniform(0.8, 3.5) if r > 60.0 else rng.uniform(0.4, 1.2)
        if excluded(x, y, s):
            continue
        z = height(x, y)
        u = rng.random()
        variant = 0 if u < 0.2 else 1 if u < 0.6 else 2
        items.append(((x, y, z - 0.3 * s),
                      (rng.uniform(-0.3, 0.3), rng.uniform(-0.3, 0.3), rng.uniform(0, TAU)),
                      (s * rng.uniform(0.8, 1.25), s * rng.uniform(0.8, 1.25), s), variant))
    return items


def scatter_grass(rng):
    items, tries = [], 0
    while len(items) < GRASS_COUNT and tries < 150000:
        tries += 1
        a = rng.uniform(0, TAU)
        r = 2.0 + (GRASS_RADIUS - 2.0) * rng.random() ** 0.85
        x, y = CAM_POS[0] + r * math.cos(a), CAM_POS[1] + r * math.sin(a)
        if rng.random() > 1.0 - smoothstep(GRASS_RADIUS * 0.65, GRASS_RADIUS, r):
            continue
        if lake_e(x, y) < 0.99 or excluded(x, y, 0.4):
            continue
        z = height(x, y)
        if z < 0.55 or fbm(x / 14.0, y / 14.0, 2, 91.0) < -0.25 or slope_at(x, y) > 30.0:
            continue
        variant = 1 if fbm(x / 45.0, y / 45.0, 2, 33.0) > 0.18 else 0
        s = rng.uniform(0.7, 1.35)
        items.append(((x, y, z),
                      (rng.uniform(-0.12, 0.12), rng.uniform(-0.12, 0.12), rng.uniform(0, TAU)),
                      (s, s, s * rng.uniform(0.85, 1.2)), variant))
    return items


def scatter_reeds(rng):
    items, tries = [], 0
    while len(items) < REED_COUNT and tries < 60000:
        tries += 1
        t = rng.uniform(0, TAU)
        e = rng.uniform(0.82, 1.02)
        x, y = LAKE_C[0] + LAKE_R[0] * e * math.cos(t), LAKE_C[1] + LAKE_R[1] * e * math.sin(t)
        z = height(x, y)
        if not -0.7 < z < 0.35 or excluded(x, y, 1.0) or near_path(x, y, 40.0, CAM_YAW, 35.0):
            continue
        if fbm(x / 18.0, y / 18.0, 2, 123.0) < -0.1:     # patchy stands, not a continuous hedge
            continue
        s = rng.uniform(0.75, 1.3)
        items.append(((x, y, z),
                      (rng.uniform(-0.08, 0.08), rng.uniform(-0.08, 0.08), rng.uniform(0, TAU)),
                      (s, s, s * rng.uniform(0.85, 1.2)), 0))
    return items


# ── Dock & cabin ─────────────────────────────────────────────────────────────

def find_spot(bearing_range, dist_range, accept, score):
    best, best_score = None, None
    for b in range(int(bearing_range[0]), int(bearing_range[1]) + 1):
        for d in range(int(dist_range[0]), int(dist_range[1]) + 1):
            x = CAM_POS[0] + d * math.sin(math.radians(b))
            y = CAM_POS[1] + d * math.cos(math.radians(b))
            if accept(x, y):
                sc = score(b, d)
                if best_score is None or sc < best_score:
                    best, best_score = (x, y), sc
    return best


def build_dock(mats, coll, rng):
    target_b = CAM_YAW + 16.0
    spot = find_spot((target_b - 12, target_b + 12), (12, 60),
                     lambda x, y: 0.4 < height(x, y) < 0.9 and lake_e(x, y) < 1.05,
                     lambda b, d: abs(b - target_b) * 2.0 + abs(d - 24.0))
    if spot is None:
        return None
    sx, sy = spot
    dx, dy = LAKE_C[0] - sx, LAKE_C[1] - sy
    ln = math.hypot(dx, dy)
    dx, dy = dx / ln, dy / ln
    length, width = 20.0, 2.2
    ox, oy = sx - dx * 0.5, sy - dy * 0.5            # start just on the bank

    bm, col = bm_new()
    deck_z = 0.0
    y = 0.0
    while y < length:
        c = rng.uniform(0.75, 1.15)
        box(bm, col, at(0, y + 0.11, deck_z), (width, 0.22, 0.05), (0.20 * c, 0.13 * c, 0.08 * c), 0)
        y += 0.28
    for xs in (-0.8, 0.8):
        box(bm, col, at(xs, length / 2, deck_z - 0.14), (0.12, length, 0.2), (0.12, 0.08, 0.05), 0)
    for k in range(int(length / 2.5) + 1):
        for xs in (-1.0, 1.0):
            tube(bm, col, (xs, k * 2.5, -3.0), (xs, k * 2.5, 0.25), 0.11, 0.10, 8,
                 (0.10, 0.075, 0.05), 0, caps=True)
    me = bm_finish(bm, "LV_Dock", (mats["wood"],))
    ob = bpy.data.objects.new("LV_Dock", me)
    ob.location = (ox, oy, WATER_Z + 0.75)
    ob.rotation_euler = (0.0, 0.0, math.atan2(-dx, dy))
    coll.objects.link(ob)
    for k in range(0, int(length) + 1, 3):
        EXCLUDE.append((ox + dx * k, oy + dy * k, 2.0))
    return ob


def build_cabin(mats, coll, rng):
    target_b = CAM_YAW - 24.0
    spot = find_spot((target_b - 25, target_b + 25), (40, 160),
                     lambda x, y: 1.06 < lake_e(x, y) < 1.5 and 1.0 < height(x, y) < 8.0
                     and slope_at(x, y) < 9.0,
                     lambda b, d: abs(b - target_b) + abs(d - 70.0) * 0.5)
    if spot is None:
        return None
    cx, cy = spot
    L_, W_, wall_h, lr = 7.0, 5.0, 2.7, 0.16
    face = math.atan2(-(LAKE_C[0] - cx), LAKE_C[1] - cy)   # front (+Y local) faces the lake
    corners = [(cx + px * math.cos(face) - py * math.sin(face), cy + px * math.sin(face) + py * math.cos(face))
               for px in (-L_ / 2, L_ / 2) for py in (-W_ / 2, W_ / 2)]
    ground = min(height(x, y) for x, y in corners)
    top = max(height(x, y) for x, y in corners)

    bm, col = bm_new()
    logs = (0.20, 0.12, 0.07)
    box(bm, col, at(0, 0, -0.5 + (top - ground) / 2), (L_ + 0.4, W_ + 0.4, 1.0 + (top - ground)),
        (0.16, 0.15, 0.14), 2)
    base = top - ground + 0.05
    courses = int(wall_h / (2 * lr))
    for i in range(courses):
        z = base + lr + i * 2 * lr
        c = rng.uniform(0.85, 1.1)
        lc = (logs[0] * c, logs[1] * c, logs[2] * c)
        for side in (-1, 1):                          # interlocked corners: side logs sit half a log higher
            tube(bm, col, (-L_ / 2 - 0.35, side * W_ / 2, z), (L_ / 2 + 0.35, side * W_ / 2, z),
                 lr, lr, 10, lc, 0, caps=True)
            tube(bm, col, (side * L_ / 2, -W_ / 2 - 0.35, z + lr), (side * L_ / 2, W_ / 2 + 0.35, z + lr),
                 lr, lr, 10, lc, 0, caps=True)
    eave = base + wall_h
    ridge_h = 1.9
    for side in (-1, 1):                              # gables
        x = side * L_ / 2
        vs = [point(bm, col, (x, -W_ / 2, eave), logs), point(bm, col, (x, W_ / 2, eave), logs),
              point(bm, col, (x, 0.0, eave + ridge_h), logs)]
        f = bm.faces.new(vs if side > 0 else list(reversed(vs)))
        f.material_index = 0
        f.smooth = False
    pitch = math.atan2(ridge_h, W_ / 2)
    run = (W_ / 2 + 0.6) / math.cos(pitch)
    for side in (-1, 1):
        cyy = side * (W_ / 2 + 0.6) / 2
        czz = eave + ridge_h - (W_ / 2 + 0.6) / 2 * math.tan(pitch)
        box(bm, col, at(0, cyy, czz + 0.12, rx=-side * pitch), (L_ + 1.0, run, 0.18), (0.075, 0.058, 0.05), 1)
    box(bm, col, at(L_ / 2 - 1.3, -0.9, eave + 1.2), (0.75, 0.75, ridge_h + 1.8), (0.15, 0.14, 0.13), 2)
    fy = W_ / 2 + 0.2
    box(bm, col, at(0, fy, base + 1.0), (1.0, 0.1, 2.0), (0.06, 0.04, 0.025), 0)
    for wx in (-2.2, 2.2):
        box(bm, col, at(wx, fy, base + 1.45), (0.9, 0.08, 0.8), (1.0, 0.9, 0.8), 3)
    box(bm, col, at(0, W_ / 2 + 1.0, base - 0.05), (L_, 1.6, 0.15), (0.18, 0.12, 0.07), 0)

    me = bm_finish(bm, "LV_Cabin", (mats["wood"], mats["roof"], mats["stone"], mats["window"]))
    ob = bpy.data.objects.new("LV_Cabin", me)
    ob.location = (cx, cy, ground)
    ob.rotation_euler = (0.0, 0.0, face)
    ob["lv_chimney"] = (L_ / 2 - 1.3, -0.9, eave + 1.2 + (ridge_h + 1.8) / 2)   # local chimney top
    coll.objects.link(ob)
    EXCLUDE.append((cx, cy, 6.5))
    EXCLUDE.append((cx - 2.5 * math.sin(face), cy + 2.5 * math.cos(face), 3.5))
    return ob


def build_boat(mats, coll):
    ob = bpy.data.objects.new("LV_Boat", make_boat("LV_Boat", mats))
    ob.location = (BOAT_POS[0], BOAT_POS[1], WATER_Z - 0.06)
    ob.rotation_euler = (0.0, 0.0, math.radians(BOAT_POS[2]))
    coll.objects.link(ob)
    return ob


# ── Camera, light, world, render ─────────────────────────────────────────────

def build_camera(scene, coll):
    cam = bpy.data.cameras.new("LV_Camera")
    cam.lens = CAM_LENS
    cam.clip_start = 0.1
    cam.clip_end = 8000.0
    ob = bpy.data.objects.new("LV_Camera", cam)
    target = bpy.data.objects.new("LV_CameraTarget", None)
    coll.objects.link(ob)
    coll.objects.link(target)
    con = ob.constraints.new('TRACK_TO')
    con.target = target
    con.track_axis = 'TRACK_NEGATIVE_Z'
    con.up_axis = 'UP_Y'

    b = math.radians(CAM_YAW)
    side = Vector((math.cos(b), -math.sin(b), 0.0))     # dolly to the right of the view
    for frame, shift in ((1, 0.0), (FRAMES // 2, CAM_DOLLY), (FRAMES, 0.0)):
        x, y = CAM_POS[0] + side.x * shift, CAM_POS[1] + side.y * shift
        z = height(x, y) + CAM_EYE
        ob.location = (x, y, z)
        ob.keyframe_insert("location", frame=frame)
        dist = 400.0
        target.location = (x + math.sin(b) * dist, y + math.cos(b) * dist,
                           z + dist * math.tan(math.radians(CAM_PITCH)))
        target.keyframe_insert("location", frame=frame)
    scene.camera = ob
    scene.frame_start, scene.frame_end = 1, FRAMES
    scene.frame_set(1)
    return ob


def build_lighting(scene, coll):
    sun = bpy.data.lights.new("LV_Sun", 'SUN')
    sun.energy = SUN_STRENGTH
    sun.color = SUN_COLOR
    sun.angle = math.radians(0.8)
    ob = bpy.data.objects.new("LV_Sun", sun)
    ob.rotation_euler = sun_vector().to_track_quat('Z', 'Y').to_euler()
    coll.objects.link(ob)

    world = bpy.data.worlds.new("LV_World")
    try:
        world.use_nodes = True
    except (AttributeError, TypeError):
        pass
    nt = world.node_tree
    bg = next(n for n in nt.nodes if n.type == 'BACKGROUND')
    bg.inputs['Strength'].default_value = SKY_STRENGTH
    sky = nt.nodes.new('ShaderNodeTexSky')
    configure_sky(sky)
    nt.links.new(sky.outputs['Color'], bg.inputs['Color'])
    scene.world = world


def configure_render(scene):
    scene.render.engine = 'BLENDER_EEVEE'
    scene.render.resolution_x, scene.render.resolution_y = 1920, 1080
    scene.render.resolution_percentage = 100
    scene.render.fps = 24
    ee = scene.eevee
    for prop, value in (("taa_render_samples", 64), ("taa_samples", 16), ("use_raytracing", True),
                        ("use_shadows", True), ("use_fast_gi", True), ("clamp_surface_indirect", 10.0)):
        if hasattr(ee, prop):
            setattr(ee, prop, value)
    try:
        scene.view_settings.look = 'AgX - Medium High Contrast'
    except TypeError:
        pass


def frame_viewports(scene):
    screen = getattr(bpy.context, "screen", None)
    if screen is None:
        return
    for area in screen.areas:
        if area.type == 'VIEW_3D':
            space = area.spaces.active
            space.clip_start, space.clip_end = 0.1, 10000.0
            space.region_3d.view_perspective = 'CAMERA'


# ── Phone export (app GPU benchmark) ─────────────────────────────────────────
#
# Little-endian binary read by GpuSceneAsset.kt:
#   "LVSC", u32 version
#   f32 lakeCx, lakeCy, lakeRx, lakeRy, waterZ
#   u32 anchorCount, per anchor: name[16], f32 x, y, z
#   u32 meshCount, per mesh: name[16], u32 vertexCount, u32 indexCount, u32 polarSegments,
#       u32 polarRings, f32 height, vertices, u16 indices (padded to 4 bytes)
#     vertex = f32 x, y, z | i8 nx, ny, nz, 0 | u8 r, g, b (sRGB), class
#     polar meshes (terrain) carry no indices: centre vertex + rings × segments
#   u32 groupCount, per group: name[16], u32 meshIndex, u32 count, count × f32 {pos, euler, scale}
#
# The phone draws every instance every frame (no culling) so the per-frame workload stays
# constant while the camera moves — the benchmark's FPS must not depend on the view.

CLASS_SOLID, CLASS_GLOW, CLASS_FOLIAGE = 0, 128, 255
ASSET_VERSION = 1


def mesh_payload(me, foliage=(), glow=(), matrix=None, split=False):
    """Mesh → ([(pos, normal, linear rgb, class)], indices). `split` keeps flat faces flat."""
    me.calc_loop_triangles()
    tris = me.loop_triangles
    nt, nv = len(tris), len(me.vertices)
    tv = [0] * (3 * nt)
    tris.foreach_get("vertices", tv)
    tm = [0] * nt
    tris.foreach_get("material_index", tm)
    co = [0.0] * (3 * nv)
    me.vertices.foreach_get("co", co)
    col = [1.0] * (4 * nv)
    attr = me.color_attributes.get("Col")
    if attr is not None:
        attr.data.foreach_get("color", col)

    def klass(mat):
        return CLASS_FOLIAGE if mat in foliage else CLASS_GLOW if mat in glow else CLASS_SOLID

    if split:
        tl = [0] * (3 * nt)
        tris.foreach_get("loops", tl)
        cn = [0.0] * (3 * len(me.loops))
        me.corner_normals.foreach_get("vector", cn)
        refs = [(tv[k], cn[3 * tl[k]:3 * tl[k] + 3], klass(tm[k // 3])) for k in range(3 * nt)]
        indices = list(range(3 * nt))
    else:
        vn = [0.0] * (3 * nv)
        me.vertex_normals.foreach_get("vector", vn)
        cls = [CLASS_SOLID] * nv
        for k in range(3 * nt):
            c = klass(tm[k // 3])
            if c != CLASS_SOLID:
                cls[tv[k]] = c
        refs = [(v, vn[3 * v:3 * v + 3], cls[v]) for v in range(nv)]
        indices = tv
    rot = matrix.to_3x3() if matrix is not None else None
    verts = []
    for v, n, c in refs:
        p, nn = Vector(co[3 * v:3 * v + 3]), Vector(n)
        if matrix is not None:
            p, nn = matrix @ p, (rot @ nn).normalized()
        verts.append((p, nn, col[4 * v:4 * v + 3], c))
    return verts, indices


def name16(s):
    b = s.encode("ascii")[:16]
    return b + b"\0" * (16 - len(b))


def pack_mesh(name, verts, indices, polar=None):
    def q8(x):
        return max(-127, min(127, round(x * 127.0)))

    def srgb8(x):
        return round(clamp(x, 0.0, 1.0) ** (1.0 / 2.2) * 255.0)

    height = max(p.z for p, _, _, _ in verts)
    out = bytearray(name16(name))
    out += struct.pack("<4If", len(verts), 0 if polar else len(indices),
                       polar[0] if polar else 0, polar[1] if polar else 0, height)
    for p, n, c, k in verts:
        out += struct.pack("<3f4b4B", p.x, p.y, p.z, q8(n[0]), q8(n[1]), q8(n[2]), 0,
                           srgb8(c[0]), srgb8(c[1]), srgb8(c[2]), k)
    if not polar:
        if max(indices) > 0xFFFF:
            raise ValueError(name + ": too many vertices for u16 indices")
        out += struct.pack("<%dH" % len(indices), *indices)
        if len(indices) % 2:
            out += b"\0\0"
    return out, len(indices) // 3 if not polar else 2 * polar[0] * polar[1] - polar[0]


def scatter_arrays(obj_name):
    me = bpy.data.objects[obj_name].data
    n = len(me.vertices)
    co, rot, scl, var = [0.0] * (3 * n), [0.0] * (3 * n), [0.0] * (3 * n), [0] * n
    me.vertices.foreach_get("co", co)
    me.attributes["lv_rot"].data.foreach_get("vector", rot)
    me.attributes["lv_scale"].data.foreach_get("vector", scl)
    me.attributes["lv_variant"].data.foreach_get("value", var)
    return [(co[3 * i:3 * i + 3], rot[3 * i:3 * i + 3], scl[3 * i:3 * i + 3], var[i]) for i in range(n)]


def export_app_scene(path, mats, cabin, dock, boat):
    """Write the phone version: same layout and instances, lighter meshes, no grass."""
    t0 = time.time()
    rng = random.Random(SEED + 101)                  # app meshes never disturb the Blender scene
    temp = []

    def tmp(me):
        temp.append(me)
        return me

    terrain, rings = terrain_mesh("LV_tmp_terrain", APP_SEGMENTS)
    temp.append(terrain)
    meshes = [  # (name, payload) — group names below refer to these
        ("terrain", mesh_payload(terrain), (APP_SEGMENTS, rings)),
        ("pine_a", mesh_payload(tmp(make_pine("LV_tmp_pa", rng, 15.0, 3.0, 7, 8, mats, trunk_seg=6)),
                                foliage={1}), None),
        ("pine_b", mesh_payload(tmp(make_pine("LV_tmp_pb", rng, 11.0, 3.4, 6, 8, mats, trunk_seg=6)),
                                foliage={1}), None),
        ("broadleaf", mesh_payload(tmp(make_broadleaf("LV_tmp_bl", rng, 11.0, mats, blob_subdiv=2)),
                                   foliage={1}), None),
        ("pine_far", mesh_payload(tmp(make_pine_lod("LV_tmp_pf", rng, 14.0, 3.1, 4, 8, mats)),
                                  foliage={1}), None),
        ("rock_0", mesh_payload(tmp(make_rock("LV_tmp_r0", rng, 3, mats))), None),
        ("rock_1", mesh_payload(tmp(make_rock("LV_tmp_r1", rng, 3, mats))), None),
        ("rock_2", mesh_payload(tmp(make_rock("LV_tmp_r2", rng, 3, mats))), None),
        ("reeds", mesh_payload(bpy.data.objects["LV_0_Reeds"].data, foliage={0}), None),
        ("boat", mesh_payload(boat.data, split=True), None),
        ("cabin", mesh_payload(cabin.data, glow={3}, matrix=cabin.matrix_world, split=True), None),
        ("dock", mesh_payload(dock.data, matrix=dock.matrix_world, split=True), None),
    ]
    mesh_index = {name: i for i, (name, _, _) in enumerate(meshes)}
    groups = {}
    for obj, names in (("LV_Scatter_trees", ("pine_a", "pine_b", "broadleaf", "pine_far")),
                       ("LV_Scatter_rocks", ("rock_0", "rock_1", "rock_2")),
                       ("LV_Scatter_reeds", ("reeds",))):
        for pos, rot, scl, var in scatter_arrays(obj):
            groups.setdefault(names[var], []).append((*pos, *rot, *scl))

    chimney = cabin.matrix_world @ Vector(cabin["lv_chimney"])
    anchors = (("chimney", chimney), ("dock_end", dock.matrix_world @ Vector((0.0, 20.0, 0.0))),
               ("boat", Vector((BOAT_POS[0], BOAT_POS[1], WATER_Z))))

    out = bytearray(b"LVSC") + struct.pack("<I", ASSET_VERSION)
    out += struct.pack("<5f", LAKE_C[0], LAKE_C[1], LAKE_R[0], LAKE_R[1], WATER_Z)
    out += struct.pack("<I", len(anchors))
    for name, p in anchors:
        out += name16(name) + struct.pack("<3f", p.x, p.y, p.z)
    out += struct.pack("<I", len(meshes))
    mesh_tris = {}
    for name, (verts, indices), polar in meshes:
        blob, tris = pack_mesh(name, verts, indices, polar)
        out += blob
        mesh_tris[name] = tris
    out += struct.pack("<I", len(groups))
    for name, rows in groups.items():
        out += name16(name) + struct.pack("<2I", mesh_index[name], len(rows))
        out += struct.pack("<%df" % (9 * len(rows)), *[v for row in rows for v in row])
    with open(path, "wb") as f:
        f.write(out)
    for me in temp:
        bpy.data.meshes.remove(me)

    print("app scene → %s (%.1f MB, %.1fs)" % (path, len(out) / 1e6, time.time() - t0))
    for name, (verts, _), _ in meshes:
        n = len(groups.get(name, ())) or 1
        print("  %-10s %6d verts %6d tris%s" % (name, len(verts), mesh_tris[name],
                                                " x%d = %s" % (n, f"{mesh_tris[name] * n:,}") if name in groups else ""))
    print(f"  main-pass triangles ≈ {sum(mesh_tris[k] * (len(groups.get(k, ())) or 1) for k in mesh_tris):,}")


# ── Build ────────────────────────────────────────────────────────────────────

def tri_count(me):
    me.calc_loop_triangles()
    return len(me.loop_triangles)


def main(save_path=None, export_path=None):
    t0 = time.time()
    rng = random.Random(SEED)
    rng_x = random.Random(SEED + 7)                  # reeds — added later, keeps older placements stable
    EXCLUDE.clear()
    scene = reset_scene()
    root = new_collection("LV_LakeValley", scene.collection)
    env = new_collection("LV_Environment", root)
    props = new_collection("LV_Props", root)
    scatter = new_collection("LV_Scatter", root)
    proto_root = new_collection("LV_Prototypes", root)

    mats = build_materials()
    terrain = build_terrain(mats, env)
    water = build_water(mats, env)
    t_terrain = time.time() - t0
    dock = build_dock(mats, props, rng)
    cabin = build_cabin(mats, props, rng)
    boat = build_boat(mats, props)
    protos = build_prototypes(mats, proto_root, rng, rng_x)

    layer = bpy.context.view_layer.layer_collection.children["LV_LakeValley"].children["LV_Prototypes"]
    layer.exclude = True                             # sources render only through the instancers

    counts = {}
    for key, items in (("trees", scatter_trees(rng)), ("rocks", scatter_rocks(rng)),
                       ("grass", scatter_grass(rng)), ("reeds", scatter_reeds(rng_x))):
        coll, objs = protos[key]
        make_points("LV_Scatter_" + key, items, coll, scatter)
        per_variant = [0] * len(objs)
        for it in items:
            per_variant[it[3]] += 1
        counts[key] = list(zip(objs, per_variant))

    build_camera(scene, props)
    build_lighting(scene, env)
    configure_render(scene)
    frame_viewports(scene)

    tris = {"terrain": tri_count(terrain.data), "water": tri_count(water.data),
            "dock": tri_count(dock.data) if dock else 0, "cabin": tri_count(cabin.data) if cabin else 0,
            "boat": tri_count(boat.data)}
    for key, rows in counts.items():
        tris[key] = sum(tri_count(ob.data) * n for ob, n in rows)
    total = sum(tris.values())
    print("LakeValley built in %.1fs (terrain %.1fs)" % (time.time() - t0, t_terrain))
    for key, rows in counts.items():
        print("  %-6s %s" % (key, ", ".join("%s x%d (%d tris)" % (ob.name, n, tri_count(ob.data))
                                           for ob, n in rows)))
    print("  triangles: " + ", ".join("%s %s" % (k, f"{v:,}") for k, v in tris.items()))
    print(f"  total ≈ {total:,} triangles per full-scene draw")
    scene["lv_triangles"] = total

    if export_path:
        export_app_scene(export_path, mats, cabin, dock, boat)
    if save_path:
        bpy.ops.wm.save_as_mainfile(filepath=save_path)
        print("saved", save_path)
    return tris


if __name__ == "__main__":
    argv = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
    main(argv[argv.index("--save") + 1] if "--save" in argv else None,
         argv[argv.index("--export") + 1] if "--export" in argv else None)
