"""Read-only Blender bake of the single-actor parry/stagger mesh rigs.

blender --background --disable-autoexec SOURCE.blend --python-exit-code 1 \
    --python tools/export_parry.py -- --name parry
Use --name stagger for the attacker reaction. Add --verify to compare all saved
resources without writing them. No embedded scripts run and the source is never
saved. This exporter does not execute or write any execution-animation resources.

Game ticks map to authored frames through source_fps / 20. The last actual rig
keyframe determines the clip endpoint; a fractional game tick gets a held tail
to the next integer tick required by AnimationClip, preserving the authored pose.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

import bpy
from mathutils import Matrix, Quaternion, Vector

PARTS = {"head": "head", "body": "torso", "rightArm": "rightArm",
         "leftArm": "leftArm", "rightLeg": "rightLeg", "leftLeg": "leftLeg"}
H = Matrix(((-1, 0, 0), (0, 0, -1), (0, -1, 0)))
W = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))
BODY_PIVOT = Vector((0, 0, 3))
HIP_OFFSET = Vector((0, 12, 0))
PIXELS_PER_UNIT = 4.0
RIG = "fiercerbattles:humanoid_v1"
REST_BOUNDS = {
    "head": ((-4, -8, -4), (4, 0, 4)),
    "body": ((-4, 0, -2), (4, 12, 2)),
    "rightArm": ((-3, -2, -2), (1, 10, 2)),
    "leftArm": ((-1, -2, -2), (3, 10, 2)),
    "rightLeg": ((-2, 0, -2), (2, 12, 2)),
    "leftLeg": ((-2, 0, -2), (2, 12, 2)),
}


def rounded(values):
    return [round(float(value), 8) for value in values]


def curves(action):
    if action is None:
        return []
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    return [curve for layer in action.layers for strip in layer.strips
            for bag in strip.channelbags for curve in bag.fcurves]


def frame_set(frame):
    integer = int(math.floor(frame))
    bpy.context.scene.frame_set(integer, subframe=frame-integer)
    bpy.context.view_layer.update()


def pose(world, origin, undo_yaw, pivot):
    position = PIXELS_PER_UNIT * (W @ (undo_yaw @ (world @ pivot-origin))) + HIP_OFFSET
    rotation = (W @ undo_yaw @ world.to_3x3().normalized() @ H.inverted()).to_quaternion().normalized()
    return position, rotation


def quaternion(sample):
    return Quaternion((sample[6], sample[3], sample[4], sample[5])).normalized()


def main():
    script_arguments = sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True, choices=("parry", "stagger"))
    parser.add_argument("--step", type=float, default=0.0625)
    parser.add_argument("--verify", action="store_true")
    arguments = parser.parse_args(script_arguments)
    if not math.isfinite(arguments.step) or arguments.step <= 0:
        parser.error("--step must be positive and finite")
    source = Path(bpy.data.filepath)
    if not source.is_file():
        parser.error("Open the source .blend before running this exporter")
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    scene = bpy.context.scene
    fps = scene.render.fps / scene.render.fps_base
    if not math.isfinite(fps) or fps <= 0:
        parser.error("Source FPS must be positive and finite")
    begin_frame = scene.frame_start
    required_names = {"Bone_A", *PARTS.values()}
    required_names.update(name+"_bend" for name in PARTS.values() if name != "head")
    missing = required_names-set(bpy.data.objects.keys())
    if missing:
        raise RuntimeError("Missing humanoid rig objects: " + str(sorted(missing)))
    keyed_frames = set()
    source_actions = {}
    # Follow only this rig's active object actions, including every evaluated parent.
    rig_objects = {bpy.data.objects[name] for name in required_names}
    for obj in list(rig_objects):
        parent = obj.parent
        while parent is not None:
            rig_objects.add(parent)
            parent = parent.parent
    for obj in sorted(rig_objects, key=lambda obj: obj.name):
        animation = obj.animation_data
        if animation is None:
            continue
        if any(track.strips for track in animation.nla_tracks):
            raise RuntimeError("NLA timeline requires an explicit exporter endpoint: " + obj.name)
        keys = sorted({float(key.co.x) for curve in curves(animation.action) for key in curve.keyframe_points})
        if keys:
            keyed_frames.update(keys)
            source_actions[obj.name] = keys
    if not keyed_frames:
        raise RuntimeError("No active rig keyframes")
    end_frame = max(keyed_frames)
    authored_ticks = (end_frame-begin_frame)*20/fps
    duration = math.ceil(authored_ticks-1e-8)
    if duration < 1 or duration > 1200 or end_frame > scene.frame_end:
        raise RuntimeError("Rig keyframe range does not fit the scene or AnimationClip duration limits")
    if not math.isclose(duration/arguments.step, round(duration/arguments.step), abs_tol=1e-8):
        parser.error("--step must evenly divide the exported duration")
    count = round(duration/arguments.step)+1
    if count > 6001:
        parser.error("Sampling would exceed AnimationClip's 6001 samples per channel")
    frame_set(begin_frame)
    root = bpy.data.objects["Bone_A"]
    origin = root.matrix_world.to_translation().copy()
    yaw = root.rotation_euler.z
    undo_yaw = Matrix.Rotation(-yaw, 3, "Z")
    bindings = {}
    rest_bounds = {}
    for part, name in PARTS.items():
        base = bpy.data.objects[name]
        rest_inverse = base.matrix_world.inverted()
        pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
        names = [name] if part == "head" else [name, name+"_bend"]
        local_points = []
        for obj_name in names:
            obj = bpy.data.objects[obj_name]
            relative = rest_inverse @ obj.matrix_world
            points = [(Vector(vertex.co), PIXELS_PER_UNIT*(H @ ((relative @ vertex.co)-pivot)))
                      for vertex in obj.data.vertices]
            bindings[obj_name] = (part, points)
            local_points.extend(point for _, point in points)
        bounds = [[min(point[axis] for point in local_points) for axis in range(3)],
                  [max(point[axis] for point in local_points) for axis in range(3)]]
        rest_bounds[part] = [rounded(bound) for bound in bounds]
        if any(abs(actual-expected) > 0.0001 for bound, wanted in zip(bounds, REST_BOUNDS[part])
               for actual, expected in zip(bound, wanted)):
            raise RuntimeError("Source mesh does not match humanoid_v1 geometry: " + part)

    def samples_at(tick):
        frame_set(min(end_frame, begin_frame+tick*fps/20))
        graph = bpy.context.evaluated_depsgraph_get()
        result = {}
        for part, name in PARTS.items():
            world = bpy.data.objects[name].evaluated_get(graph).matrix_world
            pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
            position, rotation = pose(world, origin, undo_yaw, pivot)
            result[part] = rounded(position)+rounded((rotation.x, rotation.y, rotation.z, rotation.w))
        return result

    parts = {part: [] for part in PARTS}
    max_geometry_error = 0.0
    geometry_errors = {}
    for index in range(count):
        tick = index*arguments.step
        samples = samples_at(tick)
        graph = bpy.context.evaluated_depsgraph_get()
        for part, sample in samples.items():
            rotation = quaternion(sample)
            if parts[part] and rotation.dot(quaternion(parts[part][-1])) < 0:
                sample[3:] = [-value for value in sample[3:]]
            parts[part].append(sample)
        tick_error = 0.0
        for obj_name, (part, points) in bindings.items():
            world = bpy.data.objects[obj_name].evaluated_get(graph).matrix_world
            sample = samples[part]
            position, rotation = Vector(sample[:3]), quaternion(sample)
            for source_local, model_local in points:
                actual = PIXELS_PER_UNIT*(W @ (undo_yaw @ (world @ source_local-origin)))+HIP_OFFSET
                reproduced = position+rotation @ model_local
                tick_error = max(tick_error, (actual-reproduced).length)
        max_geometry_error = max(max_geometry_error, tick_error)
        if index in {0, round((count-1)/2), count-1}:
            geometry_errors[str(tick)] = tick_error
    max_interpolation_error = 0.0
    for index in range(count-1):
        actual = samples_at((index+0.5)*arguments.step)
        for part in PARTS:
            first, second, wanted = parts[part][index], parts[part][index+1], actual[part]
            position = Vector(first[:3]).lerp(Vector(second[:3]), 0.5)
            rotation = quaternion(first).slerp(quaternion(second), 0.5)
            for bound_part, points in bindings.values():
                if bound_part == part:
                    for _, point in points:
                        reproduced = position+rotation @ point
                        desired = Vector(wanted[:3])+quaternion(wanted) @ point
                        max_interpolation_error = max(max_interpolation_error, (reproduced-desired).length)
    if max_geometry_error > 0.0001 or max_interpolation_error > 0.075:
        raise RuntimeError("Source alignment failed: sample=" + str(max_geometry_error)
                           + ", half sample=" + str(max_interpolation_error))
    frame_set(end_frame)
    torso = bpy.data.objects["torso"].evaluated_get(bpy.context.evaluated_depsgraph_get())
    source_hip = undo_yaw @ (torso.matrix_world.to_translation()-origin)
    source_delta = Vector((source_hip.x, source_hip.z, -source_hip.y))*0.25
    source_front = undo_yaw @ (torso.matrix_world.to_3x3().normalized() @ Vector((0, 1, 0)))
    source_yaw = math.atan2(-source_front.x, -source_front.y)
    final = parts["body"][-1]
    hip = Vector(final[:3])+quaternion(final) @ Vector((0, 12, 0))
    replay_delta = Vector((hip.x, 12-hip.y, -hip.z))/16
    front = quaternion(final) @ Vector((0, 0, -1))
    replay_yaw = math.atan2(-front.x, -front.z)
    hip_error = (source_delta-replay_delta).length
    yaw_error = abs(math.remainder(replay_yaw-source_yaw, 2*math.pi))
    if hip_error > 0.00001 or yaw_error > 0.00001:
        raise RuntimeError("End root reconstruction failed")
    end_root = {"right": round(float(source_delta.x), 8), "forward": round(float(source_delta.z), 8),
                "yaw": round(math.degrees(source_yaw), 8)}
    document = {"format": 1, "rig": RIG, "role": arguments.name, "duration_ticks": duration,
                "sample_step": arguments.step, "end_root": end_root, "parts": parts}
    metadata = {
        "source_file": source.name, "source_sha256": source_hash, "source_fps": fps,
        "source_scene_frame_start": scene.frame_start, "source_scene_frame_end": scene.frame_end,
        "authored_begin_frame": begin_frame, "authored_end_frame": end_frame,
        "authored_duration_game_ticks": authored_ticks, "duration_ticks": duration,
        "frame_to_game_tick": "(source_frame - authored_begin_frame) * 20 / source_fps",
        "source_rig": "Bone_A plus six rigid humanoid mesh objects; zero-bend halves",
        "rig": RIG, "root_position_blender": rounded(origin), "root_yaw_radians": round(yaw, 10),
        "active_rig_keyframes": source_actions, "timeline_markers": [(marker.name, marker.frame) for marker in scene.timeline_markers],
        "sample_step": arguments.step, "sample_count": count, "end_root": end_root,
        "end_root_up_blocks": round(float(source_delta.y), 8),
        "rest_mesh_bounds_model_pixels": rest_bounds,
        "verification": {"max_source_mesh_vertex_error_pixels": max_geometry_error,
                         "max_half_sample_mesh_error_pixels": max_interpolation_error,
                         "first_middle_last_source_mesh_errors_pixels": geometry_errors,
                         "end_hip_reconstruction_error_blocks": hip_error,
                         "end_yaw_reconstruction_error_degrees": math.degrees(yaw_error)},
        "notes": [
            "Every evaluated parent is baked, including the source root pitch. Only initial root yaw is removed.",
            "The clip's first and last poses are authored poses; ordinary/attack pose transitions belong to gameplay rendering.",
            "The parry game window uses source frames 5 through 12 because both provided sources run at 20 FPS.",
            "No independent transition marker/track exists; stagger frames 0 through 5 are part of its reaction and are retained.",
            "Embedded export texts contain conflicting stop frames inherited from previous animation files and are not executed or used.",
            "Humanoid positions are absolute model pixels and quaternions use x,y,z,w; existing AnimationClip/AnimationPlayer interpolation applies.",
            "Item helpers have no animated offset from the arm; the existing item-in-hand transform is retained.",
        ],
    }
    repository = Path(__file__).resolve().parents[1]
    assets = repository / "src/main/resources/assets/fiercerbattles/parry"
    datapack = repository / "src/main/resources/data/fiercerbattles/fiercerbattles_animations/parry"
    if arguments.verify:
        for folder in (assets, datapack):
            if json.loads((folder/(arguments.name+".json")).read_text(encoding="utf-8")) != document:
                raise RuntimeError("Saved clip does not reproduce source: " + str(folder))
        if json.loads((assets/(arguments.name+"_metadata.json")).read_text(encoding="utf-8")) != metadata:
            raise RuntimeError("Saved metadata does not reproduce source")
    else:
        encoded = json.dumps(document, separators=(",", ":"))+"\n"
        for folder in (assets, datapack):
            folder.mkdir(parents=True, exist_ok=True)
            (folder/(arguments.name+".json")).write_text(encoded, encoding="utf-8")
        (assets/(arguments.name+"_metadata.json")).write_text(json.dumps(metadata, indent=2)+"\n", encoding="utf-8")
    if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
        raise RuntimeError("Source blend unexpectedly changed")
    print("VERIFIED" if arguments.verify else "EXPORTED", json.dumps({
        "clip": arguments.name, "source_sha256": source_hash, "source_fps": fps,
        "duration_ticks": duration, "sample_count": count, "end_root": end_root,
        "verification": metadata["verification"]}))
    print("VERIFIED_SOURCE_UNCHANGED", source_hash)


if __name__ == "__main__":
    main()
