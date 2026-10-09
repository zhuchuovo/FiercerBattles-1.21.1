package com.fiercerbattles.parry;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.rig.HumanoidRig;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Loads and synchronizes the two independent humanoid clips as one immutable bundle. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID)
public final class ParryAnimations {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MAX_SYNC_BYTES = 512 * 1024;
    private static final int MAX_EXPANDED_BYTES = 2 * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private static volatile Clips current;

    public record Clips(AnimationClip parry, AnimationClip stagger, String hash) {
        public AnimationClip clip(ParryNetwork.Kind kind) {
            return kind == ParryNetwork.Kind.PARRY ? parry : stagger;
        }
    }

    private ParryAnimations() {}

    public static Clips clips() { return current; }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(GSON, "fiercerbattles_animations") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> definitions, ResourceManager resources,
                                 ProfilerFiller profiler) {
                try {
                    JsonElement parry = definitions.get(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "parry/parry"));
                    JsonElement stagger = definitions.get(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "parry/stagger"));
                    if (parry == null || stagger == null) throw new IOException("Missing parry or stagger clip");
                    JsonObject bundle = new JsonObject();
                    bundle.add("parry", parry);
                    bundle.add("stagger", stagger);
                    String json = GSON.toJson(bundle);
                    byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_EXPANDED_BYTES) throw new IOException("Parry bundle is too large");
                    Clips loaded = decodeBundle(bundle, hash(bytes));
                    // Validate compressed limits before advertising a usable server bundle.
                    encode(loaded);
                    current = loaded;
                    LOGGER.info("Loaded parry clips: parry={} ticks, stagger={} ticks", loaded.parry.duration(), loaded.stagger.duration());
                } catch (IOException | RuntimeException exception) {
                    current = null;
                    LOGGER.error("Unable to load parry animations", exception);
                }
            }
        });
    }

    public static byte[] encode(Clips clips) throws IOException {
        JsonObject bundle = new JsonObject();
        bundle.add("parry", clips.parry.sourceJson());
        bundle.add("stagger", clips.stagger.sourceJson());
        byte[] bytes = GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EXPANDED_BYTES) throw new IOException("Parry bundle is too large");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        byte[] compressed = output.toByteArray();
        if (compressed.length > MAX_SYNC_BYTES) throw new IOException("Compressed parry bundle is too large");
        return compressed;
    }

    public static Clips decode(byte[] compressed, String expectedHash) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_SYNC_BYTES) throw new IOException("Invalid parry sync size");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            for (int count; (count = gzip.read(buffer)) != -1;) {
                if (output.size() + count > MAX_EXPANDED_BYTES) throw new IOException("Expanded parry bundle is too large");
                output.write(buffer, 0, count);
            }
        }
        byte[] bytes = output.toByteArray();
        if (!hash(bytes).equals(expectedHash)) throw new IOException("Parry sync hash differs");
        try {
            return decodeBundle(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(), expectedHash);
        } catch (RuntimeException exception) { throw new IOException("Invalid parry bundle", exception); }
    }

    private static Clips decodeBundle(JsonObject bundle, String hash) throws IOException {
        AnimationClip parry = clip("parry", bundle.getAsJsonObject("parry"));
        AnimationClip stagger = clip("stagger", bundle.getAsJsonObject("stagger"));
        if (parry.duration() < ParryTimeline.WINDOW_END) throw new IOException("Parry clip does not cover its timing window");
        return new Clips(parry, stagger, hash);
    }

    private static AnimationClip clip(String name, JsonObject source) throws IOException {
        if (source == null || !source.has("rig") || !HumanoidRig.ID.equals(source.get("rig").getAsString())) {
            throw new IOException("Unsupported parry rig");
        }
        AnimationClip clip = AnimationClip.decode(FiercerBattles.MOD_ID + ":parry/" + name, source);
        if (!HumanoidRig.supports(clip)) throw new IOException("Incomplete humanoid parry channels");
        // These gestures animate in the current entity root. A moving authored root
        // needs a separate placement policy, rather than silently losing its motion.
        JsonObject end = source.getAsJsonObject("end_root");
        if (end != null) {
            for (String axis : new String[]{"right", "forward", "yaw"}) {
                float value = end.get(axis).getAsFloat();
                if (!Float.isFinite(value) || Math.abs(value) > 0.001F) throw new IOException("Parry root must return to its entity anchor");
            }
        }
        return clip;
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
