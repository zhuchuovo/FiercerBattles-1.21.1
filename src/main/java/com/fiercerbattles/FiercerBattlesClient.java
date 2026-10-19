package com.fiercerbattles;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import com.fiercerbattles.locomotion.client.LocomotionClient;
import com.fiercerbattles.parry.client.ParryClient;
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
    public static final KeyMapping EXECUTION_KEY = new KeyMapping(
            "key.fiercerbattles.execution",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            KEY_CATEGORY
    );
    public static final KeyMapping PARRY_KEY = new KeyMapping(
            "key.fiercerbattles.parry",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            KEY_CATEGORY
    );
    public static final KeyMapping ROLL_KEY = new KeyMapping(
            "key.fiercerbattles.roll",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            KEY_CATEGORY
    );
    public static final KeyMapping PRONE_KEY = new KeyMapping(
            "key.fiercerbattles.prone",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            KEY_CATEGORY
    );

    private FiercerBattlesClient() {
    }

    private static void tryStartCombatSkill(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.screen != null) {
            return;
        }
        resumeCombatSkill(minecraft);
    }

    public static void cancelAttackForExecution(Minecraft minecraft) {
        if (minecraft.player == null) return;
        FiercerBattlesRuntime.beginImmediateRecoveryCancellation();
        try {
            invokeStopAttackAnimation(minecraft.player);
            invokeCancelWeaponSwing(minecraft);
        } finally {
            FiercerBattlesRuntime.endImmediateRecoveryCancellation();
        }
        FiercerBattlesCombatSkills.clearClientState();
        FiercerBattlesRuntime.resetClientState();
    }

    public static void resumeCombatSkill(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.screen != null || FiercerBattlesExecutionClient.blocksInput()
                || ParryClient.blocksActions() || LocomotionClient.blocksActions()) {
            return;
        }
        if (minecraft.getSingleplayerServer() != null) {
            FiercerBattlesCombatSkills.load(minecraft.getSingleplayerServer().getResourceManager());
        } else if (minecraft.getResourceManager() != null) {
            // Multiplayer client: Better Combat loads the weapon registry on
            // the server only, so the client parses its own copy of the
            // weapon_attributes / combat skill files. Without this the skill
            // key (and the off-hand skill option) could never resolve a skill.
            FiercerBattlesCombatSkills.load(minecraft.getResourceManager());
        }
        Object currentHand = invokeNoArg(minecraft, "getCurrentHand");
        // An off-hand weapon that opted in with "offhand_skill": true takes
        // priority here: the skill key releases its skill instead of the
        // main-hand weapon's skill.
        FiercerBattlesCombatSkills.Activation activation =
                FiercerBattlesCombatSkills.resolveActivation(currentHand, minecraft.player);
        if (activation == null) {
            // No skill resolved: the weapon has no "Combatskill" marker, its
            // skill file is missing, or the current combo step has no skill
            // segment. Report it once per item instead of pressing into the void.
            reportMissingCombatSkill(currentHand);
            return;
        }
        FiercerBattlesCustomData.AttackData candidate = activation.customData();
        long remainingCooldown = FiercerBattlesSkillEffects.getClientRemainingCooldownTicks(
                candidate,
                minecraft.player.tickCount
        );
        if (remainingCooldown > 0) {
            showCooldownFeedback(minecraft, remainingCooldown);
            return;
        }
        // A ready combat skill directly interrupts whatever attack animation
        // is still playing instead of waiting for it to finish — unless the
        // skill opts out via "interrupt_attack": false (wait for the attack
        // to finish) or a frame number N (only interrupt once the attack
        // animation has played N frames).
        if (invokeNoArg(minecraft, "getCurrentAttackHand") != null) {
            if (candidate.skillEffect != null && !candidate.skillEffect.interruptAttack) {
                return;
            }
            if (candidate.skillEffect != null && candidate.skillEffect.interruptAfterFrame > 0
                    && !FiercerBattlesRuntime.hasAttackPlayedAtLeast(
                            minecraft.player.tickCount,
                            candidate.skillEffect.interruptAfterFrame
                    )) {
                return;
            }
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
        // *ongoing* swing and is null before a swing starts; the activation
        // resolved above already went through Better Combat's private
        // `getCurrentHand()` selector.
        Object attributes = FiercerBattlesCombatSkills.armClientSkill(activation, minecraft.player);
        if (attributes == null) {
            return;
        }
        // A skill whose animation resource is missing (not installed, wrong-case
        // file name, or a resource pack that is not enabled) would blow up inside
        // Better Combat's startUpswing with a null KeyframeAnimation; the swing
        // would start and never resolve. Refuse to start it instead, so the
        // broken reference is reported once and the normal attack stays intact.
        if (!hasLoadableAttackAnimation(minecraft, attributes)) {
            FiercerBattlesCombatSkills.clearArmedClientSkill();
            FiercerBattlesCombatSkills.clearClientSkill();
            FiercerBattlesCombatSkills.clearActiveSkillRange(minecraft.player);
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

    private static final java.util.Set<String> REPORTED_MISSING_ANIMATIONS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * True when the first attack of the given Better Combat attributes either
     * declares no animation or references one that exists as
     * {@code assets/<namespace>/player_animations/<name>.json} in the client's
     * resource manager (mod jars count). Better Combat loads player animations
     * from exactly that path, so a missing file means a null animation later.
     */
    private static boolean hasLoadableAttackAnimation(Minecraft minecraft, Object attributes) {
        try {
            java.lang.reflect.Field attacksField = attributes.getClass().getDeclaredField("attacks");
            attacksField.setAccessible(true);
            Object[] attacks = (Object[]) attacksField.get(attributes);
            Object attack = attacks != null && attacks.length > 0 ? attacks[0] : null;
            if (attack == null) {
                return true;
            }
            Object animation = attack.getClass().getMethod("animation").invoke(attack);
            if (!(animation instanceof String name) || name.isBlank()) {
                return true;
            }
            net.minecraft.resources.ResourceLocation id =
                    net.minecraft.resources.ResourceLocation.tryParse(name);
            if (id == null) {
                reportMissingAnimation(name + "' is not a valid resource location (lowercase only)");
                return false;
            }
            net.minecraft.resources.ResourceLocation file = net.minecraft.resources.ResourceLocation
                    .fromNamespaceAndPath(id.getNamespace(), "player_animations/" + id.getPath() + ".json");
            if (minecraft.getResourceManager().getResource(file).isEmpty()) {
                reportMissingAnimation(name);
                return false;
            }
            return true;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            LOGGER.debug("Unable to validate Better Combat attack animation", exception);
            return true;
        }
    }

    private static void reportMissingAnimation(String animation) {
        if (REPORTED_MISSING_ANIMATIONS.add(animation)) {
            LOGGER.error(
                    "Fiercer Battles: combat-skill animation '{}' was not found in any resource pack "
                            + "(check the file name is all lowercase and its pack is enabled); the skill was not started",
                    animation
            );
        }
    }

    private static final java.util.Set<String> REPORTED_MISSING_SKILLS =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * A weapon without a resolved skill makes the skill key look dead, because
     * the key press is intentionally silent (see the README's "no reaction"
     * entry). Report the reason once per item: the weapon_attributes file that
     * actually wins the pack order needs {@code "Combatskill": true} plus a
     * valid {@code json} reference, and the current combo step must map to a
     * skill segment that is not {@code null}.
     */
    private static void reportMissingCombatSkill(Object currentHand) {
        String itemId = FiercerBattlesCombatSkills.itemIdForHand(currentHand);
        if (itemId == null) {
            itemId = "empty hand";
        }
        if (REPORTED_MISSING_SKILLS.add(itemId)) {
            LOGGER.info(
                    "Fiercer Battles: no combat skill resolved for '{}' - the skill key was ignored. Check that its "
                            + "weapon_attributes json sets \"Combatskill\": true with a valid \"json\" skill file, and that "
                            + "the current combo step maps to a non-null skill attack",
                    itemId
            );
        }
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
            FiercerBattlesExecutionClient.initialize();
            ParryClient.initialize();
            LocomotionClient.initialize();
            event.register(COMBAT_SKILL_KEY);
            event.register(EXECUTION_KEY);
            event.register(PARRY_KEY);
            event.register(ROLL_KEY);
            event.register(PRONE_KEY);
        }
    }

    @EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
    public static final class ClientEvents {
        private ClientEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            FiercerBattlesExecutionClient.tick();
            ParryClient.tick();
            LocomotionClient.tick();
            while (EXECUTION_KEY.consumeClick()) {
                FiercerBattlesExecutionClient.tryRequest(minecraft);
            }
            while (PARRY_KEY.consumeClick()) {
                ParryClient.tryRequest(minecraft);
            }
            while (COMBAT_SKILL_KEY.consumeClick()) {
                tryStartCombatSkill(minecraft);
            }
            while (ROLL_KEY.consumeClick()) {
                LocomotionClient.tryRoll(minecraft);
            }
            while (PRONE_KEY.consumeClick()) {
                LocomotionClient.toggleProne(minecraft);
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
            FiercerBattlesExecutionClient.reset();
            FiercerBattlesExecutionClient.clearActionCache();
            ParryClient.reset();
            ParryClient.clearAnimations();
            LocomotionClient.reset();
            LocomotionClient.clearAnimations();
            FiercerBattlesCombatSkills.clearClientState();
            FiercerBattlesSkillEffects.resetClientCooldowns();
            FiercerBattlesRuntime.resetClientState();
            resetCooldownFeedback();
            REPORTED_MISSING_SKILLS.clear();
        }

        @SubscribeEvent
        public static void onPlayerClone(ClientPlayerNetworkEvent.Clone event) {
            FiercerBattlesExecutionClient.reset();
            ParryClient.reset();
            LocomotionClient.reset();
            FiercerBattlesCombatSkills.clearClientState();
            FiercerBattlesSkillEffects.resetClientCooldowns();
            FiercerBattlesRuntime.resetClientState();
            resetCooldownFeedback();
            REPORTED_MISSING_SKILLS.clear();
        }
    }
}
