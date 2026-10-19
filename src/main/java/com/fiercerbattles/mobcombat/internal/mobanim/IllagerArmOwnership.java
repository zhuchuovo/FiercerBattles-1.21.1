package com.fiercerbattles.mobcombat.internal.mobanim;

import com.fiercerbattles.mobcombat.api.MobAnimationAccess;
import com.fiercerbattles.mobcombat.logic.MobAttackSelector;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Vindicator;

public final class IllagerArmOwnership {
    private IllagerArmOwnership() {
    }

    public static boolean owns(LivingEntity entity) {
        return entity instanceof MobAnimationAccess access && access.bmc$isArmAnimationActive();
    }

    public static boolean suppressesVanillaAttack(LivingEntity entity) {
        return owns(entity)
                || entity instanceof Vindicator vindicator
                && vindicator.isAggressive()
                && MobAttackSelector.hasCombatWeapon(vindicator);
    }
}
