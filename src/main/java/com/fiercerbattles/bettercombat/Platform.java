package com.fiercerbattles.bettercombat;

import java.util.Collection;
import com.fiercerbattles.bettercombat.neoforge.PlatformImpl;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

// Embedded in Fiercer Battles: the Architectury @ExpectPlatform stubs are replaced by
// direct calls to the NeoForge implementation.
public class Platform {
    public static final boolean Fabric;
    public static final boolean Forge;
    public static final boolean NeoForge;

    static
    {
        Fabric = getPlatformType() == Type.FABRIC;
        Forge  = getPlatformType() == Type.FORGE;
        NeoForge = getPlatformType() == Type.NEOFORGE;
    }

    public enum Type { FABRIC, FORGE, NEOFORGE }

    protected static Type getPlatformType() {
        return PlatformImpl.getPlatformType();
    }

    public static boolean isModLoaded(String modid) {
        return PlatformImpl.isModLoaded(modid);
    }

    public static boolean isCastingSpell(Player player) {
        return PlatformImpl.isCastingSpell(player);
    }

    // MARK: Network hooks

    public static FriendlyByteBuf createByteBuffer() {
        return PlatformImpl.createByteBuffer();
    }

    public static Collection<ServerPlayer> tracking(ServerPlayer player) {
        return PlatformImpl.tracking(player);
    }

    public static Collection<ServerPlayer> around(ServerLevel world, Vec3 origin, double distance) {
        return PlatformImpl.around(world, origin, distance);
    }

    public static boolean networkS2C_CanSend(ServerPlayer player, ResourceLocation packetId) {
        return PlatformImpl.networkS2C_CanSend(player, packetId);
    }

    public static void networkS2C_Send(ServerPlayer player, CustomPacketPayload payload) {
        PlatformImpl.networkS2C_Send(player, payload);
    }

    public static void networkC2S_Send(CustomPacketPayload payload) {
        PlatformImpl.networkC2S_Send(payload);
    }
}
