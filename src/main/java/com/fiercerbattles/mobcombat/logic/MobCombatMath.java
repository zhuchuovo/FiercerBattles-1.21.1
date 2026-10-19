package com.fiercerbattles.mobcombat.logic;

import com.fiercerbattles.mobcombat.config.BMCConfig;
import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.api.AttackHand;
import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import net.minecraft.core.Holder;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

public final class MobCombatMath {
    private MobCombatMath() {
    }

    public static float attackDurationTicks(Mob mob, AttackHand hand) {
        double attackSpeed;
        if (!hand.isOffHand() && mob.getAttribute(Attributes.ATTACK_SPEED) != null) {
            attackSpeed = mob.getAttributeValue(Attributes.ATTACK_SPEED);
        } else {
            attackSpeed = itemAttackSpeed(hand.itemStack(), EquipmentSlot.MAINHAND);
        }
        double exactDuration = 20.0D / Math.max(0.1D, attackSpeed);
        double minimum = BMCConfig.MINIMUM_ATTACK_INTERVAL.get();
        if (BetterCombatMod.getConfig() == null || !BetterCombatMod.getConfig().allow_fast_attacks) {
            minimum = Math.max(minimum, 10.0D);
        }

        return (float) Math.max(minimum, exactDuration);
    }

    public static int attackIntervalTicks(Mob mob, AttackHand hand) {
        return Math.max(1, Math.round(attackDurationTicks(mob, hand)));
    }

    public static int impactTick(Mob mob, AttackHand hand) {
        float duration = attackDurationTicks(mob, hand);
        int interval = attackIntervalTicks(mob, hand);
        int impact = Math.max(1, Mth.ceil(duration * adjustedUpswing(hand)));
        return Mth.clamp(impact, 1, interval);
    }

    public static float synchronizedDamageUpswing(Mob mob, AttackHand hand) {
        float duration = attackDurationTicks(mob, hand);
        return Mth.clamp(impactTick(mob, hand) / duration, 0.01F, 0.99F);
    }

    public static int windupTicks(Mob mob, AttackHand hand) {
        return impactTick(mob, hand);
    }

    public static float animationUpswing(AttackHand hand) {
        return (float) Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D);
    }

    public static float adjustedUpswing(AttackHand hand) {
        // AttackHand#upswingRate already applies Better Combat's global upswing multiplier.
        // Do not force the result back up to the raw weapon JSON value: doing so makes mobs hit
        // substantially later than players when an attack defines a large custom upswing (for
        // example 1.0 with Better Combat's 0.5 global multiplier). The BMC setting is an additive
        // bonus to that multiplier, matching the original config description.
        double rawUpswing = Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D);
        double additional = rawUpswing * BMCConfig.ADDITIONAL_UPSWING_MULTIPLIER.get();
        double configured = hand.upswingRate() + additional;
        return (float) Mth.clamp(configured, 0.01D, 1.0D);
    }

    public static float totalUpswingMultiplier(AttackHand hand) {
        double rawUpswing = Math.max(0.0001D, Mth.clamp(hand.attack().upswing(), 0.0D, 1.0D));
        return (float) Mth.clamp(adjustedUpswing(hand) / rawUpswing, 0.2D, 1.0D);
    }

    public static double attackRange(Mob mob, AttackHand hand) {
        WeaponAttributes attributes = hand.attributes();
        double rangeMultiplier = Math.max(0.05F, hand.attack().rangeMultiplier());

        double legacyAbsoluteRange = attributes.attackRange();
        double range;
        if (legacyAbsoluteRange > 0.0D) {
            range = legacyAbsoluteRange * rangeMultiplier;
        } else {
            ItemStack stack = hand.itemStack();
            range = itemHasModifier(stack, Attributes.ENTITY_INTERACTION_RANGE)
                    ? applyItemModifiers(2.5D, stack, EquipmentSlot.MAINHAND, Attributes.ENTITY_INTERACTION_RANGE)
                    : 2.5D + attributes.rangeBonus();
            range *= rangeMultiplier;
        }

        return Math.max(0.5D, range + BMCConfig.ADDITIONAL_ATTACK_RANGE.get());
    }

    private static boolean itemHasModifier(ItemStack stack, Holder<Attribute> searchedAttribute) {
        final boolean[] found = {false};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(searchedAttribute)) {
                found[0] = true;
            }
        });
        return found[0];
    }

    private static double applyItemModifiers(
            double base,
            ItemStack stack,
            EquipmentSlot slot,
            Holder<Attribute> searchedAttribute
    ) {
        List<AttributeModifier> modifiers = new ArrayList<>();
        stack.forEachModifier(slot, (attribute, modifier) -> {
            if (attribute.equals(searchedAttribute)) {
                modifiers.add(modifier);
            }
        });
        return applyModifiers(base, modifiers);
    }

    private static double itemAttackSpeed(ItemStack stack, EquipmentSlot slot) {
        double base = 4.0D;
        List<AttributeModifier> modifiers = new ArrayList<>();
        stack.forEachModifier(slot, (Holder<Attribute> attribute, AttributeModifier modifier) -> {
            if (attribute.is(Attributes.ATTACK_SPEED)) {
                modifiers.add(modifier);
            }
        });
        return Math.max(0.1D, applyModifiers(base, modifiers));
    }

    private static double applyModifiers(double base, List<AttributeModifier> modifiers) {
        double add = 0.0D;
        double addBase = 0.0D;
        double multiplyTotal = 1.0D;
        for (AttributeModifier modifier : modifiers) {
            switch (modifier.operation()) {
                case ADD_VALUE -> add += modifier.amount();
                case ADD_MULTIPLIED_BASE -> addBase += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> multiplyTotal *= 1.0D + modifier.amount();
            }
        }
        return (base + add + base * addBase) * multiplyTotal;
    }
}
