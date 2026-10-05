"""Bake execution.blend into flat Minecraft ModelPart transforms, without saving it.

Run:
  blender --background --disable-autoexec execution.blend --python-exit-code 1 --python tools/export_execution.py
Verify saved resources without writing:
  blender --background --disable-autoexec execution.blend --python-exit-code 1 --python tools/export_execution.py -- --verify

The input uses mesh objects rather than armature bones. All evaluated parents,
including Bone_B's animated pitch, are included. The detached victim arm remains
in its authored world trajectory. No embedded Blender scripts are executed.
"""
import hashlib
import json
import math
from pathlib import Path
import sys

import bpy
from mathutils import Matrix, Quaternion, Vector

DURATION = 56
STEP = 0.125
PIXELS_PER_UNIT = 4.0
PARTS = {
    "head": "head", "body": "torso", "rightArm": "rightArm",
    "leftArm": "leftArm", "rightLeg": "rightLeg", "leftLeg": "leftLeg",
}
# Mesh-local +Y is the textured front, which is Minecraft model-local -Z.
H = Matrix(((-1, 0, 0), (0, 0, -1), (0, -1, 0)))
# A neutral torso is rotated pi around Blender Z. W @ Rz(pi) @ H^-1 = I.
W = Matrix(((1, 0, 0), (0, 0, -1), (0, 1, 0)))
HIP_OFFSET = Vector((0, 12, 0))
BODY_PIVOT = Vector((0, 0, 3))
OUT = Path(__file__).resolve().parents[1] / "src/main/resources/assets/fiercerbattles/execution"
ROLES = (("executioner", "", "Bone_A"), ("victim", "B_", "Bone_B"))
SCRIPT_ARGUMENTS = sys.argv[sys.argv.index("--") + 1:] if "--" in sys.argv else []
VERIFY_ONLY = "--verify" in SCRIPT_ARGUMENTS


def action_curves(action):
    if action is None:
        return []
    if hasattr(action, "fcurves"):
        return list(action.fcurves)
    return [fc for layer in action.layers for strip in layer.strips
            for bag in strip.channelbags for fc in bag.fcurves]


def set_tick(tick):
    frame = int(math.floor(tick))
    bpy.context.scene.frame_set(frame, subframe=tick - frame)
    bpy.context.view_layer.update()


def rounded(values):
    return [round(float(v), 8) for v in values]


def pose(world, origin, undo_yaw, pivot):
    point = world @ pivot
    position = PIXELS_PER_UNIT * (W @ (undo_yaw @ (point - origin))) + HIP_OFFSET
    rotation = (W @ undo_yaw @ world.to_3x3().normalized() @ H.inverted()).to_quaternion().normalized()
    return position, rotation


def samples_at(tick, origin, undo_yaw, prefix):
    set_tick(tick)
    depsgraph = bpy.context.evaluated_depsgraph_get()
    result = {}
    for part, name in PARTS.items():
        obj = bpy.data.objects[prefix + name].evaluated_get(depsgraph)
        pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
        position, rotation = pose(obj.matrix_world, origin, undo_yaw, pivot)
        result[part] = rounded(position) + rounded((rotation.x, rotation.y, rotation.z, rotation.w))
    return result


