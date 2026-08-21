package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import com.fiercerbattles.FiercerBattlesRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;

/**
 * BetterCombat client hooks.
 *
 * The targeted methods (startUpswing, getCurrentHand, performAttack) are
 * merged into Minecraft by BetterCombat's own mixin (priority 1000).
 * Injecting into mixin-merged methods requires a higher priority, so this
 * mixin applies after BetterCombat's.
 */
@Mixin(targets = "net.minecraft.client.Minecraft", priority = 2000)
public abstract class MinecraftMixin {

    @Inject(method = "startUpswing", at = @At("HEAD"), remap = false, cancellable = true)
    private void fiercerbattles$blockDuringRecovery(@Coerce Object attributes, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        FiercerBattlesCombatSkills.clearActiveClientSkillBeforeNormalAttack();
        if (minecraft.player != null && FiercerBattlesRuntime.isInRecovery(minecraft.player.tickCount)) {
            ci.cancel();
        }
    }

    @Inject(method = "getCurrentHand", at = @At("RETURN"), remap = false)
    private void fiercerbattles$captureSelectedAttack(CallbackInfoReturnable<Object> cir) {
        Object hand = cir.getReturnValue();
        if (hand == null) {
            return;
        }
        Object skillHand = FiercerBattlesCombatSkills.replaceArmedClientHand(hand);
        boolean isCombatSkill = skillHand != hand;
        hand = skillHand;
        if (isCombatSkill) {
            cir.setReturnValue(hand);
        }
        try {
            Object attack = hand.getClass().getMethod("attack").invoke(hand);
            if (attack == null) {
                return;
            }
            Object animation = attack.getClass().getMethod("animation").invoke(attack);
            if (animation instanceof String name) {
                if (isCombatSkill) {
                    FiercerBattlesRuntime.prepareCombatSkill(name, FiercerBattlesCombatSkills.getActiveSkillCustomData());
                } else {
                    FiercerBattlesRuntime.prepareAttack(name, itemId(hand), attackIndex(hand, attack));
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /**
     * Resolves the item key that FiercerBattlesCustomData is keyed by:
     * the BetterCombat weapon preset component if present, otherwise the
     * item's registry id (the same rule BetterCombat uses to resolve
     * attributes for the stack).
     */
    private static String itemId(Object hand) {
        try {
            Object stack = hand.getClass().getMethod("itemStack").invoke(hand);
            if (stack instanceof ItemStack itemStack) {
                String preset = presetId(itemStack);
                if (preset != null) {
                    return preset;
                }
                return BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return null;
    }

    private static String presetId(ItemStack stack) {
        try {
            Class<?> componentClass = Class.forName("net.bettercombat.api.component.BetterCombatDataComponents");
            Object componentType = componentClass.getField("WEAPON_PRESET_ID").get(null);
            Object value = stack.getClass()
                    .getMethod("get", Class.forName("net.minecraft.core.component.DataComponentType"))
                    .invoke(stack, componentType);
            if (value instanceof ResourceLocation id) {
                return id.toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Finds the index of the selected attack inside the weapon's attacks
     * array, so per-attack custom data (combo steps) can be looked up.
     * Falls back to the hand's combo state (1-based) minus one.
     */
    private static int attackIndex(Object hand, Object attack) {
        try {
            Object attributes = hand.getClass().getMethod("attributes").invoke(hand);
            if (attributes != null) {
                Field attacksField = attributes.getClass().getDeclaredField("attacks");
                attacksField.setAccessible(true);
                Object[] attacks = (Object[]) attacksField.get(attributes);
                if (attacks != null) {
                    for (int i = 0; i < attacks.length; i++) {
                        if (attacks[i] == attack) {
                            return i;
                        }
                    }
                }
            }
            Object combo = hand.getClass().getMethod("combo").invoke(hand);
            if (combo != null) {
                Object current = combo.getClass().getMethod("current").invoke(combo);
                if (current instanceof Integer integer) {
                    return integer - 1;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return -1;
    }

    @Inject(method = "cancelWeaponSwing", at = @At("HEAD"), remap = false)
    private void fiercerbattles$cancelMultipleHits(CallbackInfo ci) {
        FiercerBattlesRuntime.cancelMultipleHits();
    }
    @Inject(method = "performAttack", at = @At("HEAD"), cancellable = true, remap = false)
    private void fiercerbattles$replaceDefaultAttackWithMultipleHits(CallbackInfo ci) {
        if (FiercerBattlesRuntime.shouldSuppressDefaultAttack()) {
            ci.cancel();
        }
    }
    @ModifyArg(
            method = "performAttack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/bettercombat/network/Packets$C2S_AttackRequest;<init>(IZILnet/minecraft/world/entity/Entity;Ljava/util/List;)V"
            ),
            index = 0,
            remap = false
    )
    private int fiercerbattles$markCombatSkillPacket(int comboCount) {
        return FiercerBattlesCombatSkills.encodeAttackPacket(
                comboCount,
                FiercerBattlesRuntime.getExecutingMultipleHitIndex(),
                FiercerBattlesRuntime.isExecutingFinalMultipleHit()
        );
    }

    @ModifyArg(
            method = "performAttack",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setComboCount(I)V"),
            index = 0,
            remap = false
    )
    private int fiercerbattles$keepComboForMultipleHits(int nextComboCount) {
        return FiercerBattlesRuntime.isExecutingMultipleHit() ? nextComboCount - 1 : nextComboCount;
    }
    @Redirect(method = "startUpswing", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"), remap = false)
    private float fiercerbattles$modifyStartProgress(LocalPlayer player, float ticks) {
        return modifyAttackProgress(player, ticks);
    }

    @Redirect(method = "performAttack", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getAttackStrengthScale(F)F"), remap = false)
    private float fiercerbattles$modifyHitProgress(LocalPlayer player, float ticks) {
        return modifyAttackProgress(player, ticks);
    }

    private static float modifyAttackProgress(Player player, float ticks) {
        if (FiercerBattlesRuntime.isExecutingMultipleHit()) {
            return 1.0F;
        }
        float progress = player.getAttackStrengthScale(ticks);
        float multiplier = FiercerBattlesRuntime.getCurrentAttackSpeedMultiplier();
        if (multiplier > 1.0F) {
            return Math.min(1.0F, progress * multiplier);
        }
        return progress;
    }
}
