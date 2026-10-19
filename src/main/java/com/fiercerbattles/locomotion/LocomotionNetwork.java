package com.fiercerbattles.locomotion;

import com.fiercerbattles.FiercerBattles;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.UUID;
import java.util.function.Consumer;

/** The server owns roll/prone sessions; the run clip is a purely client-side presentation of sprinting. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class LocomotionNetwork {
    /** ROLL plays once; PRONE drops down then crawls until STAND plays the stand-up clip. */
    public enum Kind { ROLL, PRONE, STAND }
    private static Consumer<Sync> clientSync = ignored -> {};
    private static Consumer<Start> clientStart = ignored -> {};
    private static Consumer<Stop> clientStop = ignored -> {};
    private static Consumer<Reject> clientReject = ignored -> {};

    private LocomotionNetwork() {}

    public static void setClientHandlers(Consumer<Sync> sync, Consumer<Start> start,
                                        Consumer<Stop> stop, Consumer<Reject> reject) {
        clientSync = sync; clientStart = start; clientStop = stop; clientReject = reject;
    }

    public static void requestRoll(float yaw) { PacketDistributor.sendToServer(new RollRequest(yaw)); }
    public static void requestProne(boolean down, float yaw) { PacketDistributor.sendToServer(new ProneRequest(down, yaw)); }
    public static void releaseRoll(UUID sessionId) { PacketDistributor.sendToServer(new RollRelease(sessionId)); }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("locomotion-1");
        registrar.playToServer(RollRequest.TYPE, RollRequest.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player && !LocomotionServer.tryRoll(player, payload.yaw())) {
                PacketDistributor.sendToPlayer(player, new Reject(Kind.ROLL));
            }
        });
        registrar.playToServer(ProneRequest.TYPE, ProneRequest.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player && !LocomotionServer.tryProne(player, payload.down(), payload.yaw())) {
                PacketDistributor.sendToPlayer(player, new Reject(payload.down() ? Kind.PRONE : Kind.STAND));
            }
        });
        registrar.playToServer(RollRelease.TYPE, RollRelease.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) LocomotionServer.releaseRoll(player, payload.sessionId());
        });
        registrar.playToClient(Sync.TYPE, Sync.STREAM_CODEC, (payload, context) -> clientSync.accept(payload));
        registrar.playToClient(Start.TYPE, Start.STREAM_CODEC, (payload, context) -> clientStart.accept(payload));
        registrar.playToClient(Stop.TYPE, Stop.STREAM_CODEC, (payload, context) -> clientStop.accept(payload));
        registrar.playToClient(Reject.TYPE, Reject.STREAM_CODEC, (payload, context) -> clientReject.accept(payload));
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, name));
    }

    public record RollRequest(float yaw) implements CustomPacketPayload {
        public static final Type<RollRequest> TYPE = LocomotionNetwork.type("roll_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, RollRequest> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeFloat(value.yaw), buffer -> new RollRequest(buffer.readFloat()));
        @Override public Type<RollRequest> type() { return TYPE; }
    }

    /** {@code yaw} is the held movement heading, used when a sprinting player dives into prone. */
    public record ProneRequest(boolean down, float yaw) implements CustomPacketPayload {
        public static final Type<ProneRequest> TYPE = LocomotionNetwork.type("prone_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, ProneRequest> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> { buffer.writeBoolean(value.down); buffer.writeFloat(value.yaw); },
                buffer -> new ProneRequest(buffer.readBoolean(), buffer.readFloat()));
        @Override public Type<ProneRequest> type() { return TYPE; }
    }

    /** The roller moved or acted inside the recovery window; the server ends that roll session early. */
    public record RollRelease(UUID sessionId) implements CustomPacketPayload {
        public static final Type<RollRelease> TYPE = LocomotionNetwork.type("roll_release");
        public static final StreamCodec<RegistryFriendlyByteBuf, RollRelease> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeUUID(value.sessionId), buffer -> new RollRelease(buffer.readUUID()));
        @Override public Type<RollRelease> type() { return TYPE; }
    }

    public record Reject(Kind kind) implements CustomPacketPayload {
        public static final Type<Reject> TYPE = LocomotionNetwork.type("locomotion_reject");
        public static final StreamCodec<RegistryFriendlyByteBuf, Reject> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeEnum(value.kind), buffer -> new Reject(buffer.readEnum(Kind.class)));
        @Override public Type<Reject> type() { return TYPE; }
    }

    public record Sync(String hash, byte[] data) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = LocomotionNetwork.type("locomotion_sync");
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> { buffer.writeUtf(value.hash, 64); buffer.writeByteArray(value.data); },
                buffer -> new Sync(buffer.readUtf(64), buffer.readByteArray(LocomotionAnimations.MAX_SYNC_BYTES)));
        @Override public Type<Sync> type() { return TYPE; }
    }

    /** {@code slide}: a PRONE that began from a sprint, diving along {@code yaw}. */
    public record Start(UUID sessionId, int actorId, UUID actorUuid, ResourceLocation dimension, Kind kind,
                        long startGameTime, String animationHash, float yaw, boolean slide) implements CustomPacketPayload {
        public static final Type<Start> TYPE = LocomotionNetwork.type("locomotion_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, Start> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUUID(value.sessionId); buffer.writeVarInt(value.actorId); buffer.writeUUID(value.actorUuid);
                    buffer.writeResourceLocation(value.dimension); buffer.writeEnum(value.kind);
                    buffer.writeVarLong(value.startGameTime); buffer.writeUtf(value.animationHash, 64);
                    buffer.writeFloat(value.yaw); buffer.writeBoolean(value.slide);
                }, buffer -> new Start(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(),
                        buffer.readResourceLocation(), buffer.readEnum(Kind.class), buffer.readVarLong(),
                        buffer.readUtf(64), buffer.readFloat(), buffer.readBoolean()));
        @Override public Type<Start> type() { return TYPE; }
    }

    public record Stop(UUID sessionId, int actorId, UUID actorUuid) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = LocomotionNetwork.type("locomotion_stop");
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> { buffer.writeUUID(value.sessionId); buffer.writeVarInt(value.actorId); buffer.writeUUID(value.actorUuid); },
                buffer -> new Stop(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID()));
        @Override public Type<Stop> type() { return TYPE; }
    }
}
