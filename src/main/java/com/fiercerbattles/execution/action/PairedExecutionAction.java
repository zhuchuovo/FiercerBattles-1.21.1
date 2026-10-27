package com.fiercerbattles.execution.action;

import com.fiercerbattles.animation.core.AnimationClip;
import com.fiercerbattles.animation.rig.HumanoidRig;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.phys.Vec3;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Server-validated action data and its two client-visible pose clips. An action without a victim clip
 * ({@code victim_clip} left out) is played on a mob that animates its own half ({@code ExecutionTarget}).
 */
public record PairedExecutionAction(ResourceLocation id, ResourceLocation rig, int durationTicks,
                                    Geometry geometry, AnimationClip executorClip, AnimationClip victimClip,
                                    String contentHash) {
    private static final int MAX_SYNC_BYTES = 512 * 1024;
    private static final int MAX_EXPANDED_BYTES = 2 * 1024 * 1024;
    private static final double MAX_GEOMETRY_OFFSET = 16.0;
    private static final GsonAdapter JSON = new GsonAdapter();

    public record Geometry(double victimRight, double victimForward, float victimYawDelta,
                           double executorEndRight, double executorEndForward, float executorEndYawDelta) {
        public Vec3 victimOffset(float yaw) { return offset(victimRight, victimForward, yaw); }
        public Vec3 executorEndPosition(Vec3 anchor, float yaw) {
            return anchor.add(offset(executorEndRight, executorEndForward, yaw));
        }
        public float executorEndYaw(float yaw) { return net.minecraft.util.Mth.wrapDegrees(yaw + executorEndYawDelta); }
        private static Vec3 offset(double right, double forward, float yaw) {
            double angle = Math.toRadians(yaw), cosine = Math.cos(angle), sine = Math.sin(angle);
            return new Vec3(cosine * right - sine * forward, 0, sine * right + cosine * forward);
        }
    }

    public static PairedExecutionAction load(ResourceLocation id, JsonObject definition, ResourceManager resources)
            throws IOException {
        ResourceLocation rig = ResourceLocation.parse(GsonHelper.getAsString(definition, "rig"));
        int duration = GsonHelper.getAsInt(definition, "duration_ticks");
        if (!rig.toString().equals(HumanoidRig.ID) || duration < 1 || duration > 1200) {
            throw new IOException("Unsupported rig or duration for action " + id);
        }
        JsonObject geometryJson = GsonHelper.getAsJsonObject(definition, "geometry");
        Geometry geometry = new Geometry(number(geometryJson, "victim_right"), number(geometryJson, "victim_forward"),
                finite(geometryJson, "victim_yaw_delta"), number(geometryJson, "executor_end_right"),
                number(geometryJson, "executor_end_forward"), finite(geometryJson, "executor_end_yaw_delta"));
        validateGeometry(geometry);
        ResourceLocation executorId = ResourceLocation.parse(GsonHelper.getAsString(definition, "executor_clip"));
        AnimationClip executorClip = loadClip(executorId, resources);
        AnimationClip victimClip = definition.has("victim_clip")
                ? loadClip(ResourceLocation.parse(GsonHelper.getAsString(definition, "victim_clip")), resources) : null;
        validateClip(executorClip, duration, id);
        if (victimClip != null) validateClip(victimClip, duration, id);
        PairedExecutionAction action = new PairedExecutionAction(id, rig, duration, geometry, executorClip, victimClip, "");
        String canonical = JSON.write(action.envelope());
        if (canonical.getBytes(StandardCharsets.UTF_8).length > MAX_EXPANDED_BYTES) {
            throw new IOException("Expanded animation action exceeds limit: " + id);
        }
        return new PairedExecutionAction(id, rig, duration, geometry, executorClip, victimClip,
                hash(canonical));
    }

    public static PairedExecutionAction decodeClient(String expectedId, byte[] compressed, String expectedHash) throws IOException {
        if (compressed.length == 0 || compressed.length > MAX_SYNC_BYTES) throw new IOException("Animation sync exceeds limit");
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed));
             var output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int read; (read = gzip.read(buffer)) != -1;) {
                if (output.size() + read > MAX_EXPANDED_BYTES) throw new IOException("Expanded animation sync exceeds limit");
                output.write(buffer, 0, read);
            }
            byte[] json = output.toByteArray();
            if (!hash(new String(json, StandardCharsets.UTF_8)).equals(expectedHash)) throw new IOException("Animation sync hash mismatch");
            JsonObject envelope = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
            ResourceLocation id = ResourceLocation.parse(GsonHelper.getAsString(envelope, "id"));
            if (!id.equals(ResourceLocation.parse(expectedId))) throw new IOException("Animation sync action id mismatch");
            ResourceLocation rig = ResourceLocation.parse(GsonHelper.getAsString(envelope, "rig"));
            int duration = GsonHelper.getAsInt(envelope, "duration_ticks");
            Geometry geometry = readGeometry(GsonHelper.getAsJsonObject(envelope, "geometry"));
            AnimationClip executor = AnimationClip.decode(id + "/executor", GsonHelper.getAsJsonObject(envelope, "executor_animation"));
            AnimationClip victim = envelope.has("victim_animation")
                    ? AnimationClip.decode(id + "/victim", GsonHelper.getAsJsonObject(envelope, "victim_animation")) : null;
            validateClip(executor, duration, id);
            if (victim != null) validateClip(victim, duration, id);
            if (!rig.toString().equals(HumanoidRig.ID)) throw new IOException("Unsupported animation rig: " + rig);
            if (!hash(JSON.write(envelope)).equals(expectedHash)) throw new IOException("Animation sync is not canonical");
            return new PairedExecutionAction(id, rig, duration, geometry, executor, victim, expectedHash);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid synchronized action", exception);
        }
    }

    public byte[] syncPayload() throws IOException {
        byte[] result = gzip(JSON.write(envelope()));
        if (result.length > MAX_SYNC_BYTES) throw new IOException("Compressed animation action exceeds limit: " + id);
        return result;
    }

    private JsonObject envelope() {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("id", id.toString()); envelope.addProperty("rig", rig.toString());
        envelope.addProperty("duration_ticks", durationTicks);
        JsonObject geometryJson = new JsonObject();
        geometryJson.addProperty("victim_right", geometry.victimRight());
        geometryJson.addProperty("victim_forward", geometry.victimForward());
        geometryJson.addProperty("victim_yaw_delta", geometry.victimYawDelta());
        geometryJson.addProperty("executor_end_right", geometry.executorEndRight());
        geometryJson.addProperty("executor_end_forward", geometry.executorEndForward());
        geometryJson.addProperty("executor_end_yaw_delta", geometry.executorEndYawDelta());
        envelope.add("geometry", geometryJson);
        envelope.add("executor_animation", executorClip.sourceJson());
        if (victimClip != null) envelope.add("victim_animation", victimClip.sourceJson());
        return envelope;
    }

    private static AnimationClip loadClip(ResourceLocation id, ResourceManager resources) throws IOException {
        ResourceLocation path = id.withPath("fiercerbattles_animations/" + id.getPath() + ".json");
        var resource = resources.getResource(path).orElseThrow(() -> new IOException("Missing animation resource " + path));
        try (var reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
            return AnimationClip.read(id.toString(), new StringReader(readLimited(reader, MAX_EXPANDED_BYTES)));
        }
    }

    private static String readLimited(java.io.Reader reader, int limit) throws IOException {
        StringBuilder contents = new StringBuilder();
        char[] buffer = new char[8192];
        for (int count; (count = reader.read(buffer)) != -1;) {
            if (contents.length() + count > limit) throw new IOException("Animation clip source exceeds size limit");
            contents.append(buffer, 0, count);
        }
        return contents.toString();
    }

    private static void validateClip(AnimationClip clip, int duration, ResourceLocation action) throws IOException {
        if (clip.duration() != duration || !HumanoidRig.supports(clip)) {
            throw new IOException("Clip duration or humanoid channels do not match action " + action + ": " + clip.id());
        }
    }

    private static double number(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber())
            throw new IOException("Missing numeric animation geometry: " + key);
        double value = object.get(key).getAsDouble();
        if (!Double.isFinite(value)) throw new IOException("Non-finite animation geometry: " + key);
        return value;
    }
    private static float finite(JsonObject object, String key) throws IOException {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isNumber())
            throw new IOException("Missing numeric animation geometry: " + key);
        float value = object.get(key).getAsFloat();
        if (!Float.isFinite(value)) throw new IOException("Non-finite animation geometry: " + key);
        return value;
    }
    private static Geometry readGeometry(JsonObject json) throws IOException {
        Geometry geometry = new Geometry(number(json, "victim_right"), number(json, "victim_forward"), finite(json, "victim_yaw_delta"),
                number(json, "executor_end_right"), number(json, "executor_end_forward"), finite(json, "executor_end_yaw_delta"));
        validateGeometry(geometry);
        return geometry;
    }
    private static void validateGeometry(Geometry geometry) throws IOException {
        if (Math.abs(geometry.victimRight()) > MAX_GEOMETRY_OFFSET
                || Math.abs(geometry.victimForward()) > MAX_GEOMETRY_OFFSET
                || Math.abs(geometry.executorEndRight()) > MAX_GEOMETRY_OFFSET
                || Math.abs(geometry.executorEndForward()) > MAX_GEOMETRY_OFFSET) {
            throw new IOException("Animation geometry offsets exceed supported limits");
        }
    }
    private static String hash(String value) throws IOException {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IOException(exception); }
    }
    private static byte[] gzip(String value) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) { gzip.write(value.getBytes(StandardCharsets.UTF_8)); }
        return output.toByteArray();
    }

    /** Small wrapper keeps the shared animation core independent of Minecraft/GsonHelper. */
    private static final class GsonAdapter {
        String write(JsonObject json) { return new com.google.gson.GsonBuilder().disableHtmlEscaping().create().toJson(json); }
    }
}
