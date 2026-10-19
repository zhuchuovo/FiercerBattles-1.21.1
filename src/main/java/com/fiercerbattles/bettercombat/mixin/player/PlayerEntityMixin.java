package com.fiercerbattles.bettercombat.mixin.player;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.fiercerbattles.bettercombat.BetterCombatMod;
import com.fiercerbattles.bettercombat.api.AttackHand;
import com.fiercerbattles.bettercombat.api.CombatFlags;
import com.fiercerbattles.bettercombat.api.EntityPlayer_BetterCombat;
import com.fiercerbattles.bettercombat.api.WeaponAttributes;
import com.fiercerbattles.bettercombat.client.animation.PlayerAttackAnimatable;
import com.fiercerbattles.bettercombat.logic.PlayerAttackHelper;
import com.fiercerbattles.bettercombat.logic.PlayerAttackProperties;
import com.fiercerbattles.bettercombat.logic.Pose;
import com.fiercerbattles.bettercombat.logic.WeaponRegistry;
import net.minecraft.core.Holder;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.minecraft.world.entity.EquipmentSlot.OFFHAND;


@Mixin(value = Player.class, priority = 899)
public abstract class PlayerEntityMixin implements PlayerAttackProperties, EntityPlayer_BetterCombat {
    private int comboCount = 0;
    public int getComboCount() {
        return comboCount;
    }
    public void setComboCount(int comboCount) {
        this.comboCount = comboCount;
    }

