package com.fiercerbattles.mobcombat.event;

import com.fiercerbattles.mobcombat.api.RangedWeaponKind;
import com.fiercerbattles.mobcombat.config.BMCConfig;
import com.fiercerbattles.mobcombat.logic.ExternalAttackCompat;
import com.fiercerbattles.mobcombat.network.BMCNetwork;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ProjectileWeaponItem;
import net.minecraft.world.item.TridentItem;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

public final class CommonEvents {
    private CommonEvents() {
    }

    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide || !BMCConfig.ENABLED.get()) {
            return;
        }
        if (!(event.getEntity() instanceof Projectile projectile)) {
            return;
        }

        Entity owner = projectile.getOwner();
        if (!(owner instanceof Mob mob) || !mob.isAlive()) {
            return;
        }

        if (BMCConfig.isBlacklisted(mob.getType())) {
            return;
        }

        WeaponContext weapon = classify(mob.getMainHandItem(), InteractionHand.MAIN_HAND);
        if (weapon == null) {
            weapon = classify(mob.getOffhandItem(), InteractionHand.OFF_HAND);
        }
        if (weapon == null) {
            return;
        }

        BMCNetwork.sendRangedRelease(
                mob,
                weapon.kind(),
                weapon.hand() == InteractionHand.OFF_HAND
        );
    }


    public static void onLivingDamagePre(LivingDamageEvent.Pre event) {
        if (event.getEntity().level().isClientSide || !BMCConfig.ENABLED.get()) {
            return;
        }

        Entity attacker = event.getSource().getEntity();
        Entity direct = event.getSource().getDirectEntity();
        if (!(attacker instanceof Mob mob) || direct != attacker || !mob.isAlive()) {
            return;
        }
        if (BMCConfig.isBlacklisted(mob.getType())) {
            return;
        }

        ExternalAttackCompat.handleMeleeDamageAttempt(mob);
    }

    private static WeaponContext classify(ItemStack stack, InteractionHand hand) {
        Item item = stack.getItem();
        if (item instanceof CrossbowItem) {
            return new WeaponContext(RangedWeaponKind.CROSSBOW, hand);
        }
        if (item instanceof BowItem) {
            return new WeaponContext(RangedWeaponKind.BOW, hand);
        }
        if (item instanceof TridentItem) {
            return new WeaponContext(RangedWeaponKind.SPEAR, hand);
        }
        if (item instanceof ProjectileWeaponItem) {
            return new WeaponContext(RangedWeaponKind.GENERIC, hand);
        }
        return null;
    }

    private record WeaponContext(RangedWeaponKind kind, InteractionHand hand) {
    }
}