package com.fiercerbattles.mobcombat.network;

import com.fiercerbattles.mobcombat.BetterMobCombatReimagined;
import com.fiercerbattles.mobcombat.api.RangedWeaponKind;
import com.fiercerbattles.mobcombat.client.ClientPayloadHandler;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public record MobRangedReleasePayload(
        int entityId,
        int weaponKind,
        boolean offHand
) implements CustomPacketPayload {
    public static final Type<MobRangedReleasePayload> TYPE =
            new Type<>(BetterMobCombatReimagined.id("mob_ranged_release"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MobRangedReleasePayload> STREAM_CODEC =
            StreamCodec.ofMember(MobRangedReleasePayload::write, MobRangedReleasePayload::decode);

    public static MobRangedReleasePayload create(
            int entityId,
            RangedWeaponKind kind,
            boolean offHand
    ) {
        return new MobRangedReleasePayload(entityId, kind.ordinal(), offHand);
    }

    private static MobRangedReleasePayload decode(RegistryFriendlyByteBuf buffer) {
        return new MobRangedReleasePayload(
                buffer.readVarInt(),
                buffer.readVarInt(),
                buffer.readBoolean()
        );
    }

    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(this.entityId);
        buffer.writeVarInt(this.weaponKind);
        buffer.writeBoolean(this.offHand);
    }

    public static void handle(MobRangedReleasePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ClientPayloadHandler.handleMobRangedRelease(payload));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
