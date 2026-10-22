"""Read-only Blender bake of the Zombie Lord boss (资源包/blender动画/敌军动作).

Model geometry, from the dressed base model:
blender --background --disable-autoexec 敌军动作/zombie_lord.blend --python-exit-code 1 \
    --python tools/export_zombie_lord.py -- --model
Clips, one per source file (run --model first; clips check their object scales against it):
blender --background --disable-autoexec 敌军动作/动作/zombie_lord_attack1.blend --python-exit-code 1 \
    --python tools/export_zombie_lord.py -- --name attack1

Unlike the humanoid_v1 exporters, every rendered mesh object of the boss (armour, crown, cape,
greatsword, limb halves) is its own channel, so attachments animated in Blender (cape, tabard,
the greatsword under rightItem) keep their motion. A channel sample is the object's absolute
pose in vanilla model space: model_point = position + rotation * local_vertex, positions in
model pixels (y down, feet at y=24) and quaternions x,y,z,w, the same convention as the
AnimationClip format 1 used by execution/parry clips. Object scale is constant per object and
baked into the exported vertices. Game ticks map to frames through source_fps / 20; the boss
sources run at 60/3 = 20 FPS, so frame N is tick N. No embedded scripts run and the source is
never saved.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

import bpy
from mathutils import Matrix, Quaternion, Vector

W = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))
HIP_OFFSET = Vector((0, 12, 0))
PIXELS_PER_UNIT = 4.0
RIG = "fiercerbattles:zombie_lord_v1"
ROOT = "Bone_A"
CLIPS = ("attack1", "attack2", "attack3", "combo", "quake", "summon", "leap_slam",
         "leap_slam_inplace", "walk", "charge", "death")
LOOPS = {"walk"}
REPOSITORY = Path(__file__).resolve().parents[1]
ASSETS = REPOSITORY / "src/main/resources/assets/fiercerbattles/zombie_lord"


def rounded(values, digits=6):
    return [round(float(value), digits) for value in values]


def frame_set(frame):
    integer = int(math.floor(frame))
    bpy.context.scene.frame_set(integer, subframe=frame-integer)
    bpy.context.view_layer.update()


def rendered_meshes():
    """Mesh objects that belong to the boss and are visible in this file, sorted by name."""
    root = bpy.data.objects.get(ROOT)
    if root is None:
        raise RuntimeError("Missing rig root " + ROOT)
    result = []
    for obj in bpy.data.objects:
        if obj.type != "MESH" or obj.hide_render:
            continue
        parent = obj
        while parent is not None and parent != root:
            parent = parent.parent
        if parent == root:
            result.append(obj)
    return sorted(result, key=lambda obj: obj.name)


def root_frame():
    root = bpy.data.objects[ROOT]
    return root.matrix_world.to_translation().copy(), Matrix.Rotation(-root.rotation_euler.z, 3, "Z")


def decompose(matrix):
    location, rotation, scale = matrix.decompose()
    rebuilt = Matrix.LocRotScale(location, rotation, scale)
    if max(abs(a-b) for row_a, row_b in zip(rebuilt, matrix) for a, b in zip(row_a, row_b)) > 1e-4:
        raise RuntimeError("Sheared object transform cannot be baked")
    return location, rotation, scale


def export_model(arguments, source, source_hash):
    frame_set(bpy.context.scene.frame_start)
    graph = bpy.context.evaluated_depsgraph_get()
    objects = {}
    for obj in rendered_meshes():
        evaluated = obj.evaluated_get(graph)
        _, _, scale = decompose(evaluated.matrix_world)
        mesh = obj.data
        uv_layer = mesh.uv_layers.active
        if uv_layer is None:
            raise RuntimeError("Mesh has no UV map: " + obj.name)
        faces = []
        for polygon in mesh.polygons:
            if polygon.loop_total != 4:
                raise RuntimeError("Only quads are supported: " + obj.name)
            corners = []
            for loop_index in polygon.loop_indices:
                vertex = mesh.vertices[mesh.loops[loop_index].vertex_index].co
                local = PIXELS_PER_UNIT * (W @ Vector((vertex.x*scale.x, vertex.y*scale.y, vertex.z*scale.z)))
                uv = uv_layer.data[loop_index].uv
                corners.append((local, (uv.x, 1-uv.y)))
            normal = (corners[2][0]-corners[0][0]).cross(corners[3][0]-corners[1][0])
            if normal.length < 1e-9:
                normal = (corners[1][0]-corners[0][0]).cross(corners[2][0]-corners[0][0])
            if normal.length < 1e-9:
                continue
            normal.normalize()
            face = rounded(normal, 5)
            for local, uv in corners:
                face += rounded(local, 5) + rounded(uv, 7)
            faces.append(face)
        objects[obj.name] = {"scale": rounded(scale, 6), "faces": faces}
    images = sorted(image.filepath for image in bpy.data.images if image.filepath)
    document = {"format": 1, "rig": RIG, "texture_size": [128, 128], "objects": objects,
                "source_file": source.name, "source_sha256": source_hash, "texture_sources": images,
                "face_layout": "normal xyz, then 4 x (x, y, z, u, v); model pixels in the object's own frame"}
    ASSETS.mkdir(parents=True, exist_ok=True)
    (ASSETS / "model.json").write_text(json.dumps(document, separators=(",", ":"))+"\n", encoding="utf-8")
    print("EXPORTED_MODEL", json.dumps({name: len(value["faces"]) for name, value in objects.items()}))


def export_clip(arguments, source, source_hash):
    scene = bpy.context.scene
    fps = scene.render.fps / scene.render.fps_base
    if not math.isfinite(fps) or fps <= 0:
        raise RuntimeError("Source FPS must be positive and finite")
    model_path = ASSETS / "model.json"
    model = json.loads(model_path.read_text(encoding="utf-8"))["objects"]
    # Loops are authored on 1..L with key L equal to phase 0; frame 0 holds the same pose.
    begin_frame = 0 if arguments.name in LOOPS else scene.frame_start
    end_frame = scene.frame_end
    authored_ticks = (end_frame-begin_frame)*20/fps
    duration = round(authored_ticks)
    if not math.isclose(duration, authored_ticks, abs_tol=1e-6) or duration < 1 or duration > 1200:
        raise RuntimeError("Clip range does not land on whole game ticks")
    count = round(duration/arguments.step)+1
    if not math.isclose((count-1)*arguments.step, duration, abs_tol=1e-8) or count > 6001:
        raise RuntimeError("--step must evenly divide the clip and keep at most 6001 samples")
    frame_set(begin_frame)
    origin, undo_yaw = root_frame()
    objects = rendered_meshes()
    missing = sorted(set(model) - {obj.name for obj in objects})
    extra = sorted({obj.name for obj in objects} - set(model))
    if missing or extra:
        raise RuntimeError("Clip objects differ from model.json: missing=" + str(missing) + " extra=" + str(extra))
    parts = {obj.name: [] for obj in objects}
    max_scale_error = 0.0
    for index in range(count):
        tick = index*arguments.step
        frame_set(begin_frame+tick*fps/20)
        graph = bpy.context.evaluated_depsgraph_get()
        for obj in objects:
            location, rotation, scale = decompose(obj.evaluated_get(graph).matrix_world)
            expected = model[obj.name]["scale"]
            max_scale_error = max(max_scale_error, max(abs(a-b) for a, b in zip(scale, expected)))
            position = PIXELS_PER_UNIT * (W @ (undo_yaw @ (location-origin))) + HIP_OFFSET
            turned = (W @ undo_yaw @ rotation.to_matrix() @ W.transposed()).to_quaternion().normalized()
            previous = parts[obj.name][-1] if parts[obj.name] else None
            if previous is not None and turned.dot(Quaternion((previous[6], previous[3], previous[4], previous[5]))) < 0:
                turned.negate()
            parts[obj.name].append(rounded(position, 4) + rounded((turned.x, turned.y, turned.z, turned.w), 6))
    if max_scale_error > 1e-3:
        raise RuntimeError("Object scale changes during the clip: " + str(max_scale_error))
    events = {}
    sidecar = source.with_suffix(".json")
    if sidecar.is_file():
        events = json.loads(sidecar.read_text(encoding="utf-8")).get("events", {})
    document = {"format": 1, "rig": RIG, "clip": arguments.name, "duration_ticks": duration,
                "sample_step": arguments.step, "loop": arguments.name in LOOPS, "events": events,
                "source_file": source.name, "source_sha256": source_hash, "source_fps": fps,
                "source_frames": [begin_frame, end_frame], "parts": parts}
    folder = ASSETS / "clips"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / (arguments.name+".json")).write_text(json.dumps(document, separators=(",", ":"))+"\n", encoding="utf-8")
    print("EXPORTED_CLIP", json.dumps({"clip": arguments.name, "fps": fps, "frames": [begin_frame, end_frame],
                                       "duration_ticks": duration, "samples": count, "channels": len(parts),
                                       "max_scale_error": max_scale_error}))


def main():
    script_arguments = sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--model", action="store_true")
    group.add_argument("--name", choices=CLIPS)
    parser.add_argument("--step", type=float, default=0.5)
    arguments = parser.parse_args(script_arguments)
    source = Path(bpy.data.filepath)
    if not source.is_file():
        parser.error("Open the source .blend before running this exporter")
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    if arguments.model:
        export_model(arguments, source, source_hash)
    else:
        export_clip(arguments, source, source_hash)
    if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
        raise RuntimeError("Source blend unexpectedly changed")


if __name__ == "__main__":
    main()
