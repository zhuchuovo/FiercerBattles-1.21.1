package com.fiercerbattles.boss.client;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.boss.ZombieLord;
import com.fiercerbattles.boss.ZombieLordAction;
import com.fiercerbattles.boss.ZombieLordPoseState;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;

import java.util.HashMap;
import java.util.Map;

/**
 * Samples the boss's current clip on the shared world clock. When the server switches action the
 * pose drawn last becomes the start of a blend, as long as the synced blend ticks; the chain from
 * attack1 into attack2 uses this for its 5 tick move to attack2's frame 0.
 */
final class ZombieLordPoser {
    private ZombieLordPoser() {}

    static Map<String, AnimationClip.Transform> pose(ZombieLord lord, float partialTick) {
        boolean dying = lord.isDeadOrDying();
        ZombieLordAction action = dying ? ZombieLordAction.DEATH : lord.getAction();
        AnimationClip clip = ZombieLordAssets.clip(action.clip);
        if (clip == null) return Map.of();
        ZombieLordPoseState state = lord.poseState;
        double now = lord.level().getGameTime() + (double) partialTick;
        // The death clip runs on the entity's own death clock.
        long start = dying ? lord.level().getGameTime() - lord.deathTime : lord.getActionStart();
        if (state.action != action.ordinal() || state.actionStart != start) {
            state.blendFrom = state.lastPose.isEmpty() ? Map.of() : Map.copyOf(state.lastPose);
            state.blendStart = now;
            state.blendTicks = state.action < 0 ? 0 : dying ? 4 : lord.getActionBlend();
            state.action = action.ordinal();
            state.actionStart = start;
        }
        float time = clipTime(lord, action, clip, now - start, partialTick);
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

    private static float clipTime(ZombieLord lord, ZombieLordAction action, AnimationClip clip, double elapsed, float partialTick) {
        return switch (action.playback) {
            case LOOP -> {
                // The cycle follows the distance actually walked, so planted feet do not slide.
                yield (float) ((lord.walkDistance(partialTick) / ZombieLord.WALK_CLIP_SPEED) % clip.duration());
            }
            case CYCLE -> (float) (Math.max(0.0, elapsed) % clip.duration());
            case HOLD_AT_FREEZE -> (float) Mth.clamp(elapsed, 0.0, ZombieLord.QUAKE_FREEZE_FRAME);
            case LEAP -> (float) Mth.clamp(ZombieLord.leapClipTime(elapsed), 0.0, clip.duration());
            case DASH -> {
                if (elapsed < action.windup) yield (float) Math.max(0.0, elapsed);
                // The cycle repeats at the speed the boss actually covers ground.
                double frames = (elapsed - action.windup) * action.cycleRate;
                yield (float) (action.windup + frames % action.cycle);
            }
            case ONCE -> (float) Mth.clamp(elapsed, 0.0, clip.duration());
        };
    }

    private static AnimationClip.Transform mix(AnimationClip.Transform a, AnimationClip.Transform b, float t) {
        return new AnimationClip.Transform(Mth.lerp(t, a.x(), b.x()), Mth.lerp(t, a.y(), b.y()), Mth.lerp(t, a.z(), b.z()),
                new Quaternionf(a.rotation()).slerp(b.rotation(), t));
    }
}
