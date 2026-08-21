package com.fiercerbattles;

import com.fiercerbattles.config.FiercerBattlesConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import java.lang.reflect.Method;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

public final class FiercerBattlesRuntime {
    private FiercerBattlesRuntime() {}

    private static String currentAnimation = "";
    private static FiercerBattlesConfig.Displacement displacement = null;
    private static int displacementStartTick = 0;
    private static boolean displacementActive = false;
    private static int recoveryUntil = 0;
    private static double currentRangeBonus = 0;
    private static double currentAfterCooldown = 0;
    private static float currentAttackSpeedMultiplier = 1.0F;
    private static FiercerBattlesCustomData.AttackData currentCustomData = null;
    private static FiercerBattlesCustomData.MultipleHits currentMultipleHits = null;
    private static int multipleHitsStartTick = 0;
    private static int nextMultipleHitIndex = 0;
    private static int executingMultipleHitIndex = -1;
    public static void prepareAttack(String animation, String itemId, int attackIndex) {
        currentAnimation = animation != null ? animation : "";
        tryLoadCustomDataFromClient();

        currentCustomData = FiercerBattlesCustomData.get(itemId, attackIndex);
        float attackSpeed = 0;
        if (currentCustomData != null && currentCustomData.attackSpeedMultiplier != 0) {
            attackSpeed = currentCustomData.attackSpeedMultiplier;
        } else {
            Float configSpeed = FiercerBattlesConfig.getAttackSpeedMultiplier(currentAnimation);
            if (configSpeed != null) {
                attackSpeed = configSpeed;
            }
        }
        currentAttackSpeedMultiplier = attackSpeed == 0 ? 1.0F : attackSpeed;
    }

    public static void prepareCombatSkill(String animation, FiercerBattlesCustomData.AttackData customData) {
        currentAnimation = animation != null ? animation : "";
        currentCustomData = customData;
        float attackSpeed = 0;
        if (customData != null && customData.attackSpeedMultiplier != 0) {
            attackSpeed = customData.attackSpeedMultiplier;
        } else {
            Float configSpeed = FiercerBattlesConfig.getAttackSpeedMultiplier(currentAnimation);
            if (configSpeed != null) {
                attackSpeed = configSpeed;
            }
        }
        currentAttackSpeedMultiplier = attackSpeed == 0 ? 1.0F : attackSpeed;
    }
    public static void setCurrentAnimation(String animation, int startTick) {
        currentAnimation = animation != null ? animation : "";

        FiercerBattlesCustomData.AttackData custom = currentCustomData;
        if (custom != null && custom.hasDisplacement) {
            displacement = new FiercerBattlesConfig.Displacement(
                    custom.distance,
                    custom.durationTicks
            );
        } else {
            displacement = FiercerBattlesConfig.getDisplacement(currentAnimation);
        }
        currentMultipleHits = custom != null && custom.multipleHits != null
                ? custom.multipleHits
                : FiercerBattlesConfig.getMultipleHits(currentAnimation);
        multipleHitsStartTick = startTick;
        nextMultipleHitIndex = 0;        displacementStartTick = startTick;
        displacementActive = displacement != null;

        currentRangeBonus = custom != null ? custom.rangeBonus : 0;
        if (currentRangeBonus == 0) {
            Double configRangeBonus = FiercerBattlesConfig.getRangeBonus(currentAnimation);
            if (configRangeBonus != null) {
                currentRangeBonus = configRangeBonus;
            }
        }

        currentAfterCooldown = custom != null ? custom.afterCooldown : 0;
        if (currentAfterCooldown == 0) {
            Double configAfterCooldown = FiercerBattlesConfig.getAfterCooldown(currentAnimation);
            if (configAfterCooldown != null) {
                currentAfterCooldown = configAfterCooldown;
            }
        }

        if (currentAfterCooldown > 0) {
            // Rough approximation: one full cooldown (20 ticks) + configured extra seconds.
            recoveryUntil = startTick + 20 + (int) Math.ceil(currentAfterCooldown * 20.0);
        } else {
            recoveryUntil = 0;
        }
    }

    public static void cancelMultipleHits() {
        currentMultipleHits = null;
        nextMultipleHitIndex = 0;
        executingMultipleHitIndex = -1;
    }
    public static boolean shouldSuppressDefaultAttack() {
        return currentMultipleHits != null && !isExecutingMultipleHit();
    }

    public static boolean isExecutingMultipleHit() {
        return executingMultipleHitIndex >= 0;
    }

    public static int getExecutingMultipleHitIndex() {
        return executingMultipleHitIndex;
    }

    public static boolean isExecutingFinalMultipleHit() {
        return currentMultipleHits != null && executingMultipleHitIndex == currentMultipleHits.frames.length - 1;
    }

    public static void tickMultipleHits(LocalPlayer player) {
        if (currentMultipleHits == null || nextMultipleHitIndex >= currentMultipleHits.frames.length) {
            return;
        }
        int elapsed = player.tickCount - multipleHitsStartTick;
        while (nextMultipleHitIndex < currentMultipleHits.frames.length
                && elapsed >= currentMultipleHits.frames[nextMultipleHitIndex]) {
            executingMultipleHitIndex = nextMultipleHitIndex;
            try {
                invokePerformAttack();
            } finally {
                executingMultipleHitIndex = -1;
            }
            nextMultipleHitIndex += 1;
        }
    }

    private static void invokePerformAttack() {
        Minecraft minecraft = Minecraft.getInstance();
        try {
            for (Method method : minecraft.getClass().getDeclaredMethods()) {
                if (method.getName().equals("performAttack") && method.getParameterCount() == 0) {
                    method.setAccessible(true);
                    method.invoke(minecraft);
                    return;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }
    public static double getCurrentRangeBonus() {
        return currentRangeBonus;
    }

    public static boolean isInRecovery(int currentTick) {
        return recoveryUntil > 0 && currentTick < recoveryUntil;
    }

    public static String getCurrentAnimation() {
        return currentAnimation;
    }

    public static float getCurrentAttackSpeedMultiplier() {
        return currentAttackSpeedMultiplier == 0 ? 1.0F : currentAttackSpeedMultiplier;
    }

    public static float getAnimationSpeed() {
        // Attack speed and animation playback are linked by BetterCombat itself:
        // changing getAttackCooldownTicksCapped already changes the animation speed.
        return 1.0F;
    }

    private static void tryLoadCustomDataFromClient() {
        if (FiercerBattlesCustomData.isEmpty()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getSingleplayerServer() != null) {
                FiercerBattlesCustomData.load(minecraft.getSingleplayerServer().getResourceManager());
            }
        }
    }

    public static void tickDisplacement(LocalPlayer player) {
        if (!displacementActive || displacement == null) {
            return;
        }
        int elapsed = player.tickCount - displacementStartTick;
        if (elapsed < 0) {
            return;
        }
        int durationTicks = (int) Math.max(1, Math.ceil(displacement.durationTicks));
        if (elapsed >= durationTicks) {
            displacementActive = false;
            return;
        }
        double step = displacement.distance / displacement.durationTicks;
        Vec3 look = player.getLookAngle();
        player.move(MoverType.SELF, new Vec3(look.x * step, 0, look.z * step));
    }
}