    private static final EntityDataAccessor<String> BETTER_COMBAT_MAIN_IDLE_ANIMATION = SynchedEntityData.defineId(Player.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<String> BETTER_COMBAT_OFF_IDLE_ANIMATION = SynchedEntityData.defineId(Player.class, EntityDataSerializers.STRING);
    /*
     * Per-player combat flags, see `CombatFlags` for the public API.
     *   Bit 0 (0b00000001) TAG_DISABLED - attacks disabled, mirror of the `bettercombat_disabled` command tag, written only by `updateCombatFlagsFromCommandTags`
     *   Bit 1 (0b00000010) API_DISABLED - attacks disabled by a mod, written only via `CombatFlags.setAttacksDisabled`
     *   Bit 2 (0b00000100) unused, reserved for future flags (pose suppression, HUD)
     *   Bit 3 (0b00001000) unused
     *   Bit 4 (0b00010000) unused
     *   Bit 5 (0b00100000) unused
     *   Bit 6 (0b01000000) unused
     *   Bit 7 (0b10000000) unused
     */
    private static final EntityDataAccessor<Byte> BETTER_COMBAT_FLAGS = SynchedEntityData.defineId(Player.class, EntityDataSerializers.BYTE);

    @Inject(method = "defineSynchedData", at = @At("TAIL"))
    private void initDataTracker_TAIL_SpellEngine_SyncEffects(SynchedEntityData.Builder builder, CallbackInfo ci) {
        builder.define(BETTER_COMBAT_MAIN_IDLE_ANIMATION, "");
        builder.define(BETTER_COMBAT_OFF_IDLE_ANIMATION, "");
        builder.define(BETTER_COMBAT_FLAGS, (byte) 0);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    public void post_Tick(CallbackInfo ci) {
        var instance = (Object)this;
        var player = ((Player)instance);

        if (player.level().isClientSide()) {
            ((PlayerAttackAnimatable) this).updateAnimationsOnTick();
        } else {
            var pose = PlayerAttackHelper.poseForPlayer(player);
            player.getEntityData().set(BETTER_COMBAT_MAIN_IDLE_ANIMATION, pose.base());
            player.getEntityData().set(BETTER_COMBAT_OFF_IDLE_ANIMATION, pose.offHand());
            updateCombatFlagsFromCommandTags(player);
        }
        updateDualWieldingSpeedBoost();
    }

    // FEATURE: Per-player disable via vanilla command tag
    // Tags have no change event, and NBT loading bypasses addCommandTag/removeCommandTag,
    // so the tag is polled every tick. Diffing self-heals join, NBT load and respawn.
    private void updateCombatFlagsFromCommandTags(Player player) {
        var tagged = player.getTags().contains(CombatFlags.DISABLED_TAG);
        var flags = getCombatFlags();
        var mirrored = (flags & CombatFlags.TAG_DISABLED) != 0;
        if (tagged != mirrored) {
            setCombatFlags((byte) (tagged
                    ? flags | CombatFlags.TAG_DISABLED
                    : flags & ~CombatFlags.TAG_DISABLED));
        }
    }

    public byte getCombatFlags() {
        return ((Player) ((Object)this)).getEntityData().get(BETTER_COMBAT_FLAGS);
    }

    public void setCombatFlags(byte flags) {
        ((Player) ((Object)this)).getEntityData().set(BETTER_COMBAT_FLAGS, flags);
    }

    public String getMainHandIdleAnimation() {
        return ((Player) ((Object)this)).getEntityData().get(BETTER_COMBAT_MAIN_IDLE_ANIMATION);
    }

    public String getOffHandIdleAnimation() {
        return ((Player) ((Object)this)).getEntityData().get(BETTER_COMBAT_OFF_IDLE_ANIMATION);
    }

    // FEATURE: Disable sweeping for attributed weapons

    @ModifyVariable(method = "attack", at = @At("STORE"), ordinal = 3)
    private boolean disableSweeping(boolean value) {
        if (BetterCombatMod.config.allow_vanilla_sweeping) {
            return value;
        }

        var player = ((Player) ((Object)this));
        var currentHand = PlayerAttackHelper.getCurrentAttack(player, comboCount);
        if (currentHand != null) {
            // Disable sweeping
            return false;
        }
        return value;
    }

    // FEATURE: Two-handed wielding

    @Inject(method = "getItemBySlot", at = @At("HEAD"), cancellable = true)
    public void getEquippedStack_Pre(EquipmentSlot slot, CallbackInfoReturnable<ItemStack> cir) {
        var mainHandHasTwoHanded = false;
        var mainHandStack = ((PlayerEntityAccessor) this).getInventory().getSelected();
        var mainHandAttributes = WeaponRegistry.getAttributes(mainHandStack);
        if (mainHandAttributes != null && mainHandAttributes.isTwoHanded()) {
            mainHandHasTwoHanded = true;
        }

        var offHandHasTwoHanded = false;
        var offHandStack = ((PlayerEntityAccessor)this).getInventory().offhand.get(0);
        var offHandAttributes = WeaponRegistry.getAttributes(offHandStack);
        if(offHandAttributes != null && offHandAttributes.isTwoHanded()) {
            offHandHasTwoHanded = true;
        }

        if (slot == OFFHAND) {
            if (mainHandHasTwoHanded || offHandHasTwoHanded) {
                cir.setReturnValue(ItemStack.EMPTY);
                cir.cancel();
                return;
            }
        }
    }

    // FEATURE: Dual wielding

    private Multimap<Holder<Attribute>, AttributeModifier> dualWieldingAttributeMap;
    private static final ResourceLocation dualWieldingSpeedModifierId = ResourceLocation.fromNamespaceAndPath(BetterCombatMod.ID, "dual_wield");


    // FIXME: Replace with high level multiplied Mixin, WrapOperation player.getAttributes(...)
    private void updateDualWieldingSpeedBoost() {
        var player = ((Player) ((Object)this));
        var newState = PlayerAttackHelper.isDualWielding(player);
        var currentState = dualWieldingAttributeMap != null;
        if (newState != currentState) {
            if(newState) {
                // Just started dual wielding
                // Adding speed boost modifier
                this.dualWieldingAttributeMap = HashMultimap.create();
                double multiplier = BetterCombatMod.config.dual_wielding_attack_speed_multiplier - 1;
                dualWieldingAttributeMap.put(
                        Attributes.ATTACK_SPEED,
                        new AttributeModifier(
                                dualWieldingSpeedModifierId,
                                multiplier,
                                AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
                player.getAttributes().addTransientAttributeModifiers(dualWieldingAttributeMap);
            } else {
                // Just stopped dual wielding
                // Removing speed boost modifier
                if (dualWieldingAttributeMap != null) { // Safety first... Who knows...
                    player.getAttributes().removeAttributeModifiers(dualWieldingAttributeMap);
                    dualWieldingAttributeMap = null;
                }
            }
        }
    }

    @ModifyArg(method = "attack", at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"),
            index = 0)
    public InteractionHand getHand(InteractionHand hand) {
        var player = ((Player) ((Object)this) );
        var currentHand = PlayerAttackHelper.getCurrentAttack(player, comboCount);
        if (currentHand != null) {
            return currentHand.isOffHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        } else {
            return InteractionHand.MAIN_HAND;
        }
    }

    private AttackHand lastAttack;

    @Redirect(method = "attack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
    public ItemStack getMainHandStack_Redirect(Player instance) {
        // DUAL WIELDING LOGIC
        // Here we return the off-hand stack as fake main-hand, purpose:
        // - Getting enchants
        // - Getting itemstack to be damaged
        if (comboCount < 0) {
            // Vanilla behaviour
            return instance.getMainHandItem();
        }
        var hand = PlayerAttackHelper.getCurrentAttack(instance, comboCount);
        if (hand == null) {
            var isOffHand = PlayerAttackHelper.shouldAttackWithOffHand(instance, comboCount);
            if (isOffHand) {
                return ItemStack.EMPTY;
            } else {
                return instance.getMainHandItem();
            }
        }
        lastAttack = hand;
        return hand.itemStack();
    }

    @Redirect(method = "attack", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;setItemInHand(Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/item/ItemStack;)V"))
    public void setStackInHand_Redirect(Player instance, InteractionHand handArg, ItemStack itemStack) {
        // DUAL WIELDING LOGIC
        // In case item got destroyed due to durability loss
        // We empty the correct hand
        if (comboCount < 0) {
            // Vanilla behaviour
            instance.setItemInHand(handArg, itemStack);
        }
        // `handArg` argument is always `MAIN`, we can ignore it
        AttackHand hand = lastAttack;
        if (hand == null) {
            hand = PlayerAttackHelper.getCurrentAttack(instance, comboCount);
        }
        if (hand == null) {
            instance.setItemInHand(handArg, itemStack);
            return;
        }
        var redirectedHand = hand.isOffHand() ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        instance.setItemInHand(redirectedHand, itemStack);
    }

    // SECTION: BetterCombatPlayer

    @Nullable
    public AttackHand getCurrentAttack() {
        if (comboCount < 0) {
            return null;
        }
        var player = ((Player) ((Object)this));
        return PlayerAttackHelper.getCurrentAttack(player, comboCount);
    }
}
