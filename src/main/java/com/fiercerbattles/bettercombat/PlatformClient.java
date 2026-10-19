package com.fiercerbattles.bettercombat;

import com.fiercerbattles.bettercombat.neoforge.PlatformClientImpl;
import net.minecraft.world.entity.player.Player;

public class PlatformClient {
    public static void onEmptyLeftClick(Player player) {
        PlatformClientImpl.onEmptyLeftClick(player);
    }
}
