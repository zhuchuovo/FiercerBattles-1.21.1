package com.fiercerbattles.bettercombat.mixin.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.fiercerbattles.bettercombat.api.MinecraftClient_BetterCombat;
import com.fiercerbattles.bettercombat.client.BetterCombatClientMod;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Gui.class)
public abstract class InGameHudInject {
    @Inject(method = "renderCrosshair", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lnet/minecraft/resources/ResourceLocation;IIII)V"))
    private void pre_renderCrosshair(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        if (BetterCombatClientMod.config.isHighlightCrosshairEnabled) {
            setShaderForHighlighting();
        }
    }

    @Inject(method = "renderCrosshair", at = @At(value = "TAIL"))
    private void post_renderCrosshair(GuiGraphics context, DeltaTracker tickCounter, CallbackInfo ci) {
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
    }

    @Unique
    private void setShaderForHighlighting() {
        if (((MinecraftClient_BetterCombat) Minecraft.getInstance()).hasTargetsInReach()) {
            var color = BetterCombatClientMod.config.hudHighlightColor;
            float red = ((float) ((color >> 16) & 0xFF)) / 255F;
            float green = ((float) ((color >> 8) & 0xFF)) / 255F;
            float blue = ((float) (color & 0xFF)) / 255F;
            float alpha = 0.5F;
            RenderSystem.setShaderColor(red, green, blue, alpha);
        }
    }
}