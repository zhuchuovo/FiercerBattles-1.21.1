package com.fiercerbattles.mobcombat.logic;

import com.fiercerbattles.bettercombat.api.AttackHand;
import com.fiercerbattles.bettercombat.api.ComboState;
import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import com.fiercerbattles.bettercombat.logic.WeaponRegistry;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.ShieldItem;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

public final class MobAttackSelector {
    private MobAttackSelector() {
    }

    public static boolean hasCombatWeapon(LivingEntity entity) {
        ItemStack stack = entity.getMainHandItem();
        if (stack.getItem() instanceof ProjectileWeaponItem) {
            return false;
        }
        WeaponAttributes attributes = WeaponRegistry.getAttributes(stack);
        return hasAttacks(attributes);
    }

    public static boolean isDualWielding(LivingEntity entity) {
        if (entity.getMainHandItem().getItem() instanceof ProjectileWeaponItem
                || entity.getOffhandItem().getItem() instanceof ProjectileWeaponItem) {
            return false;
        }
        WeaponAttributes main = WeaponRegistry.getAttributes(entity.getMainHandItem());
        WeaponAttributes off = WeaponRegistry.getAttributes(entity.getOffhandItem());
        return hasAttacks(main) && !main.isTwoHanded() && hasAttacks(off) && !off.isTwoHanded();
    }

    public static boolean isTwoHandedWielding(LivingEntity entity) {
        if (entity.getMainHandItem().getItem() instanceof ProjectileWeaponItem) {
            return false;
        }
        WeaponAttributes main = WeaponRegistry.getAttributes(entity.getMainHandItem());
        return hasAttacks(main) && main.isTwoHanded();
    }

    public static boolean shouldUseOffHand(LivingEntity entity, int comboCount) {
        return isDualWielding(entity) && Math.floorMod(comboCount, 2) == 1;
    }

    @Nullable
    public static AttackHand select(LivingEntity entity, int comboCount) {
        boolean dualWielding = isDualWielding(entity);
        boolean offHand = dualWielding && shouldUseOffHand(entity, comboCount);
        ItemStack stack = offHand ? entity.getOffhandItem() : entity.getMainHandItem();
        if (stack.getItem() instanceof ProjectileWeaponItem) {
            return null;
        }
        WeaponAttributes attributes = WeaponRegistry.getAttributes(stack);
        if (!hasAttacks(attributes)) {
            return null;
        }

        int handCombo = dualWielding ? Math.max(0, comboCount - (offHand ? 1 : 0)) / 2 : Math.max(0, comboCount);
        WeaponAttributes.Attack[] validAttacks = Arrays.stream(attributes.attacks())
                .filter(attack -> attack != null && conditionsPass(attack.conditions(), entity, offHand))
                .toArray(WeaponAttributes.Attack[]::new);
        if (validAttacks.length == 0) {
            return null;
        }

        int index = Math.floorMod(handCombo, validAttacks.length);
        return new AttackHand(validAttacks[index], new ComboState(index + 1, validAttacks.length), offHand, attributes, stack);
    }

    private static boolean hasAttacks(@Nullable WeaponAttributes attributes) {
        return attributes != null && attributes.attacks() != null && attributes.attacks().length > 0;
    }

    private static boolean conditionsPass(@Nullable WeaponAttributes.Condition[] conditions, LivingEntity entity, boolean offHandAttack) {
        return conditions == null || conditions.length == 0 || Arrays.stream(conditions)
                .allMatch(condition -> conditionPasses(condition, entity, offHandAttack));
    }

    private static boolean conditionPasses(@Nullable WeaponAttributes.Condition condition, LivingEntity entity, boolean offHandAttack) {
        if (condition == null) {
            return true;
        }

        return switch (condition) {
            case NOT_DUAL_WIELDING -> !isDualWielding(entity);
            case DUAL_WIELDING_ANY -> isDualWielding(entity);
            case DUAL_WIELDING_SAME -> isDualWielding(entity)
                    && entity.getMainHandItem().is(entity.getOffhandItem().getItem());
            case DUAL_WIELDING_SAME_CATEGORY -> sameWeaponCategory(entity);
            // Better Combat treats the offhand as unavailable while a two-handed main weapon is
            // equipped. Mirror that rule for mobs even if another mod spawned an item in the slot.
            case NO_OFFHAND_ITEM -> isTwoHandedWielding(entity) || entity.getOffhandItem().isEmpty();
            case OFF_HAND_SHIELD -> !isTwoHandedWielding(entity)
                    && !isDualWielding(entity)
                    && entity.getOffhandItem().getItem() instanceof ShieldItem;
            case MAIN_HAND_ONLY -> !offHandAttack;
            case OFF_HAND_ONLY -> offHandAttack;
            case MOUNTED -> entity.isPassenger();
            case NOT_MOUNTED -> !entity.isPassenger();
        };
    }

    private static boolean sameWeaponCategory(LivingEntity entity) {
        if (!isDualWielding(entity)) {
            return false;
        }
        WeaponAttributes main = WeaponRegistry.getAttributes(entity.getMainHandItem());
        WeaponAttributes off = WeaponRegistry.getAttributes(entity.getOffhandItem());
        return main != null && off != null && main.category() != null && !main.category().isBlank()
                && main.category().equals(off.category());
    }
}
