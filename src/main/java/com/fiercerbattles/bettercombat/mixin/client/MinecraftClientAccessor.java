package com.fiercerbattles.bettercombat.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface MinecraftClientAccessor {
    @Accessor
    int getMissTime();
    @Accessor("missTime")
    void setAttackCooldown(int attackCooldown);
    @Accessor
    EntityRenderDispatcher getEntityRenderDispatcher();
}