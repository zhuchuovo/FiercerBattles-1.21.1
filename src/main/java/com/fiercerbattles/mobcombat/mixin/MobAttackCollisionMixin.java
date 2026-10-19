package com.fiercerbattles.mobcombat.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Targets close to a mob always count as inside its Better Combat attack shape.
 *
 * Better Mob Combat judges both attack starts and impact hits with an oriented
 * hitbox built from the mob's body rotation, and that shape has a dead band for
 * nearby targets: the direction test degenerates at near-zero distance, and
 * short-ranged weapons leave a gap between a fixed near zone and where the
 * shape starts to cover - which is exactly where a kiting skeleton parks. A
 * target inside ~95% of the weapon's own range is touched by any swing
 * regardless of orientation, so the check short-circuits to true there.
 * Beyond that the weapon's JSON shape decides, unchanged.
 */
@Mixin(targets = "com.fiercerbattles.mobcombat.logic.MobAttackCollision", remap = false)
public abstract class MobAttackCollisionMixin {

    /** Extra blocks beyond the weapon range kept in the always-hit zone, absorbing measurement slack. */
    private static final double NEAR_ZONE_MARGIN = 0.25D;
    /** Floor for empty hands and weapons without attributes. */
    private static final double NEAR_ZONE_MIN = 1.5D;
    /** Vertical offset still considered the same standing height. */
    private static final double NEAR_ZONE_HEIGHT = 1.0D;

    @Inject(method = "intersects", at = @At("HEAD"), cancellable = true)
    private static void fiercerbattles$nearbyAlwaysHits(
            Mob mob, Entity target, @Coerce Object attack, double range, CallbackInfoReturnable<Boolean> cir) {
        if (!(target instanceof LivingEntity) || target == mob) {
            return;
        }
        AABB own = mob.getBoundingBox();
        AABB other = target.getBoundingBox();
        double dx = Math.max(0.0D, Math.max(own.minX - other.maxX, other.minX - own.maxX));
        double dy = Math.max(0.0D, Math.max(own.minY - other.maxY, other.minY - own.maxY));
        double dz = Math.max(0.0D, Math.max(own.minZ - other.maxZ, other.minZ - own.maxZ));
        double nearZone = Math.max(NEAR_ZONE_MIN, range + NEAR_ZONE_MARGIN);
        if (dy <= NEAR_ZONE_HEIGHT && Math.sqrt(dx * dx + dz * dz) <= nearZone) {
            cir.setReturnValue(true);
        }
    }
}
