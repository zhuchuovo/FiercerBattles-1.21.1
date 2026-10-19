package com.fiercerbattles.mixin;

import com.fiercerbattles.execution.client.ExecutionHighlight;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(OverlayTexture.class)
public abstract class ExecutionOverlayTextureMixin {
    @Shadow @Final private DynamicTexture texture;

    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/texture/DynamicTexture;<init>(IIZ)V"), index = 0)
    private int fiercerbattles$extendOverlayWidth(int width) {
        return ExecutionHighlight.OVERLAY_WIDTH;
    }

    @Inject(method = "<init>", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/NativeImage;upload(IIIIIIIZZZZ)V"))
    private void fiercerbattles$addFineWhiteRamp(CallbackInfo ci) {
        NativeImage pixels = texture.getPixels();
        // Preserve vanilla's first 16 columns. The extra ramp has 1/255 opacity steps,
        // avoiding the visible 5% jumps of vanilla's white overlay for a subtle pulse.
        for (int row = 0; row < pixels.getHeight(); row++) {
            for (int column = ExecutionHighlight.WHITE_U_START; column < pixels.getWidth(); column++) {
                int color = row < 8 ? pixels.getPixelRGBA(0, row)
                        : (255 - (column - ExecutionHighlight.WHITE_U_START)) << 24 | 0xFFFFFF;
                pixels.setPixelRGBA(column, row, color);
            }
        }
    }
}
