package com.fiercerbattles.execution;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** Source-space actor geometry, independent of the baked render-part transforms. */
public final class ExecutionGeometry {
    public static final int DURATION_TICKS = 56;
    public static final double VICTIM_LOCAL_RIGHT = -0.654533446 / 4.0;
    public static final double VICTIM_LOCAL_FORWARD = 5.532946110 / 4.0;
    public static final double SEPARATION = Math.hypot(VICTIM_LOCAL_RIGHT, VICTIM_LOCAL_FORWARD);

    // Evaluated A_torso hip at source frame 56, not its shoulder pivot or Euler Z.
    public static final double EXECUTOR_END_LOCAL_RIGHT = 0.0;
    public static final double EXECUTOR_END_LOCAL_FORWARD = 0.4798867106437683;
    public static final float EXECUTOR_END_YAW_DELTA = 86.124866F;

    private ExecutionGeometry() {}

    public static Vec3 horizontalOffset(double right, double forward, float yaw) {
        double angle = Math.toRadians(yaw);
        double cosine = Math.cos(angle);
        double sine = Math.sin(angle);
        return new Vec3(cosine * right - sine * forward, 0, sine * right + cosine * forward);
    }

    public static Vec3 executorEndPosition(Vec3 anchor, float yaw) {
        // Preserve the supported ground height. The source hip rises 0.031156 blocks
        // in its ending pose, which is a body animation and must not lift the feet.
        return anchor.add(horizontalOffset(EXECUTOR_END_LOCAL_RIGHT, EXECUTOR_END_LOCAL_FORWARD, yaw));
    }

    public static float executorEndYaw(float yaw) {
        return Mth.wrapDegrees(yaw + EXECUTOR_END_YAW_DELTA);
    }
}
