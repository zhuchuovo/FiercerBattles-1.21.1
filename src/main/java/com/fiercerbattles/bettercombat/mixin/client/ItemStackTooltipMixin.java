package com.fiercerbattles.bettercombat.mixin.client;

import com.fiercerbattles.bettercombat.client.BetterCombatClientMod;
import com.fiercerbattles.bettercombat.client.WeaponAttributeTooltip;
import com.fiercerbattles.bettercombat.logic.EntityAttributeHelper;
import com.fiercerbattles.bettercombat.logic.WeaponRegistry;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Consumer;

@Mixin(ItemStack.class)
public class ItemStackTooltipMixin {
    @Inject(method = "addModifierTooltip", at = @At("HEAD"), cancellable = true)
    private void appendAttributeModifierTooltip_BetterCombat_Range(Consumer<Component> textConsumer, Player player,
                                                                   Holder<Attribute> attribute, AttributeModifier modifier, CallbackInfo ci) {
        if (BetterCombatClientMod.config.isTooltipAttackRangeReformat
                && attribute.value() == Attributes.ENTITY_INTERACTION_RANGE.value()
                && player != null) { // Even vanilla code checks for this
            var itemStack = (ItemStack) (Object) this;
            if (WeaponRegistry.getAttributes(itemStack) != null                     // Only for weapons
                    && EntityAttributeHelper.rangeModifierCount(itemStack) == 1) {  // Only if there is exactly one range modifier
                ci.cancel();
                var value = modifier.amount() + player.getAttributeBaseValue(Attributes.ENTITY_INTERACTION_RANGE);
                textConsumer.accept(WeaponAttributeTooltip.attackRangeLine(value));
            }
        }
    }
}
