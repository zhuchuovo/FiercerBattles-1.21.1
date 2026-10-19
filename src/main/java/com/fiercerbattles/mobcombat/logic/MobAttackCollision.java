package com.fiercerbattles.mobcombat.logic;

import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import com.fiercerbattles.bettercombat.client.collision.CollisionHelper;
import com.fiercerbattles.bettercombat.client.collision.OrientedBoundingBox;
import com.fiercerbattles.bettercombat.client.collision.WeaponHitBoxes;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-side equivalent of Better Combat's player target test.
 *
 * <p>The broad entity query is intentionally separate. A target only receives damage when its
 * collider intersects Better Combat's oriented weapon box and its nearest point is inside the
 * configured range and attack angle.</p>
 */
public final class MobAttackCollision {
    private static final double EPSILON = 1.0E-7D;

    private MobAttackCollision() {
    }

    public static boolean intersects(Mob mob, Entity target, WeaponAttributes.Attack attack, double attackRange) {
        if (attackRange <= 0.0D) {
            return false;
        }

        AABB targetBox = target.getBoundingBox();

        if (mob.getBoundingBox().inflate(0.15D, 0.10D, 0.15D).intersects(targetBox)) {
            return true;
        }

        Vec3 origin = initialTracingPoint(mob);
        boolean spinAttack = attack.angle() > 180.0D;
        WeaponAttributes.HitBoxShape hitbox = attack.hitbox() == null
                ? WeaponAttributes.HitBoxShape.FORWARD_BOX
                : attack.hitbox();

        Vec3 size = WeaponHitBoxes.createHitbox(hitbox, attackRange, spinAttack);
        OrientedBoundingBox weaponBox = new OrientedBoundingBox(
                origin,
                size,
                mob.getXRot(),
                mob.getYRot()
        );
        if (!spinAttack) {
            weaponBox = weaponBox.offsetAlongAxisZ(size.z / 2.0D);
        }
        weaponBox.updateVertex();

        Vec3 targetCenter = target.position().add(0.0D, target.getBbHeight() * 0.5D, 0.0D);

        if (!weaponBox.intersects(targetBox)) {
            return false;
        }

        Vec3 nearestVector = CollisionHelper.distanceVector(origin, target.getBoundingBox());
        if (nearestVector.lengthSqr() > attackRange * attackRange + EPSILON) {
            return false;
        }

        double attackAngle = Mth.clamp(attack.angle(), 0.0D, 360.0D);
        if (attackAngle == 0.0D || attackAngle >= 360.0D) {
            return true;
        }

        double maximumDifference = attackAngle * 0.5D;
        Vec3 centerVector = targetCenter.subtract(origin);

        return angleWithin(nearestVector, weaponBox.axisZ, maximumDifference);
    }

    private static Vec3 initialTracingPoint(Mob mob) {
        double shoulderOffset = mob.getBbHeight() * 0.15D * mob.getScale();
        return mob.getEyePosition().subtract(0.0D, shoulderOffset, 0.0D);
    }

    private static boolean angleWithin(Vec3 vector, Vec3 orientation, double maximumDifference) {
        if (vector.lengthSqr() <= EPSILON || orientation.lengthSqr() <= EPSILON) {
            return true;
        }
        return CollisionHelper.angleBetween(vector, orientation) <= maximumDifference;
    }
}
