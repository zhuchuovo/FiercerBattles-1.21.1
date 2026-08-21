package com.fiercerbattles;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;

public final class FiercerBattlesClient {
    public static final String KEY_CATEGORY = "key.categories.fiercerbattles";
    public static final KeyMapping COMBAT_SKILL_KEY = new KeyMapping(
            "key.fiercerbattles.combat_skill",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            KEY_CATEGORY
    );

    private FiercerBattlesClient() {
    }

    private static void tryStartCombatSkill(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        Object hand = invokeNoArg(minecraft, "getCurrentHand");
        Object attributes = FiercerBattlesCombatSkills.armClientSkill(hand);
        if (attributes == null) {
            return;
        }
        if (!invokeStartUpswing(minecraft, attributes)) {
            FiercerBattlesCombatSkills.clearArmedClientSkill();
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        try {
            var method = target.getClass().getDeclaredMethod(methodName);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static boolean invokeStartUpswing(Minecraft minecraft, Object attributes) {
        try {
            for (var method : minecraft.getClass().getDeclaredMethods()) {
                if (method.getName().equals("startUpswing") && method.getParameterCount() == 1) {
                    method.setAccessible(true);
                    method.invoke(minecraft, attributes);
                    return true;
                }
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return false;
    }
    @EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
            event.register(COMBAT_SKILL_KEY);
        }
    }

    @EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            while (COMBAT_SKILL_KEY.consumeClick()) {
                tryStartCombatSkill(Minecraft.getInstance());
            }
        }
    }
}
