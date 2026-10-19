package com.fiercerbattles.bettercombat.mixin.client;

import me.shedaniel.autoconfig.AutoConfig;
import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.Platform;
import com.fiercerbattles.bettercombat.PlatformClient;
import com.fiercerbattles.bettercombat.api.AttackHand;
import com.fiercerbattles.bettercombat.api.CombatFlags;
import com.fiercerbattles.bettercombat.api.MinecraftClient_BetterCombat;
import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import com.fiercerbattles.bettercombat.api.WeaponAttributes.Attack;
import com.fiercerbattles.bettercombat.api.client.BetterCombatClientEvents;
import com.fiercerbattles.bettercombat.api.fx.TrailAppearance;
import com.fiercerbattles.bettercombat.client.BetterCombatClientMod;
import com.fiercerbattles.bettercombat.client.Keybindings;
import com.fiercerbattles.bettercombat.client.animation.PlayerAttackAnimatable;
import com.fiercerbattles.bettercombat.client.collision.TargetFinder;
import com.fiercerbattles.bettercombat.client.particle.SlashParticleUtil;
import com.fiercerbattles.bettercombat.config.ClientConfigWrapper;
import com.fiercerbattles.bettercombat.logic.*;
import com.fiercerbattles.bettercombat.network.Packets;
import com.fiercerbattles.bettercombat.network.Packets.AttackAnimation;
import com.fiercerbattles.bettercombat.network.Packets.C2S_AttackRequest;
import com.fiercerbattles.bettercombat.network.Packets.C2S_BlockHit;
import com.fiercerbattles.bettercombat.utils.PatternMatching;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

import static net.minecraft.world.phys.HitResult.Type.BLOCK;


@Mixin(Minecraft.class)
public abstract class MinecraftClientInject implements MinecraftClient_BetterCombat {
    @Shadow public ClientLevel level;
    @Shadow @Nullable public LocalPlayer player;

    @Shadow private int rightClickDelay;

    @Shadow @Final public Font font;

    @Shadow public int missTime;

    @Shadow @Final public Gui gui;

    @Shadow @Nullable public HitResult hitResult;

    private Minecraft thisClient() {
        return (Minecraft)((Object)this);
    }
    private boolean isHoldingAttackInput = false;
    private boolean isHarvesting = false;

    // Targeting the method where all the disconnection related logic is.
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V",at = @At("TAIL"))
    private void disconnect_TAIL(Screen screen, CallbackInfo ci) {
        BetterCombatClientMod.ENABLED = false;
    }

    // Press to attack
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void pre_doAttack(CallbackInfoReturnable<Boolean> info) {
        if (!BetterCombatClientMod.ENABLED) { return; }
        if (CombatFlags.isAttackDisabled(player)) { return; }

        Minecraft client = thisClient();
        WeaponAttributes attributes = WeaponRegistry.getAttributes(client.player.getMainHandItem());
        if (attributes != null && attributes.attacks() != null) {
            if (isTargetingMineableBlock() || isHarvesting) {
                isHarvesting = true;
                return;
            }
            startUpswing(attributes);
            info.setReturnValue(false);
            info.cancel();
        }
    }

