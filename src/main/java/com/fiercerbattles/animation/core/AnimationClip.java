package com.fiercerbattles.animation.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.joml.Quaternionf;

import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;

/** Immutable, rig-independent transform samples. It knows no entity, renderer, or execution session. */
public final class AnimationClip {
    public record Transform(float x, float y, float z, Quaternionf rotation, float scale) {
        public Transform(float x, float y, float z, Quaternionf rotation) { this(x, y, z, rotation, 1.0F); }
    }

    private static final int MAX_DURATION_TICKS = 1200;
    private static final int MAX_SAMPLES_PER_CHANNEL = 6001;
    private final String id;
    private final float sampleStep;
    private final int duration;
    private final Map<String, Transform[]> channels;
    private final JsonObject source;

    private AnimationClip(String id, float sampleStep, int duration, Map<String, Transform[]> channels, JsonObject source) {
        this.id = id;
        this.sampleStep = sampleStep;
        this.duration = duration;
        this.channels = Map.copyOf(channels);
        this.source = source.deepCopy();
    }

    public static AnimationClip read(String id, Reader reader) throws IOException {
        try {
            return decode(id, JsonParser.parseReader(reader).getAsJsonObject());
        } catch (RuntimeException exception) {
            throw new IOException("Unable to parse animation clip " + id, exception);
        }
    }

    public static AnimationClip decode(String id, JsonObject root) throws IOException {
        try {
            if (!root.has("format") || root.get("format").getAsInt() != 1) {
                throw new IOException("Unsupported animation clip format: " + id);
            }
            float step = root.get("sample_step").getAsFloat();
            int duration = root.get("duration_ticks").getAsInt();
            if (!Float.isFinite(step) || step <= 0 || duration < 1 || duration > MAX_DURATION_TICKS) {
                throw new IOException("Invalid animation sampling interval or duration: " + id);
            }
            int count = Math.round(duration / step) + 1;
            if (count < 2 || count > MAX_SAMPLES_PER_CHANNEL || Math.abs((count - 1) * step - duration) > 0.01F) {
                throw new IOException("Animation sample count is outside supported limits: " + id);
            }
            JsonObject sourceChannels = root.getAsJsonObject("parts");
            if (sourceChannels == null || sourceChannels.size() == 0 || sourceChannels.size() > 64) {
                throw new IOException("Animation has no channels or too many channels: " + id);
            }
            Map<String, Transform[]> channels = new HashMap<>();
            for (Map.Entry<String, JsonElement> channel : sourceChannels.entrySet()) {
                JsonArray samples = channel.getValue().getAsJsonArray();
                if (samples.size() != count) throw new IOException("Incomplete animation channel: " + channel.getKey());
                Transform[] transforms = new Transform[count];
                for (int i = 0; i < count; i++) {
                    JsonArray sample = samples.get(i).getAsJsonArray();
                    if (sample.size() != 7) throw new IOException("Invalid animation transform");
                    float[] value = new float[7];
                    for (int j = 0; j < value.length; j++) {
                        value[j] = sample.get(j).getAsFloat();
                        if (!Float.isFinite(value[j])) throw new IOException("Non-finite animation transform");
                    }
                    Quaternionf rotation = new Quaternionf(value[3], value[4], value[5], value[6]);
                    if (rotation.lengthSquared() < 0.5F) throw new IOException("Invalid animation quaternion");
                    transforms[i] = new Transform(value[0], value[1], value[2], rotation.normalize());
                }
                channels.put(channel.getKey(), transforms);
            }
            return new AnimationClip(id, step, duration, channels, root);
        } catch (IOException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new IOException("Invalid animation clip " + id, exception);
        }
    }

    public String id() { return id; }
    public float sampleStep() { return sampleStep; }
    public int duration() { return duration; }
    public java.util.Set<String> channels() { return channels.keySet(); }
    public JsonObject sourceJson() { return source.deepCopy(); }

    public Transform sample(String channel, float tick) {
        Transform[] samples = channels.get(channel);
        if (samples == null) return null;
        float frame = Math.max(0, Math.min(duration, tick)) / sampleStep;
        int first = Math.min(samples.length - 1, (int) frame);
        int second = Math.min(samples.length - 1, first + 1);
        float delta = frame - first;
        Transform a = samples[first], b = samples[second];
        return new Transform(a.x + (b.x - a.x) * delta, a.y + (b.y - a.y) * delta,
                a.z + (b.z - a.z) * delta, new Quaternionf(a.rotation).slerp(b.rotation, delta));
    }
}
