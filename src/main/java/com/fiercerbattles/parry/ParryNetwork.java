package com.fiercerbattles.parry;

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

/** The server owns both timing and interruption; no client classes are loaded here. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ParryNetwork {
    public enum Kind { PARRY, STAGGER }
    private static Consumer<Sync> clientSync = ignored -> {};
    private static Consumer<Start> clientStart = ignored -> {};
    private static Consumer<Stop> clientStop = ignored -> {};
    private static Consumer<Reject> clientReject = ignored -> {};

    private ParryNetwork() {}

    public static void setClientHandlers(Consumer<Sync> sync, Consumer<Start> start,
                                        Consumer<Stop> stop, Consumer<Reject> reject) {
        clientSync = sync; clientStart = start; clientStop = stop; clientReject = reject;
    }

    public static void request() { PacketDistributor.sendToServer(new Request()); }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("parry-1");
        registrar.playToServer(Request.TYPE, Request.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player && !ParryServer.tryStart(player)) {
                PacketDistributor.sendToPlayer(player, new Reject());
            }
        });
        registrar.playToClient(Sync.TYPE, Sync.STREAM_CODEC, (payload, context) -> clientSync.accept(payload));
        registrar.playToClient(Start.TYPE, Start.STREAM_CODEC, (payload, context) -> clientStart.accept(payload));
        registrar.playToClient(Stop.TYPE, Stop.STREAM_CODEC, (payload, context) -> clientStop.accept(payload));
        registrar.playToClient(Reject.TYPE, Reject.STREAM_CODEC, (payload, context) -> clientReject.accept(payload));
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, name));
    }

    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = ParryNetwork.type("parry_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {}, buffer -> new Request());
        @Override public Type<Request> type() { return TYPE; }
    }

    public record Reject() implements CustomPacketPayload {
        public static final Type<Reject> TYPE = ParryNetwork.type("parry_reject");
        public static final StreamCodec<RegistryFriendlyByteBuf, Reject> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {}, buffer -> new Reject());
        @Override public Type<Reject> type() { return TYPE; }
    }

    public record Sync(String hash, byte[] data) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = ParryNetwork.type("parry_sync");
        public static final StreamCodec<RegistryFriendlyByteBuf, Sync> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> { buffer.writeUtf(value.hash, 64); buffer.writeByteArray(value.data); },
                buffer -> new Sync(buffer.readUtf(64), buffer.readByteArray(ParryAnimations.MAX_SYNC_BYTES)));
        @Override public Type<Sync> type() { return TYPE; }
    }

    public record Start(UUID sessionId, int actorId, UUID actorUuid, ResourceLocation dimension, Kind kind,
                        long startGameTime, int durationTicks, String animationHash,
                        double x, double y, double z, float yaw) implements CustomPacketPayload {
        public static final Type<Start> TYPE = ParryNetwork.type("parry_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, Start> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUUID(value.sessionId); buffer.writeVarInt(value.actorId); buffer.writeUUID(value.actorUuid);
                    buffer.writeResourceLocation(value.dimension); buffer.writeEnum(value.kind);
                    buffer.writeVarLong(value.startGameTime); buffer.writeVarInt(value.durationTicks);
                    buffer.writeUtf(value.animationHash, 64);
                    buffer.writeDouble(value.x); buffer.writeDouble(value.y); buffer.writeDouble(value.z); buffer.writeFloat(value.yaw);
                }, buffer -> new Start(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(),
                        buffer.readResourceLocation(), buffer.readEnum(Kind.class), buffer.readVarLong(),
                        buffer.readVarInt(), buffer.readUtf(64), buffer.readDouble(), buffer.readDouble(),
                        buffer.readDouble(), buffer.readFloat()));
        @Override public Type<Start> type() { return TYPE; }
    }

    public record Stop(UUID sessionId, int actorId, UUID actorUuid) implements CustomPacketPayload {
        public static final Type<Stop> TYPE = ParryNetwork.type("parry_stop");
        public static final StreamCodec<RegistryFriendlyByteBuf, Stop> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> { buffer.writeUUID(value.sessionId); buffer.writeVarInt(value.actorId); buffer.writeUUID(value.actorUuid); },
                buffer -> new Stop(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID()));
        @Override public Type<Stop> type() { return TYPE; }
    }
}
