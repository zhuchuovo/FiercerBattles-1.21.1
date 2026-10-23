package com.fiercerbattles.boss;

import com.fiercerbattles.FiercerBattles;
import net.minecraft.world.entity.Entity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityMountEvent;

/** Server-side game events of the boss package. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID)
public final class BossEvents {
    private BossEvents() {}

    /**
     * A victim held by the Zombie Lord cannot sneak out of its fist; the boss lets go itself at the release frame.
     * Only the sneak dismount is blocked (the victim is alive and sneaking): death, teleports and disconnects still
     * free it. Server side only, so the client always follows the server's passenger list.
     */
    @SubscribeEvent
    public static void dismount(EntityMountEvent event) {
        if (!event.isDismounting() || event.getLevel().isClientSide()) return;
        Entity rider = event.getEntityMounting();
        if (event.getEntityBeingMounted() instanceof ZombieLord lord && !lord.isReleasing()
                && rider.isAlive() && !rider.isRemoved() && rider.isShiftKeyDown()) {
            event.setCanceled(true);
        }
    }
}
