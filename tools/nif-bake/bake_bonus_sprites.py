# Offline Blender batch baker: render each Civ4 bonus's .nif model to a transparent PNG icon,
# for the web map's resource layer (docs/ported-terrain-art-system.md §10 sprite baker).
#
#   blender --background --python bake_bonus_sprites.py -- <repoRoot> <outDir>
#
# Resolves BONUS_* -> ArtDefineTag (CIV4BonusInfos.xml) -> NIF (CIV4ArtDefines_Bonus.xml) ->
# file under UnpackedArt/art (with the _fx animated-variant fallback), imports via
# io_scene_niftools, curates the mesh (drops ground planes / billboards / helper spheres and the
# cultivated/improved variant so the natural resource reads), frames an orthographic 3/4-top
# camera, and renders. Writes manifest.json listing what baked and what had no model.
import bpy, os, sys, math, json, glob
import xml.etree.ElementTree as ET
import mathutils

argv = sys.argv[sys.argv.index("--") + 1:]
REPO, OUT = os.path.abspath(argv[0]), os.path.abspath(argv[1])
ART = os.path.join(REPO, "UnpackedArt", "art")
os.makedirs(OUT, exist_ok=True)

def strip(t):  # tag text without namespace noise
    return (t or "").strip()
# Civ4 XMLs carry a default namespace ({x-schema:...}Tag), so match by LOCAL name
def local(tag): return tag.split("}")[-1]
def iter_local(root, name): return [e for e in root.iter() if local(e.tag) == name]
def ctext(el, name):  # text of the first direct child with this local name
    for c in el:
        if local(c.tag) == name: return strip(c.text)
    return None

# 1) BONUS_* -> ArtDefineTag
tag_of = {}
for bi in iter_local(ET.parse(os.path.join(REPO, "data/civ4/CIV4BonusInfos.xml")).getroot(), "BonusInfo"):
    t = ctext(bi, "Type"); a = ctext(bi, "ArtDefineTag")
    if t: tag_of[t] = a or ""
# 2) ArtDefineTag -> NIF
nif_of = {}
for ai in iter_local(ET.parse(os.path.join(REPO, "data/civ4/CIV4ArtDefines_Bonus.xml")).getroot(), "BonusArtInfo"):
    t = ctext(ai, "Type"); n = ctext(ai, "NIF")
    if t: nif_of[t] = n or ""
# 3) index every .nif on disk by lowercase basename (pointer files count — names exist w/o LFS)
disk = {}
for p in glob.glob(os.path.join(ART, "**", "*.nif"), recursive=True):
    disk.setdefault(os.path.basename(p).lower(), p)

def resolve_nif(bonus_type):
    nif = nif_of.get(tag_of.get(bonus_type, ""), "")
    if not nif: return None
    stem = os.path.splitext(os.path.basename(nif.replace("\\", "/")))[0].lower()
    return disk.get(stem + ".nif") or disk.get(stem + "_fx.nif")

bpy.ops.preferences.addon_enable(module="io_scene_niftools")

def clear():
    for o in list(bpy.data.objects): bpy.data.objects.remove(o, do_unlink=True)
    # purge orphan datablocks so has_texture() reflects only the NEXT model's images
    for coll in (bpy.data.meshes, bpy.data.materials, bpy.data.images, bpy.data.armatures):
        for b in list(coll):
            if b.users == 0: coll.remove(b)

DROP_KW = ("wave", "sphere", "shadow", "sound", "billboard", "collision", "envlight", "omni")
VARIANT_KW = ("cultivated", "improved", "worked", "farm", "mine", "plantation", "quarry")
BASE_KW = ("base", "ground", "plot", "dirt", "patch", "terrain", "disc", "mound")

def _bbox(o):  # world-space (min, max)
    pts = [o.matrix_world @ mathutils.Vector(c) for c in o.bound_box]
    mn = mathutils.Vector((min(p.x for p in pts), min(p.y for p in pts), min(p.z for p in pts)))
    mx = mathutils.Vector((max(p.x for p in pts), max(p.y for p in pts), max(p.z for p in pts)))
    return mn, mx

