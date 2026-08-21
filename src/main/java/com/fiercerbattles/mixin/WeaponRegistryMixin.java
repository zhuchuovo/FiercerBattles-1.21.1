package com.fiercerbattles.mixin;

import com.fiercerbattles.FiercerBattlesCombatSkills;
import com.fiercerbattles.FiercerBattlesCustomData;
import com.fiercerbattles.config.FiercerBattlesConfig;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * String-targeted mixin into BetterCombat's WeaponRegistry.
 *
 * After BetterCombat resolves a WeaponAttributes tree, we walk its attacks and
 * apply per-animation overrides from config/fiercerbattles.json and from
 * per-item custom fields (indexed by item id + attack index) written into
 * weapon_attributes JSON files.
 *
 * This lets datapack/mod authors make a specific combo segment feel slower by
 * raising its upswing and/or reducing its movement speed multiplier.
 */
@Mixin(targets = "net.bettercombat.logic.WeaponRegistry")
public abstract class WeaponRegistryMixin {

    @Inject(method = "loadContainers", at = @At("HEAD"), remap = false)
    private static void fiercerbattles$loadCustomData(ResourceManager resourceManager, CallbackInfo ci) {
        FiercerBattlesCustomData.load(resourceManager);
        FiercerBattlesCombatSkills.load(resourceManager);
    }



    @Inject(method = "resolveAttributes", at = @At("RETURN"), remap = false)
    private static void fiercerbattles$applyOverrides(@Coerce Object itemId, @Coerce Object container, CallbackInfoReturnable<Object> cir) {
        Object attributes = cir.getReturnValue();
        if (attributes == null) {
            return;
        }
        applyToAttributes(itemId, container, attributes);
    }

    private static void applyToAttributes(Object itemId, Object container, Object attributes) {
        try {
            Class<?> attributesClass = attributes.getClass();
            Field attacksField = attributesClass.getDeclaredField("attacks");
            attacksField.setAccessible(true);
            Object[] attacks = (Object[]) attacksField.get(attributes);
            if (attacks == null) {
                return;
            }
            String id = itemId != null ? itemId.toString() : null;
            // Items without their own weapon_attributes file (for example daggers
            // assigned the bettercombat:dagger preset through the fallback
            // compatibility) get their attributes from another container.
            // Record that source id so per-item custom data can still be found.
            if (id != null) {
                String sourceId = sourceContainerId(container);
                if (sourceId != null) {
                    FiercerBattlesCombatSkills.registerAlias(id, sourceId);
                    if (!FiercerBattlesCustomData.contains(id)) {
                        FiercerBattlesCustomData.registerAlias(id, sourceId);
                        id = sourceId;
                    }
                }
            }
            for (int i = 0; i < attacks.length; i++) {
                FiercerBattlesCustomData.AttackData custom = id != null ? FiercerBattlesCustomData.get(id, i) : null;
                FiercerBattlesCustomData.applyToAttack(attacks[i], custom);
            }
        } catch (ReflectiveOperationException ignored) {
        }
    }

    /**
     * Finds the weapon_attributes container id that owns the given
     * AttributesContainer object, by identity-scanning BetterCombat's
     * containers map (populated before any resolveAttributes call).
     */
    private static String sourceContainerId(Object container) {
        if (container == null) {
            return null;
        }
        try {
            Class<?> registryClass = Class.forName("net.bettercombat.logic.WeaponRegistry");
            Field containersField = registryClass.getDeclaredField("containers");
            containersField.setAccessible(true);
            Object rawMap = containersField.get(null);
            if (!(rawMap instanceof Map<?, ?> containers)) {
                return null;
            }
            for (Map.Entry<?, ?> entry : containers.entrySet()) {
                if (entry.getValue() == container) {
                    return entry.getKey().toString();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }


}
