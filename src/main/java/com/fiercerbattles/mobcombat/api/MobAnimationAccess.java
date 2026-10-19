package com.fiercerbattles.mobcombat.api;

import dev.kosmx.playerAnim.api.layered.AnimationStack;


public interface MobAnimationAccess {
    AnimationStack bmc$getAnimationStack();

    float bmc$getRenderPartialTick();

    void bmc$setRenderPartialTick(float partialTick);

    boolean bmc$isAttackAnimationActive();

    boolean bmc$isArmAnimationActive();

    boolean bmc$isTwoHandedArmAnimationActive();

    boolean bmc$isOffHandAttackAnimationActive();

    boolean bmc$shouldForceAttackItemVisible();

    /** Drops the current attack animation at once, e.g. when the mob flinches or is parry-stunned. */
    void bmc$stopAttackAnimation();

    void bmc$playAttackAnimation(
            String animationId,
            boolean offHand,
            boolean twoHanded,
            float length,
            float animationUpswing,
            float damageUpswing
    );
}