def curate():
    meshes = [o for o in bpy.data.objects if o.type == "MESH"]
    if not meshes:
        return []
    has_wild = any("wild" in o.name.lower() for o in meshes)
    floor = min(_bbox(o)[0].z for o in meshes)
    def is_junk(o):
        n = o.name.lower(); mn, mx = _bbox(o); ex = mx - mn; span = max(ex.x, ex.y, 1e-6)
        if any(k in n for k in DROP_KW): return True
        if has_wild and any(k in n for k in VARIANT_KW): return True      # keep the wild look, drop the improved
        if any(k in n for k in BASE_KW): return True                     # named ground/mound base
        if len(o.data.polygons) <= 2: return True                        # billboard / ground quad
        if ex.z <= 0.18 * span and mn.z <= floor + 0.12 * span: return True  # wide+flat mesh at the floor = base
        return False
    keep = [o for o in meshes if not is_junk(o)]
    if not keep:  # over-pruned — keep the single largest mesh (the resource itself)
        keep = [max(meshes, key=lambda o: len(o.data.polygons))]
    for o in list(meshes):
        if o not in keep: bpy.data.objects.remove(o, do_unlink=True)
    return keep

def has_texture():  # a real image loaded for this model — else it rendered as an untextured placeholder
    return any(i.name not in ("Render Result", "Viewer Node") for i in bpy.data.images)

def frame_and_render(meshes, out_png):
    mn = mathutils.Vector((1e9,) * 3); mx = mathutils.Vector((-1e9,) * 3)
    for o in meshes:
        for c in o.bound_box:
            w = o.matrix_world @ mathutils.Vector(c)
            for i in range(3): mn[i] = min(mn[i], w[i]); mx[i] = max(mx[i], w[i])
    center = (mn + mx) / 2; rad = max((mx - mn).x, (mx - mn).y, (mx - mn).z) / 2 or 1.0
    cd = bpy.data.cameras.new("c"); cd.type = "ORTHO"; cd.ortho_scale = rad * 2.3
    cam = bpy.data.objects.new("c", cd); bpy.context.scene.collection.objects.link(cam)
    el, az = math.radians(50), math.radians(-45)
    d = mathutils.Vector((math.cos(el)*math.cos(az), math.cos(el)*math.sin(az), math.sin(el)))
    cam.location = center + d * rad * 6
    tgt = bpy.data.objects.new("t", None); bpy.context.scene.collection.objects.link(tgt); tgt.location = center
    tc = cam.constraints.new("TRACK_TO"); tc.target = tgt; tc.track_axis = "TRACK_NEGATIVE_Z"; tc.up_axis = "UP_Y"
    bpy.context.scene.camera = cam
    ld = bpy.data.lights.new("s", "SUN"); ld.energy = 3.2
    sun = bpy.data.objects.new("s", ld); bpy.context.scene.collection.objects.link(sun)
    sun.rotation_euler = (math.radians(48), 0, math.radians(-35))
    sc = bpy.context.scene
    sc.render.engine = "BLENDER_EEVEE"; sc.render.film_transparent = True
    sc.render.resolution_x = sc.render.resolution_y = 256
    sc.render.image_settings.file_format = "PNG"; sc.render.image_settings.color_mode = "RGBA"
    sc.render.filepath = out_png
    bpy.ops.render.render(write_still=True)

baked, missing = [], []
for bt in tag_of:  # every bonus type in BonusInfos
    nif = resolve_nif(bt)
    if not nif:
        missing.append(bt); continue
    try:
        clear(); bpy.ops.import_scene.nif(filepath=nif)
        if not has_texture(): raise RuntimeError("untextured (placeholder) model")
        meshes = curate()
        if not meshes: raise RuntimeError("no mesh after curation")
        frame_and_render(meshes, os.path.join(OUT, bt + ".png"))
        baked.append(bt)
    except Exception as e:
        print("BAKE_FAIL", bt, repr(e)); missing.append(bt)

json.dump({"baked": sorted(baked), "missing": sorted(missing)},
          open(os.path.join(OUT, "manifest.json"), "w"), indent=1)
print("BAKED %d  MISSING %d -> %s" % (len(baked), len(missing), OUT))
print("MISSING:", ", ".join(sorted(missing)))
