"""Read-only Blender bake of a one.blend attack clip into a Better Combat (Player Animator emote v3) animation.

blender --background --disable-autoexec SOURCE.blend --python-exit-code 1     --python tools/export_bettercombat_attack.py -- --hit-tick N [--name NAME] [--tool tc_broad_axe] [--verify]
NAME defaults to the source file name. The clip is written to
src/main/resources/assets/bettercombat/player_animations/NAME.json and weapon_attributes refer to it
as "bettercombat:NAME". Add --verify to compare against the saved file without writing it.

Every game tick from frame 0 to the last keyframe becomes one linear keyframe holding absolute
model-part pivots (pixels) and Z*Y*X Euler rotations (radians), the values Player Animator writes
into the HumanoidModel parts. "torso" is the model body (emote version 3), so the entity root stays
put. The character's heading is taken from the torso at frame 0, snapped to 90 degrees, so a rig that
was turned around for authoring still faces forward in game.

Better Combat applies the hit when the clip reaches endTick / 2 (with the default upswing_multiplier)
and slows the playback afterwards. --hit-tick is the frame where the blade crosses the target, and
endTick = 2 * hit tick, so the damage lands on the visible strike. Keys after endTick still play;
stopTick adds half of endTick after the last key for the blend back to the idle pose, and beginTick
is a short fade-in.

The Tinkers tool is bound to both fists in Blender but rendered from the right hand in game, so
rightItem is solved per tick. The game chain is: arm, ItemInHandLayer offset, rightItem, then the
neoforge:item/default-tool thirdperson_righthand display, then the Tinkers large-tool quads (2x scale,
large_offset). It must reproduce the Blender voxel mesh, which is built from the same padded texture
at the same texel density (匠魂手持動作/_scripts/build_tool.py).
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import sys

import bpy
from mathutils import Euler, Matrix, Vector

sys.path.insert(0, str(Path(__file__).resolve().parent))
from export_parry import BODY_PIVOT, H, HIP_OFFSET, PARTS, PIXELS_PER_UNIT, REST_BOUNDS, W, curves, frame_set, pose  # noqa: E402

EMOTE_PARTS = {"head": "head", "body": "torso", "rightArm": "rightArm", "leftArm": "leftArm",
               "rightLeg": "rightLeg", "leftLeg": "leftLeg"}
TEXEL = 6.8 / 32            # Blender units per tool texel (build_tool.py PX)
PADDED = 34                 # 32px large-tool texture plus a 1px clear border on every side
LARGE_OFFSET = (-2, 2)      # tconstruct:item/broad_axe "large_offset"
HEAD_TEXEL = (16.34, 11.34)  # blade centroid in the padded texture (textures/tools.json head_centroid_px)
DISPLAY_TRANSLATION = Vector((0, 4.0, 0.5))  # neoforge:item/default-tool thirdperson_righthand, pixels
DISPLAY_ROTATION = (0, -90, 55)
DISPLAY_SCALE = 0.85
BEGIN_TICK = 2              # Better Combat fades into the clip over beginTick


def rotation(axis, degrees):
    return Matrix.Rotation(math.radians(degrees), 4, axis)


def part_matrix(position, quaternion):
    return Matrix.Translation(position) @ quaternion.to_matrix().to_4x4()


def emote_matrix(values):
    return (Matrix.Translation(Vector((values["x"], values["y"], values["z"])))
            @ Euler((values["pitch"], values["yaw"], values["roll"]), "XYZ").to_matrix().to_4x4())


def emote_values(matrix, previous):
    """Pivot and Z*Y*X Euler angles of a rigid model-space matrix, continuous with the previous tick."""
    basis = matrix.to_3x3().normalized()
    euler = basis.to_euler("XYZ", previous) if previous is not None else basis.to_euler("XYZ")
    translation = matrix.to_translation()
    values = {"x": translation.x, "y": translation.y, "z": translation.z,
              "pitch": euler.x, "yaw": euler.y, "roll": euler.z}
    return euler, {key: round(value, 6) for key, value in values.items()}


def texel_local(texel):
    return Vector(((texel[0]-PADDED/2)*TEXEL, (PADDED/2-texel[1])*TEXEL, 0))


def main():
    script_arguments = sys.argv[sys.argv.index("--")+1:] if "--" in sys.argv else []
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--hit-tick", type=int, required=True)
    parser.add_argument("--name")
    parser.add_argument("--tool", default="tc_broad_axe")
    parser.add_argument("--verify", action="store_true")
    arguments = parser.parse_args(script_arguments)
    source = Path(bpy.data.filepath)
    if not source.is_file():
        parser.error("Open the source .blend before running this exporter")
    name = arguments.name or source.stem
    source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    scene = bpy.context.scene
    fps = scene.render.fps / scene.render.fps_base
    if not math.isclose(fps, 20):
        raise RuntimeError("Attack clips must be authored at 20 FPS (one frame per game tick)")
    required_names = {"Bone_A", *PARTS.values(), arguments.tool}
    required_names.update(part+"_bend" for part in PARTS.values() if part != "head")
    missing = required_names-set(bpy.data.objects.keys())
    if missing:
        raise RuntimeError("Missing rig objects: " + str(sorted(missing)))
    keyed_frames = set()
    for obj in bpy.data.objects:
        animation = obj.animation_data
        if animation is None:
            continue
        if any(track.strips for track in animation.nla_tracks):
            raise RuntimeError("NLA timeline is not supported: " + obj.name)
        keyed_frames.update(float(key.co.x) for curve in curves(animation.action) for key in curve.keyframe_points)
    end_frame = max(keyed_frames)
    if scene.frame_start != 0 or end_frame != round(end_frame) or end_frame < 4:
        raise RuntimeError("Clip must start at frame 0 and end on a whole frame")
    last_tick = round(end_frame)
    if not 1 <= arguments.hit_tick < last_tick:
        parser.error("--hit-tick must lie inside the clip")
    end_tick = 2*arguments.hit_tick

    frame_set(0)
    root = bpy.data.objects["Bone_A"]
    origin = root.matrix_world.to_translation().copy()
    front = bpy.data.objects["torso"].matrix_world.to_3x3().normalized() @ Vector((0, 1, 0))
    heading = round(math.atan2(-front.x, -front.y)/(math.pi/2))*(math.pi/2)
    undo_yaw = Matrix.Rotation(heading, 3, "Z")
    to_model = (Matrix.Translation(HIP_OFFSET) @ Matrix.Scale(PIXELS_PER_UNIT, 4)
                @ (W @ undo_yaw).to_4x4() @ Matrix.Translation(-origin))
    bindings = []
    for part, obj_name in PARTS.items():
        rest_inverse = bpy.data.objects[obj_name].matrix_world.inverted()
        pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
        local_points = []
        for mesh_name in ([obj_name] if part == "head" else [obj_name, obj_name+"_bend"]):
            obj = bpy.data.objects[mesh_name]
            relative = rest_inverse @ obj.matrix_world
            points = [(Vector(vertex.co), PIXELS_PER_UNIT*(H @ ((relative @ vertex.co)-pivot)))
                      for vertex in obj.data.vertices]
            bindings.append((mesh_name, part, points))
            local_points.extend(point for _, point in points)
        for axis in range(3):
            low, high = REST_BOUNDS[part][0][axis], REST_BOUNDS[part][1][axis]
            if (abs(min(point[axis] for point in local_points)-low) > 0.0001
                    or abs(max(point[axis] for point in local_points)-high) > 0.0001):
                raise RuntimeError("Source mesh does not match humanoid_v1 geometry: " + part)
    tool = bpy.data.objects[arguments.tool]
    tool_points = [Vector(vertex.co) for vertex in tool.data.vertices]

    # Item chain in model pixels. ItemInHandLayer: X-90, Y180, translate (1/16, 0.125, -0.625) blocks.
    hand_offset = rotation("X", -90) @ rotation("Y", 180) @ Matrix.Translation(Vector((1, 2, -10)))
    display = (Matrix.Translation(DISPLAY_TRANSLATION) @ rotation("X", DISPLAY_ROTATION[0])
               @ rotation("Y", DISPLAY_ROTATION[1]) @ rotation("Z", DISPLAY_ROTATION[2]))
    # Large quads put sprite texel (x, y) at (2u + (ox-8)/32, 2(1-v) + (-oy-8)/32) blocks and renderItem
    # recentres by -0.5 block, so one texel is one item pixel and the padded-texture centre of the Blender
    # mesh sits at this constant offset. The display scale then brings item texels to TEXEL density.
    shift = Vector((PADDED/2-9+(LARGE_OFFSET[0]-8)/2, 25-PADDED/2+(-LARGE_OFFSET[1]-8)/2, 0))
    item_mesh = Matrix.Scale(DISPLAY_SCALE, 4) @ Matrix.Translation(shift) @ Matrix.Scale(1/TEXEL, 4)
    from_item_mesh = item_mesh.inverted() @ display.inverted()
    head_local = texel_local(HEAD_TEXEL)

    moves = []
    previous = {}
    max_part_error = 0.0
    max_item_error = 0.0
    head_track = []
    front_track = []
    for tick in range(last_tick+1):
        frame_set(tick)
        graph = bpy.context.evaluated_depsgraph_get()
        move = {"tick": tick, "easing": "linear"}
        matrices = {}
        for part, obj_name in PARTS.items():
            world = bpy.data.objects[obj_name].evaluated_get(graph).matrix_world
            pivot = BODY_PIVOT if part == "body" else Vector((0, 0, 0))
            position, quaternion = pose(world, origin, undo_yaw, pivot)
            previous[part], move[EMOTE_PARTS[part]] = emote_values(part_matrix(position, quaternion), previous.get(part))
            matrices[part] = emote_matrix(move[EMOTE_PARTS[part]])
        for mesh_name, part, points in bindings:
            world = bpy.data.objects[mesh_name].evaluated_get(graph).matrix_world
            for source_local, model_local in points:
                actual = to_model @ (world @ source_local)
                max_part_error = max(max_part_error, (actual-matrices[part] @ model_local).length)
        tool_model = to_model @ tool.evaluated_get(graph).matrix_world
        if any(abs(value-PIXELS_PER_UNIT) > 1e-4 for value in tool_model.to_scale()):
            raise RuntimeError("Tool mesh must stay rigid (unit world scale)")
        arm_hand = matrices["rightArm"] @ hand_offset
        right_item = arm_hand.inverted() @ tool_model @ from_item_mesh
        previous["rightItem"], move["rightItem"] = emote_values(right_item, previous.get("rightItem"))
        rendered = arm_hand @ emote_matrix(move["rightItem"]) @ display @ item_mesh
        for point in tool_points:
            max_item_error = max(max_item_error, ((tool_model @ point)-(rendered @ point)).length)
        head_track.append(tool_model @ head_local)
        front_track.append(round(-head_track[-1].z, 1))
        moves.append(move)
    if max_part_error > 0.001 or max_item_error > 0.01:
        raise RuntimeError("Reproduction failed: parts=" + str(max_part_error) + ", item=" + str(max_item_error))
    blade_speed = [round((head_track[index+1]-head_track[index]).length, 2) for index in range(last_tick)]
    document = {
        "version": 3,
        "name": name,
        "author": "tools/export_bettercombat_attack.py",
        "description": "Baked from " + source.name + " (sha256 " + source_hash[:16] + ")",
        "emote": {
            "isLoop": "false",
            "returnTick": 0,
            "beginTick": BEGIN_TICK,
            "endTick": end_tick,
            "stopTick": max(end_tick, last_tick)+math.ceil(end_tick/2),
            "degrees": False,
            "moves": moves,
        },
    }
    repository = Path(__file__).resolve().parents[1]
    target = repository / "src/main/resources/assets/bettercombat/player_animations" / (name+".json")
    encoded = json.dumps(document, indent=1)+"\n"
    if arguments.verify:
        if json.loads(target.read_text(encoding="utf-8")) != json.loads(encoded):
            raise RuntimeError("Saved animation does not reproduce source: " + str(target))
    else:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(encoded, encoding="utf-8")
    if hashlib.sha256(source.read_bytes()).hexdigest() != source_hash:
        raise RuntimeError("Source blend unexpectedly changed")
    print("VERIFIED" if arguments.verify else "EXPORTED", json.dumps({
        "animation": "bettercombat:"+name, "source_sha256": source_hash, "last_key_tick": last_tick,
        "hit_tick": arguments.hit_tick, "end_tick": end_tick, "heading_degrees": math.degrees(heading),
        "stop_tick": document["emote"]["stopTick"], "max_part_error_px": max_part_error,
        "max_item_error_px": max_item_error, "blade_speed_px_per_tick": blade_speed,
        "blade_forward_px": front_track}))


if __name__ == "__main__":
    main()
