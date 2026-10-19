package com.fiercerbattles.execution;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.FiercerBattlesExecutionServer;
import com.fiercerbattles.config.FiercerBattlesConfig;
import com.fiercerbattles.execution.action.PairedExecutionAction;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Common payloads contain no client classes, so dedicated servers can register them safely. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
public final class ExecutionNetwork {
    private static Consumer<Start> clientStart = ignored -> {};
    private static Consumer<End> clientEnd = ignored -> {};
    private static Consumer<Reject> clientReject = ignored -> {};
    private static Consumer<ActionSync> clientActionSync = ignored -> {};
    private static Consumer<HighlightRules> clientHighlightRules = ignored -> {};

    private ExecutionNetwork() {}

    public static void setClientHandlers(Consumer<Start> start, Consumer<End> end, Consumer<Reject> reject) {
        clientStart = start;
        clientEnd = end;
        clientReject = reject;
    }

    public static void setClientActionSyncHandler(Consumer<ActionSync> sync) { clientActionSync = sync; }
    public static void setClientHighlightRulesHandler(Consumer<HighlightRules> sync) { clientHighlightRules = sync; }

    public static void request(int targetEntityId) {
        PacketDistributor.sendToServer(new Request(targetEntityId));
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("execution-5");
        registrar.playToServer(Request.TYPE, Request.STREAM_CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                FiercerBattlesExecutionServer.tryStart(player, payload.targetEntityId());
            }
        });
        registrar.playToClient(Start.TYPE, Start.STREAM_CODEC, (payload, context) -> clientStart.accept(payload));
        registrar.playToClient(End.TYPE, End.STREAM_CODEC, (payload, context) -> clientEnd.accept(payload));
        registrar.playToClient(Reject.TYPE, Reject.STREAM_CODEC, (payload, context) -> clientReject.accept(payload));
        registrar.playToClient(ActionSync.TYPE, ActionSync.STREAM_CODEC, (payload, context) -> clientActionSync.accept(payload));
        registrar.playToClient(HighlightRules.TYPE, HighlightRules.STREAM_CODEC, (payload, context) -> clientHighlightRules.accept(payload));
    }

    /** Sent once on login so visual hints follow this server's bloodlines and allowlist. */
    public record HighlightRules(boolean enabled, double healthThreshold, double staggerHealthThreshold,
                                 List<String> humanoidEntities) implements CustomPacketPayload {
        public static final Type<HighlightRules> TYPE = ExecutionNetwork.type("execution_highlight_rules");
        public static final StreamCodec<RegistryFriendlyByteBuf, HighlightRules> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeBoolean(value.enabled());
                    buffer.writeDouble(value.healthThreshold());
                    buffer.writeDouble(value.staggerHealthThreshold());
                    buffer.writeCollection(value.humanoidEntities(), (buf, id) -> buf.writeUtf(id));
                },
                buffer -> new HighlightRules(buffer.readBoolean(), buffer.readDouble(), buffer.readDouble(),
                        buffer.readList(buf -> buf.readUtf())));

        public HighlightRules { humanoidEntities = List.copyOf(humanoidEntities); }

        public static HighlightRules fromConfig() {
            var config = FiercerBattlesConfig.getExecution();
            return new HighlightRules(config.enabled, config.healthThreshold(),
                    FiercerBattlesConfig.getParry().staggerExecutionHealthThreshold(),
                    config.humanoid_entities == null ? List.of()
                            : config.humanoid_entities.stream().filter(Objects::nonNull).toList());
        }

        @Override public Type<HighlightRules> type() { return TYPE; }
    }

    /** Compressed datapack definition and both pose clips, sent before the matching Start. */
    public record ActionSync(ResourceLocation actionId, String contentHash, byte[] compressedData)
            implements CustomPacketPayload {
        public static final Type<ActionSync> TYPE = ExecutionNetwork.type("execution_action_sync");
        public static final int MAX_BYTES = 512 * 1024;
        public static final StreamCodec<RegistryFriendlyByteBuf, ActionSync> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeResourceLocation(value.actionId());
                    buffer.writeUtf(value.contentHash(), 64);
                    buffer.writeByteArray(value.compressedData());
                },
                buffer -> {
                    ResourceLocation id = buffer.readResourceLocation();
                    String hash = buffer.readUtf(64);
                    byte[] data = buffer.readByteArray(MAX_BYTES);
                    return new ActionSync(id, hash, data);
                });
        public static ActionSync from(PairedExecutionAction action) throws IOException {
            return new ActionSync(action.id(), action.contentHash(), action.syncPayload());
        }
        @Override public Type<ActionSync> type() { return TYPE; }
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
            UUID sessionId, ResourceLocation actionId, String actionHash,
            int executorId, UUID executorUuid, int victimId, UUID victimUuid,
            ResourceLocation dimension, long startGameTime, int durationTicks,
            double executorX, double executorY, double executorZ, float executorYaw,
            double victimX, double victimY, double victimZ, float victimYaw,
            double executorFromX, double executorFromY, double executorFromZ, float executorFromYaw,
            double victimFromX, double victimFromY, double victimFromZ, float victimFromYaw
    ) implements CustomPacketPayload {
        public static final Type<Start> TYPE = ExecutionNetwork.type("execution_start");
        public static final StreamCodec<RegistryFriendlyByteBuf, Start> STREAM_CODEC = StreamCodec.of(
                (buffer, value) -> {
                    buffer.writeUUID(value.sessionId());
                    buffer.writeResourceLocation(value.actionId());
                    buffer.writeUtf(value.actionHash(), 64);
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
                    buffer.writeDouble(value.executorFromX());
                    buffer.writeDouble(value.executorFromY());
                    buffer.writeDouble(value.executorFromZ());
                    buffer.writeFloat(value.executorFromYaw());
                    buffer.writeDouble(value.victimFromX());
                    buffer.writeDouble(value.victimFromY());
                    buffer.writeDouble(value.victimFromZ());
                    buffer.writeFloat(value.victimFromYaw());
                },
                buffer -> new Start(buffer.readUUID(), buffer.readResourceLocation(), buffer.readUtf(64),
                        buffer.readVarInt(), buffer.readUUID(),
                        buffer.readVarInt(), buffer.readUUID(), buffer.readResourceLocation(),
                        buffer.readVarLong(), buffer.readVarInt(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(),
                        buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readFloat(),
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
