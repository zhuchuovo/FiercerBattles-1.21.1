package com.fiercerbattles.animation.core;

/** Samples a clip on a shared world-time clock so multiple entities cannot drift apart. */
public record AnimationPlayer(AnimationClip clip, long startGameTime) {
    public float tick(long gameTime, float partialTick) {
        return Math.max(0, Math.min(clip.duration(), gameTime - startGameTime + partialTick));
    }

    public AnimationClip.Transform sample(String channel, long gameTime, float partialTick) {
        return clip.sample(channel, tick(gameTime, partialTick));
    }
}
