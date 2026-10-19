package com.fiercerbattles.bettercombat.client;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.List;
import net.minecraft.client.KeyMapping;

public class Keybindings {
    public static KeyMapping feintKeyBinding;
    public static KeyMapping toggleMineKeyBinding;
    public static List<KeyMapping> all;

    static {
        feintKeyBinding = new KeyMapping(
                "keybinds.bettercombat.feint",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "Better Combat");

        toggleMineKeyBinding = new KeyMapping(
                "keybinds.bettercombat.toggle_mine_with_weapons",
                InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(),
                "Better Combat");

        all = List.of(feintKeyBinding, toggleMineKeyBinding);
    }
}
