package com.fiercerbattles.execution;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import com.fiercerbattles.execution.action.PairedExecutionAction;

import java.util.ArrayList;
import java.util.List;

/** Finds supported, collision-free anchors while preserving the authored pair geometry. */
public final class ExecutionPlacement {
    private static final double EPSILON = 1.0E-4;
    private static final double MAX_HEIGHT_ALIGNMENT = 2.0;
    private static final double SWEEP_STEP = 0.15;
    private static final int[] ANGLE_OFFSETS = {0, 15, -15, 30, -30, 45, -45, 60, -60, 90, -90, 135, -135, 180};

    public record Placement(Vec3 executorAnchor, Vec3 victimAnchor, float executorYaw, float victimYaw) {}

    private ExecutionPlacement() {}

    private static final PairedExecutionAction.Geometry DEFAULT_GEOMETRY = new PairedExecutionAction.Geometry(
            ExecutionGeometry.VICTIM_LOCAL_RIGHT, ExecutionGeometry.VICTIM_LOCAL_FORWARD, 180,
            ExecutionGeometry.EXECUTOR_END_LOCAL_RIGHT, ExecutionGeometry.EXECUTOR_END_LOCAL_FORWARD,
            ExecutionGeometry.EXECUTOR_END_YAW_DELTA);

    public static boolean canReach(ServerPlayer executor, Mob victim, double range) {
        Vec3 eye = executor.getEyePosition();
        AABB box = victim.getBoundingBox();
        if (box.distanceToSqr(eye) > range * range + EPSILON * EPSILON) return false;

        // The client already picked an entity. Rechecking the server's look angle races
        // the next rotation packet; validate reach and visible body space instead.
        double nearestX = Mth.clamp(eye.x, box.minX, box.maxX);
        double nearestZ = Mth.clamp(eye.z, box.minZ, box.maxZ);
        double[] heights = {Mth.clamp(eye.y, box.minY, box.maxY),
                box.minY + box.getYsize() * 0.5, box.minY + box.getYsize() * 0.25,
                box.minY + box.getYsize() * 0.75};
        for (double y : heights) {
            if (visibleWithinReach(executor, eye, new Vec3(nearestX, y, nearestZ), range)) return true;
            for (double x : new double[]{box.minX, box.maxX}) {
                for (double z : new double[]{box.minZ, box.maxZ}) {
                    if (visibleWithinReach(executor, eye, new Vec3(x, y, z), range)) return true;
                }
            }
        }
        return false;
    }

    private static boolean visibleWithinReach(ServerPlayer executor, Vec3 eye, Vec3 point, double range) {
        return eye.distanceToSqr(point) <= range * range + EPSILON * EPSILON
                && executor.level().clip(new ClipContext(eye, point,
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, executor)).getType() == HitResult.Type.MISS;
    }

    public static Placement find(ServerLevel level, ServerPlayer executor, Mob victim) {
        return find(level, executor, victim, DEFAULT_GEOMETRY);
    }

