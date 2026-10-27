package com.fiercerbattles.boss;

import com.fiercerbattles.FiercerBattles;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;

/** Server-side game events of the boss package. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID)
public final class BossEvents {
    /** The Zombie Lord deals 300% extra damage to iron golems. */
    static final float IRON_GOLEM_MULTIPLIER = 4.0F;

    private BossEvents() {}

    /**
     * Everything the boss itself deals is multiplied against iron golems, and players watching the phase transition
     * (whose camera is on the boss, BossClient) take no damage until it ends.
     */
    @SubscribeEvent
    public static void incomingDamage(LivingIncomingDamageEvent event) {
        LivingEntity victim = event.getEntity();
        if (victim.level().isClientSide()) return;
        if (victim instanceof IronGolem && event.getSource().getEntity() instanceof ZombieLord) {
            event.setAmount(event.getAmount() * IRON_GOLEM_MULTIPLIER);
        }
        if (victim instanceof Player && !event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                && !victim.level().getEntitiesOfClass(ZombieLord.class,
                        victim.getBoundingBox().inflate(ZombieLord.TRANSITION_AUDIENCE_RANGE),
                        lord -> lord.getAction() == ZombieLordAction.TRANSITION).isEmpty()) {
            event.setCanceled(true);
        }
    }

    /**
     * A victim held by the Zombie Lord (or skewered on the Iron Sentinel's saw) cannot sneak free; the boss lets go
     * itself at the release frame.
     * Only the sneak dismount is blocked (the victim is alive and sneaking): death, teleports and disconnects still
     * free it. Server side only, so the client always follows the server's passenger list.
     */
    @SubscribeEvent
    public static void dismount(EntityMountEvent event) {
        if (!event.isDismounting() || event.getLevel().isClientSide()) return;
        Entity rider = event.getEntityMounting();
        boolean held = event.getEntityBeingMounted() instanceof ZombieLord lord && !lord.isReleasing()
                || event.getEntityBeingMounted() instanceof IronSentinel sentinel && !sentinel.isReleasing();
        if (held && rider.isAlive() && !rider.isRemoved() && rider.isShiftKeyDown()) {
            event.setCanceled(true);
        }
    }
}
