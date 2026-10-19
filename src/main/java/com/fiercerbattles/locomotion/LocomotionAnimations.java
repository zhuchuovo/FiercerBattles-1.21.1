package com.fiercerbattles.locomotion;

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

/** Loads the five in-place locomotion clips from datapacks and synchronizes them as one immutable bundle. */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID)
public final class LocomotionAnimations {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final int MAX_SYNC_BYTES = 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 4 * 1024 * 1024;
    private static final String[] NAMES = {"roll", "run", "prone_down", "prone_crawl", "prone_up"};
    private static final Gson GSON = new Gson();
    private static volatile Clips current;

    public record Loop(int start, int end) {}

    public record Clips(AnimationClip roll, AnimationClip run, Loop runLoop, AnimationClip proneDown,
                        AnimationClip proneCrawl, Loop crawlLoop, AnimationClip proneUp, String hash) {
        public AnimationClip clip(LocomotionNetwork.Kind kind) {
            return switch (kind) {
                case ROLL -> roll;
                case PRONE -> proneDown;
                case STAND -> proneUp;
            };
        }
    }

    private LocomotionAnimations() {}

    public static Clips clips() { return current; }

    @SubscribeEvent
    public static void addReloadListener(AddReloadListenerEvent event) {
        event.addListener(new SimpleJsonResourceReloadListener(GSON, "fiercerbattles_animations") {
            @Override
            protected void apply(Map<ResourceLocation, JsonElement> definitions, ResourceManager resources,
                                 ProfilerFiller profiler) {
                try {
                    JsonObject bundle = new JsonObject();
                    for (String name : NAMES) {
                        JsonElement clip = definitions.get(ResourceLocation.fromNamespaceAndPath(FiercerBattles.MOD_ID, "locomotion/" + name));
                        if (clip == null) throw new IOException("Missing locomotion clip " + name);
                        bundle.add(name, clip);
                    }
                    byte[] bytes = GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_EXPANDED_BYTES) throw new IOException("Locomotion bundle is too large");
                    Clips loaded = decodeBundle(bundle, hash(bytes));
                    // Validate compressed limits before advertising a usable server bundle.
                    encode(loaded);
                    current = loaded;
                    LOGGER.info("Loaded locomotion clips: roll={}, run={}, prone_down={}, prone_crawl={}, prone_up={} ticks",
                            loaded.roll.duration(), loaded.run.duration(), loaded.proneDown.duration(),
                            loaded.proneCrawl.duration(), loaded.proneUp.duration());
                } catch (IOException | RuntimeException exception) {
                    current = null;
                    LOGGER.error("Unable to load locomotion animations", exception);
                }
            }
        });
    }

    public static byte[] encode(Clips clips) throws IOException {
        JsonObject bundle = new JsonObject();
        bundle.add("roll", clips.roll.sourceJson());
        bundle.add("run", clips.run.sourceJson());
        bundle.add("prone_down", clips.proneDown.sourceJson());
        bundle.add("prone_crawl", clips.proneCrawl.sourceJson());
        bundle.add("prone_up", clips.proneUp.sourceJson());
        byte[] bytes = GSON.toJson(bundle).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_EXPANDED_BYTES) throw new IOException("Locomotion bundle is too large");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(bytes); }
        byte[] compressed = output.toByteArray();
        if (compressed.length > MAX_SYNC_BYTES) throw new IOException("Compressed locomotion bundle is too large");
        return compressed;
    }

    public static Clips decode(byte[] compressed, String expectedHash) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_SYNC_BYTES) throw new IOException("Invalid locomotion sync size");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            for (int count; (count = gzip.read(buffer)) != -1;) {
                if (output.size() + count > MAX_EXPANDED_BYTES) throw new IOException("Expanded locomotion bundle is too large");
                output.write(buffer, 0, count);
            }
        }
        byte[] bytes = output.toByteArray();
        if (!hash(bytes).equals(expectedHash)) throw new IOException("Locomotion sync hash differs");
        try {
            return decodeBundle(JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject(), expectedHash);
        } catch (RuntimeException exception) { throw new IOException("Invalid locomotion bundle", exception); }
    }

    private static Clips decodeBundle(JsonObject bundle, String hash) throws IOException {
        // Roll, run and stand-up hand back to the ordinary pose at the entity root, so they must end there.
        AnimationClip roll = clip("roll", bundle.getAsJsonObject("roll"), true);
        AnimationClip run = clip("run", bundle.getAsJsonObject("run"), true);
        AnimationClip proneDown = clip("prone_down", bundle.getAsJsonObject("prone_down"), false);
        AnimationClip proneCrawl = clip("prone_crawl", bundle.getAsJsonObject("prone_crawl"), false);
        AnimationClip proneUp = clip("prone_up", bundle.getAsJsonObject("prone_up"), true);
        return new Clips(roll, run, loop(run, bundle.getAsJsonObject("run")), proneDown, proneCrawl,
                loop(proneCrawl, bundle.getAsJsonObject("prone_crawl")), proneUp, hash);
    }

    private static AnimationClip clip(String name, JsonObject source, boolean returnsToRoot) throws IOException {
        if (source == null || !source.has("rig") || !HumanoidRig.ID.equals(source.get("rig").getAsString())) {
            throw new IOException("Unsupported locomotion rig: " + name);
        }
        AnimationClip clip = AnimationClip.decode(FiercerBattles.MOD_ID + ":locomotion/" + name, source);
        if (!HumanoidRig.supports(clip)) throw new IOException("Incomplete humanoid locomotion channels: " + name);
        JsonObject end = source.getAsJsonObject("end_root");
        if (returnsToRoot && end != null) {
            for (String axis : new String[]{"right", "forward", "yaw"}) {
                float value = end.get(axis).getAsFloat();
                if (!Float.isFinite(value) || Math.abs(value) > 0.001F) {
                    throw new IOException("Locomotion clip " + name + " must end at its entity root");
                }
            }
        }
        return clip;
    }

    private static Loop loop(AnimationClip clip, JsonObject source) throws IOException {
        if (!source.has("loop_start_tick") || !source.has("loop_end_tick")) {
            throw new IOException("Looping locomotion clip has no loop range: " + clip.id());
        }
        int start = source.get("loop_start_tick").getAsInt(), end = source.get("loop_end_tick").getAsInt();
        if (start < 0 || end <= start || end > clip.duration()) throw new IOException("Invalid loop range: " + clip.id());
        return new Loop(start, end);
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
