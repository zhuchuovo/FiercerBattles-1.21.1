package com.fiercerbattles.bettercombat.api.component;

import com.mojang.serialization.Codec;
import net.minecraft.resources.ResourceLocation;

public record WeaponAttributesIdComponent(ResourceLocation id) {
    public static final Codec<WeaponAttributesIdComponent> CODEC = ResourceLocation.CODEC.xmap(WeaponAttributesIdComponent::new, WeaponAttributesIdComponent::id);
}
