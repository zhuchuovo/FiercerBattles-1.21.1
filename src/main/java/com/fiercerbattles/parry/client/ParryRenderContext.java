package com.fiercerbattles.parry.client;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.humanoid.AnimationModelPart;
import com.fiercerbattles.animation.rig.HumanoidRig;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** One renderer scope supplies an animation root and captures ordinary absolute model poses. */
public final class ParryRenderContext {
    private record Frame(LivingEntity entity, ParryClient.Actor actor, float partial,
                         Matrix4f root, Matrix4f inverseRoot, boolean capture,
                         Map<String, Matrix4f> captured, Map<String, ParryPoseHistory.Bend> bends) {}
    private record FirstPerson(ParryClient.Actor actor, float partial) {}
    private static final ThreadLocal<Deque<Frame>> FRAMES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final ThreadLocal<Deque<FirstPerson>> FIRST_PERSON = ThreadLocal.withInitial(ArrayDeque::new);

    private ParryRenderContext() {}

    public static void begin(LivingEntity entity, float partial, Matrix4f entryPose, boolean executionActive) {
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        boolean humanoid = renderer instanceof LivingEntityRenderer<?, ?> living
                && living.getModel() instanceof HumanoidModel<?>;
        ParryClient.Actor actor = executionActive || !humanoid ? null : ParryClient.actor(entity);
        float yaw = actor == null ? Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot) : actor.yaw(entity, partial);
        Matrix4f root = new Matrix4f(entryPose).rotateY((180 - yaw) * Mth.DEG_TO_RAD)
                .scale(-1, -1, 1).translate(0, -1.501F, 0);
        FRAMES.get().push(new Frame(entity, actor, partial, root, new Matrix4f(root).invert(),
                !executionActive && humanoid && actor == null, new HashMap<>(), new HashMap<>()));
    }

    public static void end() {
        Deque<Frame> frames = FRAMES.get();
        if (frames.isEmpty()) return;
        Frame frame = frames.pop();
        Minecraft minecraft = Minecraft.getInstance();
        if (frame.capture && minecraft.level != null) {
            ParryPoseHistory.record(frame.entity.getUUID(), minecraft.level.getGameTime(), frame.captured, frame.bends);
        }
        if (frames.isEmpty()) FRAMES.remove();
    }

    public static boolean isActive() { Frame frame = FRAMES.get().peek(); return frame != null && frame.actor != null; }
    public static float yaw() {
        Frame frame = FRAMES.get().peek();
        return frame == null || frame.actor == null ? 0 : frame.actor.yaw(frame.entity, frame.partial);
    }
    public static float ordinaryProgress() {
        Frame frame = FRAMES.get().peek();
        return frame == null || frame.actor == null ? 0 : frame.actor.ordinaryProgress(frame.partial);
    }
    public static float returnProgress() {
        Frame frame = FRAMES.get().peek();
        return frame == null || frame.actor == null ? 0 : frame.actor.returnProgress(frame.partial);
    }

    public static AnimationClip.Transform sample(String channel, AnimationClip.Transform ordinary) {
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.actor == null || frame.actor.matrixReplay(frame.partial)) return null;
        float scale = ordinaryScale(frame.entity);
        AnimationClip.Transform sampled = scaledPose(
                frame.actor.animation().sample(channel, frame.actor.tick(frame.partial)), scale);
        if (sampled == null) return null;
        return blendOrdinary(frame.entity, sampled, ordinary, frame.actor.ordinaryProgress(frame.partial));
    }

    /** Fade a clip pose already at ordinary scale (see {@link #scaledPose}) toward the vanilla pose. */
    public static AnimationClip.Transform blendOrdinary(LivingEntity entity, AnimationClip.Transform sampled,
                                                       AnimationClip.Transform ordinary, float ordinaryWeight) {
        if (ordinaryWeight <= 0) return sampled;
        float scale = ordinaryScale(entity);
        return new AnimationClip.Transform(Mth.lerp(ordinaryWeight, sampled.x(), ordinary.x() * scale),
                Mth.lerp(ordinaryWeight, sampled.y(), ordinary.y() * scale + 16 * 1.501F * (1 - scale)),
                Mth.lerp(ordinaryWeight, sampled.z(), ordinary.z() * scale),
                new Quaternionf(sampled.rotation()).slerp(ordinary.rotation(), ordinaryWeight),
                sampled.scale());
    }

    public static void capture(ModelPart part, PoseStack poses) {
        Frame frame = FRAMES.get().peek();
        if (frame == null || !frame.capture || !((Object) part instanceof AnimationModelPart identified)) return;
        String channel = identified.fiercerbattles$getAnimationChannel();
        if (channel == null || !HumanoidRig.REQUIRED_CHANNELS.contains(channel) || frame.captured.containsKey(channel)) return;
        Matrix4f relative = new Matrix4f(frame.inverseRoot).mul(poses.last().pose());
        if (relative.isFinite()) {
            frame.captured.put(channel, relative);
            frame.bends.put(channel, PlayerAnimatorBendBridge.capture(frame.entity, channel));
        }
    }

    /** Apply before mesh compilation, even when the core cancels translateAndRotate at HEAD. */
    public static void applyBend(ModelPart part) {
        if (!((Object) part instanceof AnimationModelPart identified)) return;
        String channel = identified.fiercerbattles$getAnimationChannel();
        if (channel == null || !HumanoidRig.REQUIRED_CHANNELS.contains(channel)) return;
        Frame frame = FRAMES.get().peek();
        if (frame != null && frame.actor != null) {
            PlayerAnimatorBendBridge.apply(part, frame.actor.bend(channel, frame.partial));
        } else {
            FirstPerson firstPerson = FIRST_PERSON.get().peek();
            if (firstPerson != null) PlayerAnimatorBendBridge.apply(part, firstPerson.actor.bend(channel, firstPerson.partial));
        }
    }

    public static void beginFirstPerson(ParryClient.Actor actor, float partial) {
        FIRST_PERSON.get().push(new FirstPerson(actor, partial));
    }
    public static void endFirstPerson() {
        Deque<FirstPerson> frames = FIRST_PERSON.get();
        if (!frames.isEmpty()) frames.pop();
        if (frames.isEmpty()) FIRST_PERSON.remove();
    }

    /** Matrix replay preserves the independently scaled axes which the core TRS record cannot encode. */
    public static boolean applyReplay(ModelPart part, PoseStack poses) {
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.actor == null || !frame.actor.matrixReplay(frame.partial)
                || !((Object) part instanceof AnimationModelPart identified)) return false;
        String channel = identified.fiercerbattles$getAnimationChannel();
        if (channel == null || !HumanoidRig.REQUIRED_CHANNELS.contains(channel)) return false;
        AnimationClip.Transform ordinary = new AnimationClip.Transform(part.x, part.y, part.z,
                new Quaternionf().rotationZYX(part.zRot, part.yRot, part.xRot));
        Matrix4f fallback = ordinaryMatrix(ordinary, frame.entity).scale(part.xScale, part.yScale, part.zScale);
        Matrix4f relative = frame.actor.replay(channel, fallback, frame.partial, ordinaryScale(frame.entity));
        if (relative == null || !relative.isFinite() || Math.abs(relative.determinant3x3()) < 1.0E-8F) return false;
        Matrix4f desired = new Matrix4f(frame.root).mul(relative);
        Matrix4f correction = new Matrix4f(poses.last().pose()).invert().mul(desired);
        // This method also recomputes normals for nonuniform scale; direct pose mutation would not.
        poses.mulPose(correction);
        return true;
    }

    public static Matrix4f replayBody() {
        Frame frame = FRAMES.get().peek();
        if (frame == null || frame.actor == null || !frame.actor.matrixReplay(frame.partial)) return null;
        return frame.actor.replay("body", ordinaryMatrix(new AnimationClip.Transform(0, 0, 0,
                new Quaternionf()), frame.entity), frame.partial, ordinaryScale(frame.entity));
    }

    public static Matrix4f matrix(AnimationClip.Transform pose) {
        return new Matrix4f().translation(pose.x() / 16, pose.y() / 16, pose.z() / 16)
                .rotate(pose.rotation()).scale(pose.scale());
    }

    public static Matrix4f ordinaryMatrix(AnimationClip.Transform ordinary, LivingEntity entity) {
        float scale = ordinaryScale(entity);
        return new Matrix4f().translation(ordinary.x() * scale / 16,
                ordinary.y() * scale / 16 + 1.501F * (1 - scale), ordinary.z() * scale / 16)
                .rotate(ordinary.rotation()).scale(scale);
    }

    /** Keep the ordinary entity size in the shared root, including its translated foot anchor. */
    public static AnimationClip.Transform scaledPose(AnimationClip.Transform pose, float scale) {
        if (pose == null) return null;
        return new AnimationClip.Transform(pose.x() * scale,
                pose.y() * scale + 16 * 1.501F * (1 - scale), pose.z() * scale,
                new Quaternionf(pose.rotation()), pose.scale() * scale);
    }

    public static float ordinaryScale(LivingEntity entity) {
        return (entity instanceof Player ? 0.9375F : 1.0F) * entity.getScale();
    }

    public static void clear() { FRAMES.remove(); FIRST_PERSON.remove(); }
}
