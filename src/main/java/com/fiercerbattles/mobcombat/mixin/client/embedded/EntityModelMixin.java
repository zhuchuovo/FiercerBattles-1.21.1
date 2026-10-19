package com.fiercerbattles.mobcombat.mixin.client.embedded;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;

/** Extensible model-copy hook used by the embedded illager animation bridge. */
@Mixin(EntityModel.class)
public abstract class EntityModelMixin<T extends Entity> extends Model {
    protected EntityModelMixin(Function<ResourceLocation, RenderType> renderType) {
        super(renderType);
    }

    @Inject(method = "copyPropertiesTo", at = @At("RETURN"))
    private void bmc$copyEmbeddedAnimationState(EntityModel<T> otherModel, CallbackInfo ci) {
        this.bmc$copyMutatedAttributes(otherModel);
    }

    @Unique
    protected void bmc$copyMutatedAttributes(EntityModel<T> otherModel) {
    }
}