def end_root_data(prefix, origin, undo_yaw, body_sample):
    """Recover the authored hip and horizontal heading for the idle handoff.

    A body ModelPart rotates about the shoulder, while Blender torso rotates
    about the hip. Applying the sampled body quaternion to (0,12,0) gives the
    shoulder-to-hip vector. Never use the shoulder translation as entity motion.
    """
    set_tick(DURATION)
    torso = bpy.data.objects[prefix + "torso"].evaluated_get(bpy.context.evaluated_depsgraph_get())
    world = torso.matrix_world.copy()
    source_hip = world.to_translation()
    own_hip = undo_yaw @ (source_hip - origin)
    source_delta = Vector((own_hip.x, own_hip.z, -own_hip.y)) * 0.25
    own_front = undo_yaw @ (world.to_3x3().normalized() @ Vector((0, 1, 0)))
    source_yaw = math.atan2(-own_front.x, -own_front.y)
    position = Vector(body_sample[:3])
    rotation = Quaternion((body_sample[6], body_sample[3], body_sample[4], body_sample[5])).normalized()
    reconstructed_hip = position + rotation @ Vector((0, 12, 0))
    reconstructed_delta = Vector((reconstructed_hip.x, 12 - reconstructed_hip.y, -reconstructed_hip.z)) / 16
    reconstructed_front = rotation @ Vector((0, 0, -1))
    reconstructed_yaw = math.atan2(-reconstructed_front.x, -reconstructed_front.z)
    hip_error = (source_delta - reconstructed_delta).length
    yaw_error = abs(math.remainder(reconstructed_yaw - source_yaw, 2 * math.pi))
    if hip_error > 0.00001 or yaw_error > 0.00001:
        raise RuntimeError("End root reconstruction failed")
    return {
        "tick": DURATION,
        "source_torso_hip_world_blender": [float(v) for v in source_hip],
        "source_torso_euler_xzy_radians": [float(v) for v in torso.rotation_euler],
        "actor_local_hip_delta_blocks_right_up_forward": [float(v) for v in source_delta],
        "actor_local_horizontal_yaw_delta_degrees": math.degrees(source_yaw),
        "reconstructed_hip_model_pixels": rounded(reconstructed_hip),
        "max_hip_reconstruction_error_blocks": hip_error,
        "yaw_reconstruction_error_degrees": math.degrees(yaw_error),
        "body_tilt_from_vertical_degrees": math.degrees(math.acos(max(-1.0, min(1.0, (rotation @ Vector((0, 1, 0))).y)))),
    }


