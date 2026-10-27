"""Read-only Blender bake of the Iron Sentinel boss (资源包/blender动画/BOSS/钢铁卫戍).

Model geometry, from the dressed base model:
blender --background --disable-autoexec 钢铁卫戍/iron_sentinel.blend --python-exit-code 1 \
    --python tools/export_iron_sentinel.py -- --model
Clips, one per source file (run --model first; clips check their object scales against it):
blender --background --disable-autoexec 钢铁卫戍/动作/iron_sentinel_saw_thrust.blend --python-exit-code 1 \
    --python tools/export_iron_sentinel.py -- --name saw_thrust
The paired execution (钢铁卫戍/动作/iron_sentinel_executed.blend and saw_executed: the sentinel plus a P_ player
rig) also writes the player's half as a humanoid_v1 paired action:
blender ... iron_sentinel_saw_executed.blend ... -- --name saw_executed --player 30

Same scheme as tools/export_zombie_lord.py: every rendered mesh object under Bone_A is its own channel, sampled
as an absolute pose in vanilla model space (pixels, y down, feet at y=24, quaternions x,y,z,w), so the saw
stages, the cannon parts and the chains keep their authored motion. Samples are taken in the root's own frame,
so a scaled root (the paired file draws the sentinel at its in-game 1.2x next to a 1.0x player) exports the
sentinel at scale 1 like every other clip. Clips also carry "points": per-tick tracked points of the saw and
the cannon in the same model space (tools/iron_sentinel_points.py turns them into server tables). No embedded
scripts run and the source is never saved.
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
HIP_OFFSET = Vector((0, 9, 0))       # Bone_A sits at the hip's rest position, 15 px above the ground
PIXELS_PER_UNIT = 4.0
RIG = "fiercerbattles:iron_sentinel_v1"
ROOT = "Bone_A"
CLIPS = ("idle", "walk", "attack_left", "attack_right", "attack_combo", "attack_normal", "skill_cannon",
         "saw_transform", "saw_revert", "saw_sweep_lr", "saw_sweep_rl", "saw_thrust", "saw_impale", "saw_idle",
         "saw_walk", "saw_cannon", "stagger", "saw_stagger", "executed", "saw_executed", "cannon_sky",
         "saw_cannon_sky", "saw_cannon_thrust", "saw_cannon_impale", "death", "saw_death")
LOOPS = {"idle", "walk", "saw_idle", "saw_walk"}
# Tracked points: (object, local offset in px, direction in the object's frame or None).
POINTS = {
    "impale": ("IS_leftSawTip", (0.0, 0.0, 8.0), None),      # where a skewered victim sits on the blade
    "saw_end": ("IS_leftSawTip", (0.0, 0.0, 0.0), None),
    "wrist_l": ("leftArm_bend", (0.0, 0.0, -11.0), None),    # the left cuff, where the saw comes out
    "muzzle": ("IS_rightMuzzle", (0.0, 0.0, -0.25), (0.0, 0.0, -1.0)),
}
REPOSITORY = Path(__file__).resolve().parents[1]
ASSETS = REPOSITORY / "src/main/resources/assets/fiercerbattles/iron_sentinel"
DATA = REPOSITORY / "src/main/resources/data/fiercerbattles"

# Humanoid executor (the paired execution's player half), as in tools/export_execution.py.
PLAYER_PARTS = {"head": "head", "body": "torso", "rightArm": "rightArm", "leftArm": "leftArm",
                "rightLeg": "rightLeg", "leftLeg": "leftLeg"}
H = Matrix(((-1, 0, 0), (0, 0, -1), (0, -1, 0)))
PLAYER_HIP = Vector((0, 12, 0))
BODY_PIVOT = Vector((0, 0, 3))


def rounded(values, digits=6):
    return [round(float(value), digits) for value in values]


def frame_set(frame):
    integer = int(math.floor(frame))
    bpy.context.scene.frame_set(integer, subframe=frame-integer)
    bpy.context.view_layer.update()


def rendered_meshes():
    """Mesh objects that belong to the sentinel and are visible in this file, sorted by name."""
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


def root_inverse():
    """World -> the root's own frame (translation, rotation and scale undone)."""
    return bpy.data.objects[ROOT].matrix_world.inverted()


def decompose(matrix):
    location, rotation, scale = matrix.decompose()
    rebuilt = Matrix.LocRotScale(location, rotation, scale)
    if max(abs(a-b) for row_a, row_b in zip(rebuilt, matrix) for a, b in zip(row_a, row_b)) > 1e-4:
        raise RuntimeError("Sheared object transform cannot be baked")
    return location, rotation, scale


def model_point(local):
    """A point in the root frame (Blender units) -> vanilla model pixels."""
    return PIXELS_PER_UNIT * (W @ local) + HIP_OFFSET


def export_model(arguments, source, source_hash):
    frame_set(bpy.context.scene.frame_start)
    graph = bpy.context.evaluated_depsgraph_get()
    undo = root_inverse()
    objects = {}
    for obj in rendered_meshes():
        _, _, scale = decompose(undo @ obj.evaluated_get(graph).matrix_world)
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


