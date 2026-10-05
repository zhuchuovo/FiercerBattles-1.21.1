package com.fiercerbattles.execution;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.FiercerBattlesExecutionServer;
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

/** Common payloads contain no client classes, so dedicated servers can register them safely. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ExecutionNetwork {
    private static Consumer<Start> clientStart = ignored -> {};
    private static Consumer<End> clientEnd = ignored -> {};
    private static Consumer<Reject> clientReject = ignored -> {};

    private ExecutionNetwork() {}

    public static void setClientHandlers(Consumer<Start> start, Consumer<End> end, Consumer<Reject> reject) {
        clientStart = start;
        clientEnd = end;
        clientReject = reject;
    }

    public static void request(int targetEntityId) {
        PacketDistributor.sendToServer(new Request(targetEntityId));
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("execution-2");
        registrar.playToServer(Request.TYPE, Request.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                FiercerBattlesExecutionServer.tryStart(player, payload.targetEntityId());
            }
        });
        registrar.playToClient(Start.TYPE, Start.STREAM_CODEC, (payload, context) -> clientStart.accept(payload));
        registrar.playToClient(End.TYPE, End.STREAM_CODEC, (payload, context) -> clientEnd.accept(payload));
        registrar.playToClient(Reject.TYPE, Reject.STREAM_CODEC, (payload, context) -> clientReject.accept(payload));
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String name) {
        return new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, name));
    }

    public record Request(int targetEntityId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = ExecutionNetwork.type("execution_request");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeVarInt(value.targetEntityId()),
                buffer -> new Request(buffer.readVarInt()));

        @Override public Type<Request> type() { return TYPE; }
    }

    public record Start(
            UUID sessionId, int executorId, UUID executorUuid, int victimId, UUID victimUuid,
            ResourceLocation dimension, long startGameTime, int durationTicks,
            double executorX, double executorY, double executorZ, float executorYaw,
            double victimX, double victimY, double victimZ, float victimYaw
    ) implements CustomPacketPayload {
        public static final Type<Start> TYPE = ExecutionNetwork.type("execution_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, Start> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUUID(value.sessionId());
                    buffer.writeVarInt(value.executorId());
                    buffer.writeUUID(value.executorUuid());
                    buffer.writeVarInt(value.victimId());
                    buffer.writeUUID(value.victimUuid());
                    buffer.writeResourceLocation(value.dimension());
                    buffer.writeVarLong(value.startGameTime());
                    buffer.writeVarInt(value.durationTicks());
                    buffer.writeDouble(value.executorX());
                    buffer.writeDouble(value.executorY());
                    buffer.writeDouble(value.executorZ());
                    buffer.writeFloat(value.executorYaw());
                    buffer.writeDouble(value.victimX());
                    buffer.writeDouble(value.victimY());
                    buffer.writeDouble(value.victimZ());
                    buffer.writeFloat(value.victimYaw());
                },
                buffer -> new Start(buffer.readUUID(), buffer.readVarInt(), buffer.readUUID(),
                        buffer.readVarInt(), buffer.readUUID(), buffer.readResourceLocation(),
                        buffer.readVarLong(), buffer.readVarInt(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat()));

        @Override public Type<Start> type() { return TYPE; }
    }

    public record End(UUID sessionId, boolean completed,
                      double executorX, double executorY, double executorZ, float executorYaw) implements CustomPacketPayload {
        public static final Type<End> TYPE = ExecutionNetwork.type("execution_end");
        public static final StreamCodec<RegistryFriendlyByteBuf, End> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUUID(value.sessionId());
                    buffer.writeBoolean(value.completed());
                    buffer.writeDouble(value.executorX());
                    buffer.writeDouble(value.executorY());
                    buffer.writeDouble(value.executorZ());
                    buffer.writeFloat(value.executorYaw());
                }, buffer -> new End(buffer.readUUID(), buffer.readBoolean(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat()));

        @Override public Type<End> type() { return TYPE; }
    }

    public record Reject(int targetEntityId) implements CustomPacketPayload {
        public static final Type<Reject> TYPE = ExecutionNetwork.type("execution_reject");
        public static final StreamCodec<RegistryFriendlyByteBuf, Reject> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> buffer.writeVarInt(value.targetEntityId()),
                buffer -> new Reject(buffer.readVarInt()));

        @Override public Type<Reject> type() { return TYPE; }
    }
}