def role_export(role, prefix, root_name):
    set_tick(0)
    root = bpy.data.objects[root_name]
    origin = root.matrix_world.to_translation().copy()
    yaw = root.rotation_euler.z
    undo_yaw = Matrix.Rotation(-yaw, 3, "Z")
    mesh_bindings = {}
    rest_bounds = {}
    # Independent rest geometry: both halves of each rigid part are verified.
    for part, name in PARTS.items():
        base = bpy.data.objects[prefix + name]
        rest_inv = base.matrix_world.inverted()
        names = [prefix + name]
        if part != "head":
            names.append(prefix + name + "_bend")
        pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
        local_points = []
        for obj_name in names:
            obj = bpy.data.objects[obj_name]
            rest_relative = rest_inv @ obj.matrix_world
            points = [(Vector(v.co), PIXELS_PER_UNIT * (H @ ((rest_relative @ v.co) - pivot)))
                      for v in obj.data.vertices]
            mesh_bindings[obj_name] = (part, points)
            local_points.extend(point for _, point in points)
        rest_bounds[part] = {
            "minimum": rounded(min(v[i] for v in local_points) for i in range(3)),
            "maximum": rounded(max(v[i] for v in local_points) for i in range(3)),
        }
    parts = {name: [] for name in PARTS}
    max_geometry_error = 0.0
    max_rotation_error = 0.0
    max_pivot_error = 0.0
    previous = {}
    for index in range(round(DURATION / STEP) + 1):
        tick = index * STEP
        samples = samples_at(tick, origin, undo_yaw, prefix)
        for part, sample in samples.items():
            rotation = Quaternion((sample[6], sample[3], sample[4], sample[5]))
            if part in previous and rotation.dot(previous[part]) < 0:
                sample[3:] = [-value for value in sample[3:]]
                rotation.negate()
            previous[part] = rotation.copy()
            parts[part].append(sample)
        for obj_name, (part, points) in mesh_bindings.items():
            world = bpy.data.objects[obj_name].matrix_world
            sample = samples[part]
            position = Vector(sample[:3])
            rotation = Quaternion((sample[6], sample[3], sample[4], sample[5])).normalized()
            for source_local, model_local in points:
                actual = PIXELS_PER_UNIT * (W @ (undo_yaw @ ((world @ source_local) - origin))) + HIP_OFFSET
                replayed = position + rotation @ model_local
                max_geometry_error = max(max_geometry_error, (actual - replayed).length)
        for part, name in PARTS.items():
            sample = samples[part]
            position = Vector(sample[:3])
            rotation = Quaternion((sample[6], sample[3], sample[4], sample[5])).normalized()
            pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
            source = bpy.data.objects[prefix + name].matrix_world
            original_position, original_rotation = pose(source, origin, undo_yaw, pivot)
            max_pivot_error = max(max_pivot_error, (position - original_position).length)
            # abs(dot) avoids float angular error near identity.
            dot = min(1.0, abs(float(rotation.dot(original_rotation))))
            max_rotation_error = max(max_rotation_error, 2.0 * math.acos(dot))
    # Test intermediate playback using the same linear/slerp interpolation as Java.
    max_interpolation_error = 0.0
    for index in range(round(DURATION / STEP)):
        tick = (index + 0.5) * STEP
        actual = samples_at(tick, origin, undo_yaw, prefix)
        for part in PARTS:
            a, b, wanted = parts[part][index], parts[part][index + 1], actual[part]
            position = Vector(a[:3]).lerp(Vector(b[:3]), 0.5)
            ra = Quaternion((a[6], a[3], a[4], a[5])).normalized()
            rb = Quaternion((b[6], b[3], b[4], b[5])).normalized()
            rotation = ra.slerp(rb, 0.5)
            desired_position = Vector(wanted[:3])
            desired_rotation = Quaternion((wanted[6], wanted[3], wanted[4], wanted[5])).normalized()
            # The farthest rest vertex catches both translation and rotation error.
            for obj_name, (bound_part, points) in mesh_bindings.items():
                if bound_part != part:
                    continue
                for _, point in points:
                    reproduced = position + rotation @ point
                    desired = desired_position + desired_rotation @ point
                    max_interpolation_error = max(max_interpolation_error, (reproduced - desired).length)
    report = {
        "source_root": root_name,
        "root_position_blender": rounded(origin),
        "root_yaw_radians": round(yaw, 10),
        "sample_count": len(parts["head"]),
        "rest_mesh_bounds_model_pixels": rest_bounds,
        "max_sample_pivot_error_pixels": round(max_pivot_error, 10),
        "max_sample_rotation_error_degrees": round(math.degrees(max_rotation_error), 8),
        "max_source_mesh_vertex_error_pixels": round(max_geometry_error, 8),
        "max_half_sample_playback_mesh_error_pixels": round(max_interpolation_error, 8),
        "end_root": end_root_data(prefix, origin, undo_yaw, parts["body"][-1]),
    }
    if max_geometry_error > 0.0001 or max_pivot_error > 0.0001:
        raise RuntimeError("Source mesh alignment verification failed: " + json.dumps(report))
    document = {"format": 1, "role": role, "duration_ticks": DURATION,
                "sample_step": STEP, "parts": parts}
    if max_interpolation_error > 0.075:
        raise RuntimeError("Subframe alignment verification failed: " + json.dumps(report))
    if VERIFY_ONLY:
        existing = json.loads((OUT / (role + ".json")).read_text(encoding="utf-8"))
        if document != existing:
            raise RuntimeError("Saved clip does not reproduce source: " + role)
    else:
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / (role + ".json")).write_text(json.dumps(document, separators=(",", ":")) + "\n", encoding="utf-8")
    print("VERIFIED" if VERIFY_ONLY else "EXPORTED", role, json.dumps(report))
    return report


scene = bpy.context.scene
fps = scene.render.fps / scene.render.fps_base
if abs(fps - 20) > 0.00001:
    raise RuntimeError("Source must use 20 animation frames per second; got " + str(fps))
source = Path(bpy.data.filepath)
source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
set_tick(0)
source_a = bpy.data.objects["Bone_A"].matrix_world.to_translation().copy()
source_b = bpy.data.objects["Bone_B"].matrix_world.to_translation().copy()
source_offset = source_b - source_a
role_reports = {role: role_export(role, prefix, root) for role, prefix, root in ROLES}
max_bend = 0.0
for name in bpy.data.objects.keys():
    if name.endswith("_bend"):
        for index in range(round(DURATION / STEP) + 1):
            set_tick(index * STEP)
            max_bend = max(max_bend, *(abs(v) for v in bpy.data.objects[name].rotation_euler))