def clip_range(name):
    scene = bpy.context.scene
    fps = scene.render.fps / scene.render.fps_base
    if not math.isfinite(fps) or abs(fps - 20) > 1e-6:
        raise RuntimeError("Source must run at 20 FPS (one frame per game tick)")
    # Loops are authored on 1..L with key L equal to phase 0; frame 0 holds the same pose.
    begin_frame = 0 if name in LOOPS else scene.frame_start
    duration = scene.frame_end - begin_frame
    if duration < 1 or duration > 1200:
        raise RuntimeError("Clip range does not land on whole game ticks")
    return begin_frame, duration


def export_clip(arguments, source, source_hash):
    model = json.loads((ASSETS / "model.json").read_text(encoding="utf-8"))["objects"]
    begin_frame, duration = clip_range(arguments.name)
    count = round(duration/arguments.step)+1
    if not math.isclose((count-1)*arguments.step, duration, abs_tol=1e-8) or count > 6001:
        raise RuntimeError("--step must evenly divide the clip and keep at most 6001 samples")
    frame_set(begin_frame)
    undo = root_inverse()
    objects = rendered_meshes()
    missing = sorted(set(model) - {obj.name for obj in objects})
    extra = sorted({obj.name for obj in objects} - set(model))
    if missing or extra:
        raise RuntimeError("Clip objects differ from model.json: missing=" + str(missing) + " extra=" + str(extra))
    parts = {obj.name: [] for obj in objects}
    points = {name: [] for name in POINTS}
    max_scale_error = 0.0
    for index in range(count):
        tick = index*arguments.step
        frame_set(begin_frame+tick)
        graph = bpy.context.evaluated_depsgraph_get()
        for obj in objects:
            location, rotation, scale = decompose(undo @ obj.evaluated_get(graph).matrix_world)
            expected = model[obj.name]["scale"]
            max_scale_error = max(max_scale_error, max(abs(a-b) for a, b in zip(scale, expected)))
            position = model_point(location)
            turned = (W @ rotation.to_matrix() @ W.transposed()).to_quaternion().normalized()
            previous = parts[obj.name][-1] if parts[obj.name] else None
            if previous is not None and turned.dot(Quaternion((previous[6], previous[3], previous[4], previous[5]))) < 0:
                turned.negate()
            parts[obj.name].append(rounded(position, 4) + rounded((turned.x, turned.y, turned.z, turned.w), 6))
        if index % round(1/arguments.step) == 0:
            for name, (part, offset, direction) in POINTS.items():
                world = bpy.data.objects[part].evaluated_get(graph).matrix_world
                local = undo @ world
                entry = rounded(model_point(local @ (Vector(offset) / PIXELS_PER_UNIT)), 3)
                if direction is not None:
                    entry += rounded((W @ (local.to_3x3().normalized() @ Vector(direction))).normalized(), 4)
                points[name].append(entry)
    if max_scale_error > 1e-3:
        raise RuntimeError("Object scale changes during the clip: " + str(max_scale_error))
    document = {"format": 1, "rig": RIG, "clip": arguments.name, "duration_ticks": duration,
                "sample_step": arguments.step, "loop": arguments.name in LOOPS, "points": points,
                "points_layout": "one entry per whole tick: model px (x, y, z) [+ unit direction]",
                "source_file": source.name, "source_sha256": source_hash, "source_frames": [begin_frame,
                                                                                            begin_frame+duration],
                "parts": parts}
    folder = ASSETS / "clips"
    folder.mkdir(parents=True, exist_ok=True)
    (folder / (arguments.name+".json")).write_text(json.dumps(document, separators=(",", ":"))+"\n", encoding="utf-8")
    print("EXPORTED_CLIP", json.dumps({"clip": arguments.name, "frames": [begin_frame, begin_frame+duration],
                                       "duration_ticks": duration, "samples": count, "channels": len(parts),
                                       "max_scale_error": max_scale_error}))


# ---------------------------------------------------------------- the paired execution's player half
def player_pose(world, origin, undo_yaw, pivot):
    point = world @ pivot
    position = PIXELS_PER_UNIT * (W @ (undo_yaw @ (point - origin))) + PLAYER_HIP
    rotation = (W @ undo_yaw @ world.to_3x3().normalized() @ H.inverted()).to_quaternion().normalized()
    return position, rotation