    // Hold to attack
    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void pre_handleBlockBreaking(boolean bl, CallbackInfo ci) {
        if (!BetterCombatClientMod.ENABLED) { return; }
        if (CombatFlags.isAttackDisabled(player)) { return; }

        Minecraft client = thisClient();
        WeaponAttributes attributes = WeaponRegistry.getAttributes(client.player.getMainHandItem());
        if (attributes != null && attributes.attacks() != null) {
            boolean isPressed = client.options.keyAttack.isDown();
            if(isPressed && !isHoldingAttackInput) {
                if (isTargetingMineableBlock() || isHarvesting) {
                    isHarvesting = true;
                    return;
                } else {
                    ci.cancel();
                }
            }

            if (BetterCombatClientMod.config.isHoldToAttackEnabled && isPressed) {
                isHoldingAttackInput = true;
                startUpswing(attributes);
                ci.cancel();
            } else {
                isHarvesting = false;
                isHoldingAttackInput = false;
            }
        }
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void pre_doItemUse(CallbackInfo ci) {
        if (!BetterCombatClientMod.ENABLED) { return; }
        if (CombatFlags.isAttackDisabled(player)) { return; }

        var hand = getCurrentHand();
        if (hand == null) { return; }
        double upswingRate = hand.upswingRate();
        if (currentUpswingTicks() > 0 || player.getAttackStrengthScale(0) < (1.0 - upswingRate)) {
            ci.cancel();
        }
    }

    private boolean isTargetingMineableBlock() {
        if (!BetterCombatClientMod.config.isMiningWithWeaponsEnabled) {
            var whitelist = BetterCombatClientMod.config.mineWithWeaponWhitelist;
            if (whitelist == null || whitelist.isEmpty()) {
                return false;
            }
            var itemStack = player.getMainHandItem();
            var id = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
            if (!PatternMatching.matches(id, whitelist)) {
                return false;
            }
            // Weapon is whitelisted — fall through to continue checks
        }
        var regex = BetterCombatClientMod.config.mineWithWeaponBlacklist;
        if (regex != null && !regex.isEmpty()) {
            var itemStack = player.getMainHandItem();
            var id = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
            if (PatternMatching.matches(id, regex)) {
                return false;
            }
        }
        if (BetterCombatClientMod.config.isAttackInsteadOfMineWhenEnemiesCloseEnabled
                && this.hasTargetsInReach()) {
            return false;
        }
        Minecraft client = thisClient();
        HitResult crosshairTarget = client.hitResult;
        if (crosshairTarget != null && crosshairTarget.getType() == BLOCK) {
            BlockHitResult blockHitResult = (BlockHitResult) crosshairTarget;
            BlockPos pos = blockHitResult.getBlockPos();
            BlockState clicked = level.getBlockState(pos);
            if (shouldSwingThruGrass()) {
                if (!clicked.getCollisionShape(level, pos).isEmpty() || clicked.getDestroySpeed(level, pos) != 0.0F) {
                    return true;
                }
            } else {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSwingThruGrass() {
        if(!BetterCombatClientMod.config.isSwingThruGrassEnabled) {
            return false;
        }
        if (BetterCombatClientMod.config.isSwingThruGrassSmart
                && !this.hasTargetsInReach()) {
            return false;
        }
        var regex = BetterCombatClientMod.config.swingThruGrassBlacklist;
        if (regex == null || regex.isEmpty()) {
            return true;
        }
        var itemStack = player.getMainHandItem();
        var id = BuiltInRegistries.ITEM.getKey(itemStack.getItem()).toString();
        return !PatternMatching.matches(id, regex);
    }

    private ItemStack upswingStack;
    private ItemStack lastAttacedWithItemStack;
    private WeaponSwing ongoingSwing;
//    private int upswingTicks = 0;
    private int lastAttacked = 1000;
    private float lastSwingDuration = 0;
    private int comboReset = 0;

    @Unique private int currentTime() {
        if (player == null) {
            return 0;
        }
        return player.tickCount;
    }
    @Unique private int currentUpswingTicks() {
        if (ongoingSwing == null) {
            return 0;
        }
        return ongoingSwing.upswingTicksLeft(currentTime());
    }

    private void startUpswing(WeaponAttributes attributes) {
        // Guard conditions

        if (player.isHandsBusy()) {
            // isRiding is `isHandsBusy()` according to official mappings
            // Support for revival mod
            return;
        }

        var attackHand = getCurrentHand();
        if (attackHand == null) { return; }
        float upswingRate = (float) attackHand.upswingRate();
        if (currentUpswingTicks() > 0
                || missTime > 0
                || player.isUsingItem()
                || player.getAttackStrengthScale(0) < (1.0 - upswingRate)) {
//            double attackCooldownTicks = PlayerAttackHelper.getAttackCooldownTicksCapped(player) / PlayerAttackHelper.getDualWieldingAttackSpeedMultiplier(player);
//            var currentCD = Math.round(attackCooldownTicks * player.getAttackCooldownProgress(0));
//            System.out.println("Waiting for cooldown: " + currentCD + "/" + attackCooldownTicks);
            return;
        }

        // Starting upswing
        player.releaseUsingItem();

        lastAttacked = 0;
        upswingStack = player.getMainHandItem();
        float attackCooldownTicksFloat = PlayerAttackHelper.getAttackCooldownTicksCapped(player); // `getAttackCooldownProgressPerTick` should be called `getAttackCooldownLengthTicks`
        int attackCooldownTicks = Math.round(attackCooldownTicksFloat);
        this.comboReset = Math.round(attackCooldownTicksFloat * BetterCombatMod.config.combo_reset_rate);
        var upswingTicks = Math.max(Math.round(attackCooldownTicksFloat * upswingRate), 1); // At least 1 upswing ticks
        this.ongoingSwing = new WeaponSwing(attackHand, currentTime(), upswingTicks, attackCooldownTicksFloat);
        this.lastSwingDuration = attackCooldownTicksFloat;
        this.rightClickDelay = attackCooldownTicks; // Vanilla MinecraftClient property for compatibility
        setMiningCooldown(attackCooldownTicks);
//        System.out.println("Starting upswingTicks: " + upswingTicks);
        String animationName = attackHand.attack().animation();
        boolean isOffHand = attackHand.isOffHand();
        var animatedHand = AnimatedHand.from(isOffHand, attributes.isTwoHanded());
        ((PlayerAttackAnimatable) player).playAttackAnimation(animationName, animatedHand, attackCooldownTicksFloat, upswingRate);

        var particles = SlashParticleUtil.trailParticlesFromAttack(attackHand);
        var appearance = SlashParticleUtil.appearanceFor(player, attackHand.itemStack());
        var packet = new Packets.AttackAnimation(
                player.getId(), animatedHand, animationName, attackCooldownTicksFloat, upswingRate,
                (float)PlayerAttackHelper.getStaticRange(player, attackHand.itemStack()),
                upswingTicks,
                new Packets.SwingParticles(particles, appearance)
        );
        Platform.networkC2S_Send(packet);
        BetterCombatClientEvents.ATTACK_START.invoke(handler -> {
            handler.onPlayerAttackStart(player, attackHand);
        });
    }

    private void cancelSwingIfNeeded() {
        if (upswingStack != null && !areItemStackEqual(player.getMainHandItem(), upswingStack)) {
            cancelWeaponSwing();
            return;
        }
    }

    private void attackFromUpswingIfNeeded() {
        if (ongoingSwing != null && currentUpswingTicks() == 0) {
            performAttack();
            upswingStack = null;
        }
    }

    private void resetComboIfNeeded() {
        // Combo timeout
        if(lastAttacked > comboReset && getComboCount() > 0) {
            setComboCount(0);
        }
        // Switching main-hand weapon
        if (!PlayerAttackHelper.shouldAttackWithOffHand(player, getComboCount())) {
            if(player.getMainHandItem() == null
                    || (lastAttacedWithItemStack != null && !lastAttacedWithItemStack.getItem().equals(player.getMainHandItem().getItem()) ) ) {
                setComboCount(0);
            }
        }
    }

    private List<Entity> targetsInReach = null;

    private boolean shouldUpdateTargetsInReach() {
        if(BetterCombatClientMod.config.isHighlightCrosshairEnabled
                || BetterCombatClientMod.config.isAttackInsteadOfMineWhenEnemiesCloseEnabled) {
            return targetsInReach == null;
        }
        return false;
    }

    private void updateTargetsInReach(List<Entity> targets) {
        targetsInReach = targets;
    }

    private void updateTargetsIfNeeded() {
        if (shouldUpdateTargetsInReach()) {
            List<Entity> targets = List.of();
            var hand = PlayerAttackHelper.getCurrentAttack(player, getComboCount());
            if (hand != null) {
                WeaponAttributes attributes = WeaponRegistry.getAttributes(hand.itemStack());
                var range = PlayerAttackHelper.getRangeForItem(player, hand.itemStack());
                range *= hand.attack().rangeMultiplier();
                if (attributes != null && attributes.attacks() != null) {
                    targets = TargetFinder.findAttackTargets(
                            player,
                            getCursorTarget(),
                            hand.attack(),
                            range);
                }
            }
            updateTargetsInReach(targets);
        }
    }

    @Inject(method = "tick",at = @At("HEAD"))
    private void pre_Tick(CallbackInfo ci) {
        if (player == null) {
            return;
        }
        targetsInReach = null;
        lastAttacked += 1;

        if (CombatFlags.isAttackDisabled(player)) {
            // Cancel any in-flight swing, so vanilla input resumes cleanly
            if (ongoingSwing != null) {
                cancelWeaponSwing();
            }
            return;
        }

        if (ongoingSwing != null) {
            var time = currentTime();
            var swing = ongoingSwing; // Store in local for further checks
            if (swing.ticksLeft(time) <= 0) {
                ongoingSwing = null;
            }
            if (!player.isAlive() || !swing.isValid(time)) {
                cancelWeaponSwing();
            }
        }
        cancelSwingIfNeeded();
        attackFromUpswingIfNeeded();
        updateTargetsIfNeeded();
        resetComboIfNeeded();
    }

    @Inject(method = "tick",at = @At("TAIL"))
    private void post_Tick(CallbackInfo ci) {
        if (player == null) {
            return;
        }
        if (Keybindings.toggleMineKeyBinding.consumeClick()) {
            BetterCombatClientMod.config.isMiningWithWeaponsEnabled = !BetterCombatClientMod.config.isMiningWithWeaponsEnabled;
            AutoConfig.getConfigHolder(ClientConfigWrapper.class).save();

            var message = I18n.get(BetterCombatClientMod.config.isMiningWithWeaponsEnabled ?
                    "hud.bettercombat.mine_with_weapons_on" : "hud.bettercombat.mine_with_weapons_off");
            gui.setOverlayMessage(Component.literal(message), false);
        }
    }

    private void performAttack() {
        if (Keybindings.feintKeyBinding.isDown()) {
            player.resetAttackStrengthTicker();
            cancelWeaponSwing();
            return;
        }

        var weaponSwing = this.ongoingSwing;
        if (weaponSwing == null) {
            return;
        }
        var hand = weaponSwing.attackHand();
        if (hand == null) { return; }
        var attack = hand.attack();
        var upswingRate = hand.upswingRate();
        if (player.getAttackStrengthScale(0) < (1.0 - upswingRate)) {
            return;
        }
        // System.out.println("Attack with CD: " + client.player.getAttackCooldownProgress(0));

        var cursorTarget = getCursorTarget();
        var range = PlayerAttackHelper.getRangeForItem(player, hand.itemStack());
        range *= hand.attack().rangeMultiplier();
        List<Entity> targets = TargetFinder.findAttackTargets(
                player,
                cursorTarget,
                attack,
                range);
        updateTargetsInReach(targets);
        if(targets.size() == 0) {
            PlatformClient.onEmptyLeftClick(player);

            if (hitResult.getType() == BLOCK) {
                var blockHitResult = (BlockHitResult) hitResult;
                var pos = blockHitResult.getBlockPos();
                var packet = new Packets.C2S_BlockHit(pos);
                Platform.networkC2S_Send(packet);
            }
        }

        // Mimic logic of:
        // ClientPlayerInteractionManager.attackEntity(PlayerEntity player, Entity target)
        var packet = new Packets.C2S_AttackRequest(getComboCount(), player.isShiftKeyDown(), player.getInventory().selected, cursorTarget, targets);
        Platform.networkC2S_Send(packet);
        for (var target: targets) {
            player.attack(target);
        }
        player.resetAttackStrengthTicker();
        BetterCombatClientEvents.ATTACK_HIT.invoke(handler -> {
            handler.onPlayerAttackStart(player, hand, targets, cursorTarget);
        });

        var particles = SlashParticleUtil.trailParticlesFromAttack(hand);
        var appearance = SlashParticleUtil.appearanceFor(player, hand.itemStack());
        SlashParticleUtil.spawnParticles(player, hand.isOffHand(), (float)PlayerAttackHelper.getStaticRange(player, hand.itemStack()), particles, appearance);

        setComboCount(getComboCount() + 1);
        if (!hand.isOffHand()) {
            lastAttacedWithItemStack = hand.itemStack();
        }
    }

    private AttackHand getCurrentHand() {
        return PlayerAttackHelper.getCurrentAttack(player, getComboCount());
    }

    private void setComboCount(int comboCount) {
        ((PlayerAttackProperties)player).setComboCount(comboCount);
    }

    private static boolean areItemStackEqual(ItemStack left, ItemStack right) {
        if (left == null && right == null) {
            return true;
        }
        if (left == null || right == null) {
            return false;
        }
        return ItemStack.matches(left, right);
    }

    private void setMiningCooldown(int ticks) {
        Minecraft client = thisClient();
        ((MinecraftClientAccessor) client).setAttackCooldown(ticks); // This is actually the mining cooldown
    }

    private void cancelWeaponSwing() {
        var downWind = (int)Math.round(PlayerAttackHelper.getAttackCooldownTicksCapped(player) * (1 - 0.5 * BetterCombatMod.config.upswing_multiplier));
        ((PlayerAttackAnimatable) player).stopAttackAnimation(downWind);
        var packet = Packets.AttackAnimation.stop(player.getId(), downWind);
        Platform.networkC2S_Send(packet);
        upswingStack = null;
        ongoingSwing = null;
        rightClickDelay = 0;
        setMiningCooldown(0);
    }


    // SECTION: MinecraftClient_BetterCombat

    @Override
    public int getComboCount() {
        return ((PlayerAttackProperties)player).getComboCount();
    }

    @Override
    public boolean hasTargetsInReach() {
        return targetsInReach != null && !targetsInReach.isEmpty();
    }

    @Override
    public float getSwingProgress() {
        if (lastAttacked > lastSwingDuration || lastSwingDuration <= 0) {
            return 1F;
        }
        return (float)lastAttacked / lastSwingDuration;
    }

    @Override
    public int getUpswingTicks() {
        return currentUpswingTicks();
    }

    @Override
    public void cancelUpswing() {
        if (currentUpswingTicks() > 0) {
            cancelWeaponSwing();
        }
    }

    @Override
    public AttackHand getCurrentAttackHand() {
        if (this.ongoingSwing != null) {
            return this.ongoingSwing.attackHand();
        }
        return null;
    }
}
