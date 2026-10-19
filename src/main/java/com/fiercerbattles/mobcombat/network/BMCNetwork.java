package com.fiercerbattles.mobcombat.network;

import com.fiercerbattles.mobcombat.api.RangedWeaponKind;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class BMCNetwork {
    private static final String PROTOCOL_VERSION = "3";

    private BMCNetwork() {
    }

    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(MobAttackPayload.TYPE, MobAttackPayload.STREAM_CODEC, MobAttackPayload::handle);
        registrar.playToClient(
                MobRangedReleasePayload.TYPE,
                MobRangedReleasePayload.STREAM_CODEC,
                MobRangedReleasePayload::handle
        );
    }

    public static void sendRangedRelease(Mob mob, RangedWeaponKind kind, boolean offHand) {
        PacketDistributor.sendToPlayersTrackingEntity(
                mob,
                MobRangedReleasePayload.create(mob.getId(), kind, offHand)
        );
    }

    public static void sendAttackAnimation(
            Mob mob,
            String animationId,
            boolean offHand,
            boolean twoHanded,
            float length,
            float animationUpswing,
            float damageUpswing
    ) {
        PacketDistributor.sendToPlayersTrackingEntity(
                mob,
                new MobAttackPayload(
                        mob.getId(),
                        animationId,
                        offHand,
                        twoHanded,
                        length,
                        animationUpswing,
                        damageUpswing
                )
        );
    }
}