def export_player(arguments, source, source_hash):
    """The P_ rig's six humanoid_v1 channels over --player ticks, the action definition with the pair geometry
    (the sentinel's offset from the player and the player's end position), and no victim clip: the sentinel
    plays its own clip of the same name (IronSentinel, executed / saw_executed). As in export_execution.py,
    "right" is Blender +x (the character's left; the execution module uses the same mirrored convention)."""
    prefix, duration, step = "P_", arguments.player, arguments.player_step
    frame_set(0)
    player_root = bpy.data.objects[prefix + "Bone_A"]
    boss_root = bpy.data.objects[ROOT]
    origin = player_root.matrix_world.to_translation().copy()
    yaw = player_root.matrix_world.to_euler("XYZ").z
    undo_yaw = Matrix.Rotation(-yaw, 3, "Z")
    offset = undo_yaw @ (boss_root.matrix_world.to_translation() - origin)
    boss_yaw = boss_root.matrix_world.to_euler("XYZ").z
    count = round(duration/step)+1
    parts = {part: [] for part in PLAYER_PARTS}
    bindings = []
    for part, name in PLAYER_PARTS.items():
        base = bpy.data.objects[prefix + name]
        pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
        rest_inv = base.matrix_world.inverted()
        for obj_name in [prefix + name] + ([prefix + name + "_bend"] if part != "head" else []):
            obj = bpy.data.objects[obj_name]
            relative = rest_inv @ obj.matrix_world
            bindings.append((obj_name, part, [(Vector(v.co), PIXELS_PER_UNIT * (H @ ((relative @ v.co) - pivot)))
                                              for v in obj.data.vertices]))
    max_error = 0.0
    previous = {}
    for index in range(count):
        frame_set(index*step)
        for part, name in PLAYER_PARTS.items():
            pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
            position, rotation = player_pose(bpy.data.objects[prefix + name].matrix_world, origin, undo_yaw, pivot)
            if part in previous and rotation.dot(previous[part]) < 0:
                rotation.negate()
            previous[part] = rotation.copy()
            parts[part].append(rounded(position, 8) + rounded((rotation.x, rotation.y, rotation.z, rotation.w), 8))
        for obj_name, part, points in bindings:
            world = bpy.data.objects[obj_name].matrix_world
            sample = parts[part][-1]
            position = Vector(sample[:3])
            rotation = Quaternion((sample[6], sample[3], sample[4], sample[5])).normalized()
            for source_local, model_local in points:
                actual = PIXELS_PER_UNIT * (W @ (undo_yaw @ ((world @ source_local) - origin))) + PLAYER_HIP
                max_error = max(max_error, (actual - (position + rotation @ model_local)).length)
    if max_error > 1e-4:
        raise RuntimeError("Player mesh does not follow its six channels: %.6f px" % max_error)
    # Where the player ends up: the authored hip, carried over to the entity when the session ends.
    frame_set(duration)
    torso = bpy.data.objects[prefix + "torso"].matrix_world
    hip = undo_yaw @ (torso.to_translation() - origin)
    front = undo_yaw @ (torso.to_3x3().normalized() @ Vector((0, 1, 0)))
    end_yaw = math.degrees(math.atan2(-front.x, -front.y))
    name = "iron_sentinel_execution"
    clip = {"format": 1, "role": "executioner", "duration_ticks": duration, "sample_step": step, "parts": parts}
    action = {"rig": "fiercerbattles:humanoid_v1", "duration_ticks": duration,
              "executor_clip": "fiercerbattles:%s/executioner" % name,
              "geometry": {"victim_right": round(offset.x * 0.25, 6), "victim_forward": round(-offset.y * 0.25, 6),
                           "victim_yaw_delta": round(math.degrees(math.remainder(boss_yaw - yaw, 2*math.pi)), 4),
                           "executor_end_right": round(hip.x * 0.25, 6),
                           "executor_end_forward": round(-hip.y * 0.25, 6),
                           "executor_end_yaw_delta": round(end_yaw, 4)},
              "source_file": source.name, "source_sha256": source_hash}
    clips = DATA / "fiercerbattles_animations" / name
    clips.mkdir(parents=True, exist_ok=True)
    (clips / "executioner.json").write_text(json.dumps(clip, separators=(",", ":"))+"\n", encoding="utf-8")
    actions = DATA / "fiercerbattles_actions"
    actions.mkdir(parents=True, exist_ok=True)
    (actions / (name+".json")).write_text(json.dumps(action, indent=2)+"\n", encoding="utf-8")
    print("EXPORTED_PLAYER", json.dumps({"action": name, "geometry": action["geometry"], "samples": count,
                                         "max_mesh_error_px": max_error}))


def main():
    script_arguments = sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--model", action="store_true")
    group.add_argument("--name", choices=CLIPS)
    parser.add_argument("--step", type=float, default=0.5)
    parser.add_argument("--player", type=int, default=0, help="also export the P_ player rig's first N ticks")
    parser.add_argument("--player-step", type=float, default=0.0625)
    arguments = parser.parse_args(script_arguments)
    source = Path(bpy.data.filepath)
    if not source.is_file():
        parser.error("Open the source .blend before running this exporter")
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    if arguments.model:
        export_model(arguments, source, source_hash)
    else:
        export_clip(arguments, source, source_hash)
        if arguments.player:
            export_player(arguments, source, source_hash)
    if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
        raise RuntimeError("Source blend unexpectedly changed")


if __name__ == "__main__":
    main()
