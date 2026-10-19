package com.fiercerbattles.mobcombat.client;

import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.fiercerbattles.mobcombat.api.RangedWeaponKind;
import com.fiercerbattles.mobcombat.network.MobAttackPayload;
import com.fiercerbattles.mobcombat.network.MobRangedReleasePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public final class ClientPayloadHandler {
    private ClientPayloadHandler() {
    }

    public static void handleMobAttack(MobAttackPayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(payload.entityId());
        if (entity instanceof MobAnimationAccess animatedMob) {
            animatedMob.bmc$playAttackAnimation(
                    payload.animationId(),
                    payload.offHand(),
                    payload.twoHanded(),
                    payload.length(),
                    payload.animationUpswing(),
                    payload.damageUpswing()
            );
        }
    }

    public static void handleMobRangedRelease(MobRangedReleasePayload payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(payload.entityId());
        if (entity instanceof LivingEntity livingEntity) {
            RangedMobAnimator.triggerRelease(
                    livingEntity,
                    RangedWeaponKind.byId(payload.weaponKind()),
                    payload.offHand()
            );
        }
    }
}