metadata = {
    "source_file": source.name, "source_sha256": source_hash,
    "source_fps": fps, "source_frame_start": scene.frame_start,
    "source_frame_end": scene.frame_end, "duration_ticks": DURATION,
    "sample_step": STEP, "pixels_per_blender_unit": PIXELS_PER_UNIT,
    "relative_victim_position_blocks_at_executor_yaw_zero": [float(source_offset.x * 0.25), float(source_offset.z * 0.25), float(-source_offset.y * 0.25)],
    "relative_victim_yaw_degrees": 180,
    "root_to_feet_blocks": 0.75,
    "max_bend_radians": max_bend,
    "conversion": {
        "source_world_to_minecraft_world_at_executor_yaw_zero": "0.25 * (x, z, -y)",
        "model_pivot_pixels": "4 * W * Rz(-rootYaw) * (worldPivot - rootPositionAtFrame0) + (0,12,0)",
        "model_rotation": "W * Rz(-rootYaw) * worldRotation * inverse(H)",
        "W": [list(row) for row in W], "H": [list(row) for row in H],
        "body_source_local_pivot": list(BODY_PIVOT),
        "quaternion_order": ["x", "y", "z", "w"],
    },
    "notes": [
        "All evaluated parents are baked. Animated Bone_B pitch is retained; only its fixed yaw is removed.",
        "Model positions are absolute pixels. Do not add vanilla pivots or animate torso position a second time.",
        "Both entities use a shared render scale of 1.0; standard player's 0.9375 scale causes contact drift.",
        "Body uses shoulder pivot, source torso local (0,0,3), not the Blender hip origin.",
        "Bone_B actual placement differs from adjacent README and is captured here.",
        "The detached left arm is already offset from the victim shoulder in the source at frame0; its authored world path is preserved.",
        "All torso/limb bends are zero. Rigid vanilla model parts match both source mesh halves.",
        "Item channels are constant zero. Source rightItem/leftItem rest pivots map to (-6,12,-2)/(6,12,-2), matching vanilla ItemInHandLayer's arm transform and RX(-90)*RY(180) translation.",
        "Each quaternion's sign is made continuous. Java playback uses shortest-path slerp and linear pivot interpolation.",
        "Resource duration follows source split export stopFrame=56; Blender scene extends to60 with held end poses.",
        "At clip end, entity motion comes from the authored torso hip, recovered as bodyPivot + bodyQuaternion * (0,12,0), never directly from the shoulder pivot.",
        "For the idle handoff, rotate actor-local (right,forward) using the starting entity yaw; ending body horizontal heading is a separate yaw offset.",
        "Idle is upright, while the authored final executioner body is tilted approximately18.1degrees; retaining the hip avoids a backwards snap, and a short pose return blend handles the remaining shoulder displacement.",
    ],
    "verification": role_reports,
    "end_root_reconstruction": {
        "hip_model_pixels": "bodyPivot + bodyQuaternion * (0,12,0)",
        "actor_local_delta_blocks_right_up_forward": "(hip.x / 16, (12 - hip.y) / 16, -hip.z / 16)",
        "front_model": "bodyQuaternion * (0,0,-1)",
        "actor_yaw_offset_radians": "atan2(-frontModel.x, -frontModel.z)",
        "world_delta_xz_for_initial_actor_yaw_theta": "(right*cos(theta)-forward*sin(theta), right*sin(theta)+forward*cos(theta))",
        "grounded_idle_handoff": "Use horizontal hip delta and preserve grounded entity Y; yaw becomes initial actor yaw plus projected final body yaw delta.",
    },
    "verification_limits": {
        "max_source_mesh_vertex_error_pixels": 0.0001,
        "max_half_sample_playback_mesh_error_pixels": 0.075,
        "max_half_sample_playback_mesh_error_blocks": 0.0046875,
        "command": "blender --background --disable-autoexec execution.blend --python-exit-code 1 --python tools/export_execution.py -- --verify",
    },
}
if VERIFY_ONLY:
    existing_metadata = json.loads((OUT / "metadata.json").read_text(encoding="utf-8"))
    if existing_metadata != metadata:
        raise RuntimeError("Saved metadata does not reproduce source")
else:
    (OUT / "metadata.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
    raise RuntimeError("Source blend unexpectedly changed")
print("VERIFIED_SOURCE_UNCHANGED", source_hash)
