package com.fiercerbattles.execution;

import net.minecraft.world.phys.Vec3;

/** Shared transition timing around the unchanged, authored animation clock. */
public final class ExecutionTimeline {
    public static final int APPROACH_TICKS = 5;
    public static final int RETURN_TICKS = 5;

    private ExecutionTimeline() {}

    public static float progress(float elapsed, int ticks) {
        float linear = Math.max(0, Math.min(1, elapsed / ticks));
        return linear * linear * (3 - 2 * linear);
    }

    public static float approachProgress(long clipStart, long worldTime, float partial) {
        return progress(worldTime - clipStart + APPROACH_TICKS + partial, APPROACH_TICKS);
    }

    /** Follow the same raised path validated by ExecutionPlacement.safeTravel. */
    public static Vec3 approachPosition(Vec3 from, Vec3 anchor, float progress) {
        if (progress <= 0) return from;
        if (progress >= 1) return anchor;
        double topY = Math.max(from.y, anchor.y);
        double up = topY - from.y;
        double across = Math.hypot(anchor.x - from.x, anchor.z - from.z);
        double down = topY - anchor.y;
        double distance = (up + across + down) * progress;
        if (distance < up) return new Vec3(from.x, from.y + distance, from.z);
        distance -= up;
        if (distance < across) {
            double horizontal = distance / across;
            return new Vec3(from.x + (anchor.x - from.x) * horizontal, topY,
                    from.z + (anchor.z - from.z) * horizontal);
        }
        return new Vec3(anchor.x, topY - (distance - across), anchor.z);
    }
}
