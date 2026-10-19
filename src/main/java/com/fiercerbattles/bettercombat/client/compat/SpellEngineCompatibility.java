package com.fiercerbattles.bettercombat.client.compat;

import com.fiercerbattles.bettercombat.Platform;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Method;

// Embedded in Fiercer Battles: Spell Engine is not on the compile classpath, so its
// SpellCasterEntity interface is resolved reflectively.
public class SpellEngineCompatibility {
    private static Boolean isLoaded = null;
    private static Class<?> casterClass;
    private static Method getCurrentSpell;

    public static boolean isCastingSpell(Player player) {
        if (isLoaded == null) {
            isLoaded = Platform.isModLoaded("spell_engine") && resolve();
        }
        if (isLoaded && casterClass.isInstance(player)) {
            try {
                return getCurrentSpell.invoke(player) != null;
            } catch (ReflectiveOperationException e) {
                isLoaded = false;
            }
        }
        return false;
    }

    private static boolean resolve() {
        try {
            casterClass = Class.forName("net.spell_engine.internals.casting.SpellCasterEntity");
            getCurrentSpell = casterClass.getMethod("getCurrentSpell");
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }
}