    public static Placement find(ServerLevel level, ServerPlayer executor, Mob victim,
                                 PairedExecutionAction.Geometry geometry) {
        if (Math.abs(executor.getY() - victim.getY()) > MAX_HEIGHT_ALIGNMENT + EPSILON) return null;
        Vec3 direction = new Vec3(victim.getX() - executor.getX(), 0, victim.getZ() - executor.getZ());
        if (direction.lengthSqr() < EPSILON * EPSILON) {
            direction = Vec3.directionFromRotation(0, executor.getYRot());
        }
        float preferredYaw = (float) Math.toDegrees(Math.atan2(-direction.x, direction.z));
        // Try the original target location first, then move the whole pair locally.
        // Neither actor can change the authored relative offset independently.
        List<Vec3> shifts = new ArrayList<>();
        shifts.add(Vec3.ZERO);
        for (double radius : new double[]{0.5, 1.0}) {
            for (int angle = 0; angle < 360; angle += 45) {
                shifts.add(ExecutionGeometry.horizontalOffset(0, radius, preferredYaw + angle));
            }
        }
        for (Vec3 shift : shifts) for (int angle : ANGLE_OFFSETS) {
            float approachYaw = preferredYaw + angle;
            float executorYaw = Mth.wrapDegrees(approachYaw
                    - (float) Math.toDegrees(Math.atan2(-geometry.victimRight(), geometry.victimForward())));
            Vec3 target = victim.position().add(shift);
            Vec3 offset = geometry.victimOffset(executorYaw);
            for (double height : groundHeights(level, executor, victim, target, target.subtract(offset))) {
                Vec3 victimAnchor = new Vec3(target.x, height, target.z);
                // Derive the second anchor from the same authored offset used by rendering.
                Vec3 executorAnchor = victimAnchor.subtract(offset);
                AABB executorBox = executor.getDimensions(Pose.STANDING).makeBoundingBox(executorAnchor);
                AABB victimBox = victim.getDimensions(Pose.STANDING).makeBoundingBox(victimAnchor);
                if (safeBox(level, executor, victim, executorBox) && safeBox(level, victim, executor, victimBox)
                        && supported(level, executor, executorBox) && supported(level, victim, victimBox)
                        && clearStage(level, executor, executorBox, victimBox)
                        && safeTravel(level, executor, victim, executorBox)
                        && safeTravel(level, victim, executor, victimBox)
                        && safeFinishPlacement(level, executor, victim, executorBox, executorAnchor,
                        executorYaw, geometry)) {
                    return new Placement(executorAnchor, victimAnchor, executorYaw,
                            Mth.wrapDegrees(executorYaw + geometry.victimYawDelta()));
                }
            }
        }
        return null;
    }

    private static List<Double> groundHeights(ServerLevel level, ServerPlayer executor, Mob victim,
                                               Vec3 victimAnchor, Vec3 executorAnchor) {
        List<Double> heights = new ArrayList<>();
        addHeight(heights, victim.getY());
        addHeight(heights, executor.getY());
        double minY = Math.min(executor.getY(), victim.getY());
        double maxY = Math.max(executor.getY(), victim.getY());
        // Stairs and slabs can offer a shared surface between the two original foot heights.
        for (Vec3 anchor : new Vec3[]{victimAnchor, executorAnchor}) {
            AABB column = new AABB(anchor.x - 0.3, minY - EPSILON, anchor.z - 0.3,
                    anchor.x + 0.3, maxY + EPSILON, anchor.z + 0.3);
            for (var shape : level.getBlockCollisions(executor, column)) {
                for (AABB box : shape.toAabbs()) {
                    if (box.maxY >= minY - EPSILON && box.maxY <= maxY + EPSILON) addHeight(heights, box.maxY);
                }
            }
        }
        return heights;
    }

    private static void addHeight(List<Double> heights, double height) {
        if (heights.stream().noneMatch(existing -> Math.abs(existing - height) < EPSILON)) heights.add(height);
    }

    private static boolean safeTravel(ServerLevel level, LivingEntity actor, Entity partner, AABB destination) {
        AABB origin = actor.getDimensions(Pose.STANDING).makeBoundingBox(actor.position());
        // Step up before crossing a ledge, or step down after crossing it. Both paths
        // must clear blocks, liquids and other entities, including overhead obstacles.
        double travelY = Math.max(origin.minY, destination.minY);
        AABB raisedOrigin = origin.move(0, travelY - origin.minY, 0);
        AABB raisedDestination = destination.move(0, travelY - destination.minY, 0);
        return safeBox(level, actor, partner, origin.minmax(raisedOrigin))
                && safeSweep(level, actor, partner, raisedOrigin, raisedDestination)
                && safeBox(level, actor, partner, raisedDestination.minmax(destination));
    }

