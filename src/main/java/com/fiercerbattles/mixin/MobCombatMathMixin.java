package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCustomData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Applies the weapon_attributes {@code attack_speed_multiplier} to Better Mob
 * Combat's mob attack pacing, mirroring what {@link PlayerAttackHelperMixin}
 * does for players.
 *
 * Without this, a mob (for example an AI companion) holding a weapon whose
 * datapack entry promises {@code "attack_speed_multiplier": 2.0} still swings
 * at the item's raw attack-speed attribute, which for heavy weapons is one
 * visible swing every two to three seconds once Better Mob Combat's telegraph
 * delays are added on top.
 */
@Mixin(targets = "bmc_re.better_mob_combat.logic.MobCombatMath", remap = false)
public abstract class MobCombatMathMixin {

    @Inject(method = "attackDurationTicks", at = @At("RETURN"), cancellable = true)
    private static void fiercerbattles$applyWeaponAttackSpeed(
            Mob mob, @Coerce Object hand, CallbackInfoReturnable<Float> cir) {
        float original = cir.getReturnValue();
        float result = original;
        // Better Mob Combat prefers the mob's ATTACK_SPEED entity attribute whenever it is
        // registered, and combat overhauls can leave that value zeroed mid-fight: 20 / max(0.1,
        // 0) then stretches the swing to 200 ticks. Fall back to the weapon's own attack-speed
        // modifiers in that case - the same input vanilla mobs are paced by.
        double attrSpeed = fiercerbattles$attributeAttackSpeed(mob);
        if (attrSpeed >= 0.0D && attrSpeed <= 0.1D) {
            double itemSpeed = fiercerbattles$itemAttackSpeed(hand);
            if (itemSpeed > 0.1D) {
                result = Math.max(1.0F, (float) (20.0D / itemSpeed));
            }
        }
        float multiplier = fiercerbattles$weaponAttackSpeedMultiplier(hand);
        if (multiplier > 0.0F && multiplier != 1.0F) {
            result = Math.max(1.0F, result / multiplier);
        }
        if (result != original) {
            cir.setReturnValue(result);
        }
    }

    /** The mob's ATTACK_SPEED attribute value, or -1 when the attribute is not registered. */
    private static double fiercerbattles$attributeAttackSpeed(Mob mob) {
        try {
            return mob.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
        } catch (IllegalArgumentException notRegistered) {
            return -1.0D;
        }
    }

    /** 4.0 plus the weapon item's own ATTACK_SPEED modifiers, or -1 when unavailable. */
    private static double fiercerbattles$itemAttackSpeed(Object hand) {
        try {
            Object stack = hand.getClass().getMethod("itemStack").invoke(hand);
            if (!(stack instanceof ItemStack itemStack)) {
                return -1.0D;
            }
            double[] speed = {4.0D};
            itemStack.forEachModifier(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                    (attribute, modifier) -> {
                        if (attribute == net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED) {
                            speed[0] += modifier.amount();
                        }
                    });
            return Math.max(0.1D, speed[0]);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return -1.0D;
        }
    }

    /** First non-zero attack_speed_multiplier of the hand's weapon, or 0 when none is set. */
    private static float fiercerbattles$weaponAttackSpeedMultiplier(Object hand) {
        try {
            Object stack = hand.getClass().getMethod("itemStack").invoke(hand);
            if (!(stack instanceof ItemStack itemStack) || itemStack.isEmpty()) {
                return 0.0F;
            }
            String itemId = fiercerbattles$itemId(itemStack);
            if (itemId == null) {
                return 0.0F;
            }
            int attacks = fiercerbattles$attackCount(hand);
            for (int index = 0; index < attacks; index++) {
                FiercerBattlesCustomData.AttackData data = FiercerBattlesCustomData.get(itemId, index);
                if (data != null && data.attackSpeedMultiplier != 0) {
                    return data.attackSpeedMultiplier;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Better Mob Combat internals moved; mobs simply keep their normal pacing.
        }
        return 0.0F;
    }

    private static String fiercerbattles$itemId(ItemStack stack) {
        try {
            Class<?> componentClass = Class.forName("net.bettercombat.api.component.BetterCombatDataComponents");
            Object componentType = componentClass.getField("WEAPON_PRESET_ID").get(null);
            Object value = stack.getClass()
                    .getMethod("get", Class.forName("net.minecraft.core.component.DataComponentType"))
                    .invoke(stack, componentType);
            if (value instanceof ResourceLocation id) {
                return id.toString();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // No preset component; the registry id below is the fallback.
        }
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    private static int fiercerbattles$attackCount(Object hand) {
        try {
            Object attributes = hand.getClass().getMethod("attributes").invoke(hand);
            if (attributes == null) {
                return 1;
            }
            java.lang.reflect.Field attacksField = attributes.getClass().getDeclaredField("attacks");
            attacksField.setAccessible(true);
            Object[] attacks = (Object[]) attacksField.get(attributes);
            return attacks != null ? attacks.length : 1;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return 1;
        }
    }
}
