package com.fiercerbattles.execution;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Quaternionf;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Evaluated Blender transforms: all parents are baked, including detached limbs. */
public final class ExecutionAnimation {
    public record Transform(float x, float y, float z, Quaternionf rotation, float scale) {
        public Transform(float x, float y, float z, Quaternionf rotation) {
            this(x, y, z, rotation, 1.0F);
        }
    }

    private final float sampleStep;
    private final int duration;
    private final Map<String, Transform[]> parts;

    private ExecutionAnimation(float sampleStep, int duration, Map<String, Transform[]> parts) {
        this.sampleStep = sampleStep;
        this.duration = duration;
        this.parts = Map.copyOf(parts);
    }

    public static ExecutionAnimation load(String role) throws IOException {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("fiercerbattles", "execution/" + role + ".json");
        try (Reader reader = Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            float step = root.get("sample_step").getAsFloat();
            int duration = root.get("duration_ticks").getAsInt();
            if (!Float.isFinite(step) || step <= 0 || duration < 1 || duration > 1200) {
                throw new IOException("Invalid execution sampling interval or duration: " + id);
            }
            int count = Math.round(duration / step) + 1;
            Map<String, Transform[]> parts = new HashMap<>();
            for (Map.Entry<String, JsonElement> part : root.getAsJsonObject("parts").entrySet()) {
                JsonArray samples = part.getValue().getAsJsonArray();
                if (samples.size() != count) {
                    throw new IOException("Incomplete execution part: " + part.getKey());
                }
                Transform[] transforms = new Transform[count];
                for (int i = 0; i < count; i++) {
                    JsonArray sample = samples.get(i).getAsJsonArray();
                    if (sample.size() != 7) throw new IOException("Invalid execution transform");
                    float[] values = new float[7];
                    for (int j = 0; j < 7; j++) {
                        values[j] = sample.get(j).getAsFloat();
                        if (!Float.isFinite(values[j])) throw new IOException("Non-finite execution transform");
                    }
                    Quaternionf rotation = new Quaternionf(values[3], values[4], values[5], values[6]);
                    if (rotation.lengthSquared() < 0.5F) throw new IOException("Invalid execution rotation");
                    transforms[i] = new Transform(values[0], values[1], values[2], rotation.normalize());
                }
                parts.put(part.getKey(), transforms);
            }
            for (String name : new String[]{"head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg"}) {
                if (!parts.containsKey(name)) throw new IOException("Missing execution part: " + name);
            }
            return new ExecutionAnimation(step, duration, parts);
        } catch (RuntimeException exception) {
            throw new IOException("Unable to parse execution animation " + id, exception);
        }
    }

    public int duration() {
        return duration;
    }

    public Transform sample(String part, float tick) {
        Transform[] samples = parts.get(part);
        if (samples == null) return null;
        float frame = Math.max(0, Math.min(duration, tick)) / sampleStep;
        int first = Math.min(samples.length - 1, (int) frame);
        int second = Math.min(samples.length - 1, first + 1);
        float delta = frame - first;
        Transform a = samples[first];
        Transform b = samples[second];
        return new Transform(
                a.x + (b.x - a.x) * delta,
                a.y + (b.y - a.y) * delta,
                a.z + (b.z - a.z) * delta,
                new Quaternionf(a.rotation).slerp(b.rotation, delta)
        );
    }
}