    private static boolean safeSweep(ServerLevel level, LivingEntity actor, Entity partner, AABB start, AABB end) {
        Vec3 movement = end.getCenter().subtract(start.getCenter());
        int steps = Math.max(1, (int) Math.ceil(movement.length() / SWEEP_STEP));
        AABB previous = start;
        for (int i = 1; i <= steps; i++) {
            AABB next = start.move(movement.scale((double) i / steps));
            // Short continuous sweeps avoid counting distant corners of a diagonal
            // axis-aligned union as part of the actor's actual movement path.
            if (!safeBox(level, actor, partner, previous.minmax(next))) return false;
            previous = next;
        }
        return true;
    }

    private static boolean safeBox(ServerLevel level, LivingEntity actor, Entity partner, AABB box) {
        if (!level.getWorldBorder().isWithinBounds(box) || !level.hasChunkAt(BlockPos.containing(box.getCenter()))
                || !level.noBlockCollision(actor, box.deflate(EPSILON)) || level.containsAnyLiquid(box)) return false;
        return level.getEntities(actor, box.deflate(EPSILON), entity -> entity != partner
                && entity.isAlive() && entity.isPushable()).isEmpty();
    }

    public static boolean supported(ServerLevel level, LivingEntity actor, AABB box) {
        Vec3 center = box.getCenter();
        double dx = box.getXsize() / 4, dz = box.getZsize() / 4;
        int contacts = 0;
        for (double[] point : new double[][]{{0, 0}, {-dx, -dz}, {-dx, dz}, {dx, -dz}, {dx, dz}}) {
            AABB foot = new AABB(center.x + point[0] - EPSILON, box.minY - 0.075, center.z + point[1] - EPSILON,
                    center.x + point[0] + EPSILON, box.minY + EPSILON, center.z + point[1] + EPSILON);
            if (!level.noBlockCollision(actor, foot)) contacts++;
        }
        // A tiny corner touching a ledge must not count as a stable place for a locked actor.
        return contacts >= 3;
    }

    public static boolean clearStage(ServerLevel level, LivingEntity executor, AABB executorBox, AABB victimBox) {
        Vec3 start = executorBox.getCenter(), end = victimBox.getCenter();
        Vec3 movement = new Vec3(end.x - start.x, 0, end.z - start.z);
        double halfX = Math.max(executorBox.getXsize(), victimBox.getXsize()) / 2;
        double halfZ = Math.max(executorBox.getZsize(), victimBox.getZsize()) / 2;
        double minY = Math.min(executorBox.minY, victimBox.minY) + EPSILON;
        double maxY = Math.max(executorBox.maxY, victimBox.maxY) - EPSILON;
        AABB aisle = new AABB(start.x - halfX, minY, start.z - halfZ,
                start.x + halfX, maxY, start.z + halfZ);
        int steps = Math.max(1, (int) Math.ceil(movement.length() / SWEEP_STEP));
        AABB previous = aisle;
        for (int i = 1; i <= steps; i++) {
            AABB next = aisle.move(movement.scale((double) i / steps));
            AABB swept = previous.minmax(next);
            if (!level.getWorldBorder().isWithinBounds(swept) || !level.noBlockCollision(executor, swept)) return false;
            previous = next;
        }
        return true;
    }

    public static boolean safeFinishPlacement(ServerLevel level, ServerPlayer executor, Mob victim,
                                               AABB startBox, Vec3 anchor, float yaw) {
        return safeFinishPlacement(level, executor, victim, startBox, anchor, yaw, DEFAULT_GEOMETRY);
    }

    public static boolean safeFinishPlacement(ServerLevel level, ServerPlayer executor, Mob victim,
                                               AABB startBox, Vec3 anchor, float yaw,
                                               PairedExecutionAction.Geometry geometry) {
        Vec3 displacement = geometry.executorEndPosition(anchor, yaw).subtract(anchor);
        AABB finalBox = startBox.move(displacement);
        return safeBox(level, executor, victim, finalBox) && safeSweep(level, executor, victim, startBox, finalBox)
                && supported(level, executor, finalBox);
    }
}
