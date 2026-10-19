package com.fiercerbattles.bettercombat.neoforge;

import io.netty.buffer.Unpooled;
import com.fiercerbattles.bettercombat.Platform;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import com.fiercerbattles.bettercombat.client.compat.SpellEngineCompatibility;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import java.util.Collection;

import static com.fiercerbattles.bettercombat.Platform.Type.NEOFORGE;

public class PlatformImpl {
    public static Platform.Type getPlatformType() {
        return NEOFORGE;
    }

    public static boolean isModLoaded(String modid) {
        return ModList.get().isLoaded(modid);
    }

    public static boolean isCastingSpell(Player player) {
        return SpellEngineCompatibility.isCastingSpell(player);
    }

    public static Collection<ServerPlayer> tracking(ServerPlayer player) {
        return (Collection<ServerPlayer>) player.level().players();
    }

    public static FriendlyByteBuf createByteBuffer() {
        return new FriendlyByteBuf(Unpooled.buffer());
    }

    public static Collection<ServerPlayer> around(ServerLevel world, Vec3 origin, double distance) {
        return world.getPlayers((player) -> player.position().distanceToSqr(origin) <= (distance*distance));
    }

    public static boolean networkS2C_CanSend(ServerPlayer player, ResourceLocation packetId) {
        return true;
    }

    public static void networkS2C_Send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void networkC2S_Send(CustomPacketPayload payload) {
        PacketDistributor.sendToServer(payload);
    }
}
