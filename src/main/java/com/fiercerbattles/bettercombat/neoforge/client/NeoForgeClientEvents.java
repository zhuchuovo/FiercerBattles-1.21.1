package com.fiercerbattles.bettercombat.neoforge.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.client.WeaponAttributeTooltip;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public class NeoForgeClientEvents {
    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event){
        WeaponAttributeTooltip.modifyTooltip(event.getItemStack(), event.getToolTip());
    }
}
