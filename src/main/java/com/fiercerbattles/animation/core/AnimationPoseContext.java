package com.fiercerbattles.animation.core;

import java.util.ArrayDeque;
import java.util.Deque;

/** Render-thread scope supplied by a playback owner; core sampling is independent of that owner. */
public final class AnimationPoseContext {
    @FunctionalInterface
    public interface PoseSource {
        AnimationClip.Transform sample(String channel, AnimationClip.Transform restPose);
        default float blendProgress() { return 0; }
    }

    private static final ThreadLocal<Deque<PoseSource>> SOURCES = ThreadLocal.withInitial(ArrayDeque::new);
    private AnimationPoseContext() {}

    public static void begin(PoseSource source) { SOURCES.get().push(source); }
    public static void end() {
        Deque<PoseSource> sources = SOURCES.get();
        if (!sources.isEmpty()) sources.pop();
        if (sources.isEmpty()) SOURCES.remove();
    }
    public static AnimationClip.Transform sample(String channel, AnimationClip.Transform restPose) {
        PoseSource source = SOURCES.get().peek();
        return source == null ? null : source.sample(channel, restPose);
    }
    public static float blendProgress() {
        PoseSource source = SOURCES.get().peek();
        return source == null ? 0 : source.blendProgress();
    }
}
