package com.fiercerbattles.bettercombat.client.compat;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import com.fiercerbattles.bettercombat.Platform;
import com.fiercerbattles.bettercombat.client.BetterCombatClientMod;
import com.fiercerbattles.bettercombat.config.TriStateAuto;

public class FirstPersonAnimationCompatibility {
    private static boolean isCameraModPresent = false;

    static void setup() {
        var cameraMods = new String[] {
                "firstperson", "realcamera"
        };
        for (var mod : cameraMods) {
            if (Platform.isModLoaded(mod)) {
                isCameraModPresent = true;
                break;
            }
        }
    }

    public static FirstPersonMode firstPersonMode() {
        switch (BetterCombatClientMod.config.firstPersonAnimations) {
            case TriStateAuto.YES:
                return FirstPersonMode.THIRD_PERSON_MODEL;
            case TriStateAuto.NO:
                return FirstPersonMode.NONE;
            default:
                return isCameraModPresent ? FirstPersonMode.NONE : FirstPersonMode.THIRD_PERSON_MODEL;
        }
    }
}
