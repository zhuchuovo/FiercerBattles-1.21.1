package com.fiercerbattles.boss.client;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.boss.IronSentinel;
import com.fiercerbattles.boss.IronSentinelAction;
import com.fiercerbattles.boss.IronSentinelPoseState;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/**
 * Samples the Iron Sentinel's current clip on the shared world clock, at the action's playback speed. When the
 * server switches action the pose drawn last becomes the start of a blend, as long as the synced blend ticks (the
 * chained combo segments use it for their move to the next segment's frame 0).
 */
final class IronSentinelPoser {
    private IronSentinelPoser() {}

    static Map<String, AnimationClip.Transform> pose(IronSentinel sentinel, float partialTick) {
        IronSentinelAction action = sentinel.getAction();
        AnimationClip clip = IronSentinelAssets.clip(action.clip);
        if (clip == null) return Map.of();
        IronSentinelPoseState state = sentinel.poseState;
        double now = sentinel.level().getGameTime() + (double) partialTick;
        long start = sentinel.getActionStart();
        if (state.action != action.ordinal() || state.actionStart != start) {
            state.blendFrom = state.lastPose.isEmpty() ? Map.of() : Map.copyOf(state.lastPose);
            state.blendStart = now;
            state.blendTicks = state.action < 0 ? 0 : sentinel.getActionBlend();
            state.action = action.ordinal();
            state.actionStart = start;
        }
        float time = clipTime(sentinel, action, clip, partialTick);
        float blend = state.blendTicks <= 0 ? 1.0F : (float) Mth.clamp((now - state.blendStart) / state.blendTicks, 0.0, 1.0);
        blend = blend * blend * (3.0F - 2.0F * blend);
        Map<String, AnimationClip.Transform> result = new HashMap<>();
        for (String channel : clip.channels()) {
            AnimationClip.Transform target = clip.sample(channel, time);
            AnimationClip.Transform from = blend < 1.0F ? state.blendFrom.get(channel) : null;
            result.put(channel, from == null ? target : mix(from, target, blend));
        }
        state.lastPose.clear();
        state.lastPose.putAll(result);
        return result;
    }

    private static float clipTime(IronSentinel sentinel, IronSentinelAction action, AnimationClip clip, float partialTick) {
        double elapsed = sentinel.actionTime(partialTick);
        return switch (action.playback) {
            // The cycle follows the distance actually walked, so planted feet do not slide.
            case LOOP -> (float) ((sentinel.walkDistance(partialTick) / IronSentinel.WALK_CLIP_SPEED) % clip.duration());
            case CYCLE -> (float) (Math.max(0.0, elapsed) % clip.duration());
            // The cannon's shot frames, once per firing period.
            case FIRE -> (float) (IronSentinel.CANNON_FIRE_FRAME + Math.max(0.0, elapsed)
                    * IronSentinel.CANNON_FIRE_CLIP_FRAMES / IronSentinel.CANNON_FIRE_PERIOD % IronSentinel.CANNON_FIRE_CLIP_FRAMES);
            case ONCE -> (float) Mth.clamp(sentinel.clipTime(partialTick), 0.0, clip.duration());
        };
    }

    private static AnimationClip.Transform mix(AnimationClip.Transform a, AnimationClip.Transform b, float t) {
        return new AnimationClip.Transform(Mth.lerp(t, a.x(), b.x()), Mth.lerp(t, a.y(), b.y()), Mth.lerp(t, a.z(), b.z()),
                new Quaternionf(a.rotation()).slerp(b.rotation(), t));
    }
}
