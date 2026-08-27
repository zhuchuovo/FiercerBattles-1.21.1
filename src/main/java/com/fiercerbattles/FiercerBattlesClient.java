package com.fiercerbattles;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import org.slf4j.Logger;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Locale;

public final class FiercerBattlesClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long COOLDOWN_FLASH_DURATION_MILLIS = 450;
    private static final long COOLDOWN_FLASH_PEAK_MILLIS = 75;
    private static final int COOLDOWN_FLASH_MAX_ALPHA = 128;
    private static long cooldownFlashStartedAtMillis = Long.MIN_VALUE;
    public static final String KEY_CATEGORY = "key.categories.fiercerbattles";
    public static final KeyMapping COMBAT_SKILL_KEY = new KeyMapping(
            "key.fiercerbattles.combat_skill",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            KEY_CATEGORY
    );

    private FiercerBattlesClient() {
    }

    private static void tryStartCombatSkill(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        if (minecraft.getSingleplayerServer() != null) {
            FiercerBattlesCombatSkills.load(minecraft.getSingleplayerServer().getResourceManager());
        }
        Object currentHand = invokeNoArg(minecraft, "getCurrentHand");
        FiercerBattlesCustomData.AttackData candidate =
                FiercerBattlesCombatSkills.getCombatSkillDataForHand(currentHand, minecraft.player);
        if (candidate == null) {
            return;
        }
        long remainingCooldown = FiercerBattlesSkillEffects.getClientRemainingCooldownTicks(
                candidate,
                minecraft.player.tickCount
        );
        if (remainingCooldown > 0) {
            showCooldownFeedback(minecraft, remainingCooldown);
            return;
        }
        // A ready combat skill directly interrupts whatever attack animation
        // is still playing instead of waiting for it to finish.
        if (invokeNoArg(minecraft, "getCurrentAttackHand") != null) {
            FiercerBattlesRuntime.beginImmediateRecoveryCancellation();
            try {
                // Better Combat's cancelWeaponSwing normally fades the old
                // animation out. Stop the animation first with a zero-length
                // fade so the skill animation can take over immediately.
                invokeStopAttackAnimation(minecraft.player);
                if (!invokeCancelWeaponSwing(minecraft)) {
                    return;
                }
                FiercerBattlesRuntime.consumeRecoveryCancellation(minecraft.player.tickCount, candidate);
            } finally {
                FiercerBattlesRuntime.endImmediateRecoveryCancellation();
            }
        }
        FiercerBattlesCombatSkills.clearActiveSkillRange(minecraft.player);
        FiercerBattlesCombatSkills.clearClientSkill();
        // `getCurrentAttackHand()` is the public Better Combat API for the
        // *ongoing* swing and is null before a swing starts. The skill must
        // inspect Better Combat's private `getCurrentHand()` selector first.
        Object hand = currentHand != null ? currentHand : invokeNoArg(minecraft, "getCurrentHand");
        Object attributes = FiercerBattlesCombatSkills.armClientSkill(hand, minecraft.player);
        if (attributes == null) {
            return;
        }
        FiercerBattlesCustomData.AttackData skillData = FiercerBattlesCombatSkills.getArmedClientSkillCustomData();
        if (skillData != null
                && !FiercerBattlesSkillEffects.isClientReady(skillData, minecraft.player.tickCount)) {
            FiercerBattlesCombatSkills.clearArmedClientSkill();
            FiercerBattlesCombatSkills.clearActiveSkillRange(minecraft.player);
            return;
        }
        if (!invokeStartUpswing(minecraft, attributes)) {
            FiercerBattlesCombatSkills.clearClientSkill();
            FiercerBattlesCombatSkills.clearActiveSkillRange(minecraft.player);
            return;
        }
        Object ongoingHand = invokeNoArg(minecraft, "getCurrentAttackHand");
        if (!FiercerBattlesCombatSkills.isActiveClientSkillHand(ongoingHand)) {
            FiercerBattlesCombatSkills.clearClientSkill();
            FiercerBattlesCombatSkills.clearActiveSkillRange(minecraft.player);
            return;
        }
        FiercerBattlesSkillEffects.markClientCooldown(skillData, minecraft.player.tickCount);
    }

    private static void showCooldownFeedback(Minecraft minecraft, long remainingTicks) {
        String seconds = String.format(Locale.ROOT, "%.1f", remainingTicks / 20.0);
        minecraft.player.displayClientMessage(
                Component.translatable("message.fiercerbattles.combat_skill_cooldown", seconds),
                true
        );
        cooldownFlashStartedAtMillis = Util.getMillis();
    }

    private static void renderCooldownFlash(GuiGraphics graphics) {
        long elapsed = Util.getMillis() - cooldownFlashStartedAtMillis;
        if (elapsed < 0 || elapsed >= COOLDOWN_FLASH_DURATION_MILLIS) {
            return;
        }

        float intensity;
        if (elapsed < COOLDOWN_FLASH_PEAK_MILLIS) {
            intensity = elapsed / (float) COOLDOWN_FLASH_PEAK_MILLIS;
        } else {
            intensity = 1.0F - (elapsed - COOLDOWN_FLASH_PEAK_MILLIS)
                    / (float) (COOLDOWN_FLASH_DURATION_MILLIS - COOLDOWN_FLASH_PEAK_MILLIS);
        }
        intensity = smoothStep(intensity);
        double screenEffectScale = Minecraft.getInstance().options.screenEffectScale().get();
        int peakAlpha = Math.round((float) (COOLDOWN_FLASH_MAX_ALPHA * intensity * screenEffectScale));
        if (peakAlpha <= 0) {
            return;
        }

        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        int thickness = Math.max(6, Math.min(12, Math.min(width, height) / 40));
        for (int inset = 0; inset < thickness; inset++) {
            float falloff = 1.0F - inset / (float) thickness;
            int alpha = Math.round(peakAlpha * falloff * falloff);
            int color = alpha << 24 | 0xFFFFFF;
            graphics.fill(inset, inset, width - inset, inset + 1, color);
            graphics.fill(inset, height - inset - 1, width - inset, height - inset, color);
            graphics.fill(inset, inset + 1, inset + 1, height - inset - 1, color);
            graphics.fill(width - inset - 1, inset + 1, width - inset, height - inset - 1, color);
        }
    }

    private static float smoothStep(float value) {
        float clamped = Math.max(0.0F, Math.min(1.0F, value));
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    private static void resetCooldownFeedback() {
        cooldownFlashStartedAtMillis = Long.MIN_VALUE;
    }

    private static boolean invokeCancelWeaponSwing(Minecraft minecraft) {
        for (Class<?> type = minecraft.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod("cancelWeaponSwing");
                method.setAccessible(true);
                method.invoke(minecraft);
                return true;
            } catch (NoSuchMethodException ignored) {
                // Search the superclass chain.
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                LOGGER.debug("Unable to cancel Better Combat weapon swing", exception);
                return false;
            }
        }
        return false;
    }

    private static void invokeStopAttackAnimation(net.minecraft.client.player.LocalPlayer player) {
        for (Class<?> type = player.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("stopAttackAnimation") || method.getParameterCount() != 1
                        || method.getParameterTypes()[0] != float.class) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(player, 0.0F);
                    return;
                } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                    LOGGER.debug("Unable to stop Better Combat attack animation immediately", exception);
                    return;
                }
            }
        }
    }

    private static Object invokeNoArg(Object target, String methodName) {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod(methodName);
                method.setAccessible(true);
                return method.invoke(target);
            } catch (NoSuchMethodException ignored) {
                // Search the superclass chain; NeoForge can wrap the client.
            } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                LOGGER.debug("Unable to invoke Better Combat client method {}", methodName, exception);
                return null;
            }
        }
        return null;
    }

    private static boolean invokeStartUpswing(Minecraft minecraft, Object attributes) {
        for (Class<?> type = minecraft.getClass(); type != null; type = type.getSuperclass()) {
            for (Method method : type.getDeclaredMethods()) {
                if (!method.getName().equals("startUpswing") || method.getParameterCount() != 1) {
                    continue;
                }
                try {
                    method.setAccessible(true);
                    method.invoke(minecraft, attributes);
                    return true;
                } catch (IllegalAccessException | InvocationTargetException | RuntimeException exception) {
                    LOGGER.debug("Better Combat startUpswing invocation failed", exception);
                    return false;
                }
            }
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
            Minecraft minecraft = Minecraft.getInstance();
            while (COMBAT_SKILL_KEY.consumeClick()) {
                tryStartCombatSkill(minecraft);
            }
        }

        @SubscribeEvent
        public static void onRenderGui(RenderGuiEvent.Post event) {
            renderCooldownFlash(event.getGuiGraphics());
        }

        /**
         * The client player is recreated (tickCount reset to zero) on every
         * world rejoin. Client cooldown deadlines and skill selections are
         * absolute tick-based, so they must be dropped on logout or the next
         * session inherits unreachably large deadlines.
         */
        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            FiercerBattlesCombatSkills.clearClientState();
            FiercerBattlesSkillEffects.resetClientCooldowns();
            FiercerBattlesRuntime.resetClientState();
            resetCooldownFeedback();
        }

        @SubscribeEvent
        public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
            FiercerBattlesCombatSkills.clearClientState();
            FiercerBattlesSkillEffects.resetClientCooldowns();
            FiercerBattlesRuntime.resetClientState();
            resetCooldownFeedback();
        }
    }
}
