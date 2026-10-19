package com.fiercerbattles.bettercombat.neoforge;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.mixin.player.PlayerEntityAccessor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingSwapItemsEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
public class NeoForgeEvents {
    @SubscribeEvent
    public static void register(final ServerAboutToStartEvent event) {
        BetterCombatMod.loadWeaponAttributes(event.getServer());
    }

    @SubscribeEvent
    public static void onHandSwap(LivingSwapItemsEvent.Hands event){
        if (event.getEntity() instanceof Player player) {
            var offHandStack = ((PlayerEntityAccessor)player).getInventory().offhand.get(0);
            event.setItemSwappedToOffHand(player.getMainHandItem());
            event.setItemSwappedToMainHand(offHandStack);
        }
    }
}
