package com.fiercerbattles.mobcombat.network;

import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.mobcombat.client.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MobAttackPayload(
        int entityId,
        String animationId,
        boolean offHand,
        boolean twoHanded,
        float length,
        float animationUpswing,
        float damageUpswing
) implements CustomPacketPayload {
    public static final Type<MobAttackPayload> TYPE = new Type<>(BetterMobCombatReimagined.id("mob_attack"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MobAttackPayload> STREAM_CODEC =
            StreamCodec.ofMember(MobAttackPayload::write, MobAttackPayload::decode);

    private static MobAttackPayload decode(RegistryFriendlyByteBuf buffer) {
        return new MobAttackPayload(
                buffer.readVarInt(),
                buffer.readUtf(256),
                buffer.readBoolean(),
                buffer.readBoolean(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat()
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(entityId);
        buffer.writeUtf(animationId, 256);
        buffer.writeBoolean(offHand);
        buffer.writeBoolean(twoHanded);
        buffer.writeFloat(length);
        buffer.writeFloat(animationUpswing);
        buffer.writeFloat(damageUpswing);
    }

    public static void handle(MobAttackPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handleMobAttack(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
