package com.fiercerbattles.parry.client;

import com.fiercerbattles.animation.rig.HumanoidRig;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Actual rendered poses, including external animation and independent part scales. */
public final class ParryPoseHistory {
    private static final int CAPACITY = 12;
    private static final int REWIND_FRAMES = 6;
    private static final Map<UUID, Deque<Frame>> HISTORY = new HashMap<>();
    private record Frame(long tick, Map<String, Matrix4f> parts, Map<String, Bend> bends) {}

    public record Bend(float axis, float angle) {
        public static final Bend ZERO = new Bend(0, 0);

        public Bend fade(float progress) {
            return progress >= 1 ? ZERO : new Bend(axis, angle * Math.max(0, 1 - progress));
        }

        public static Bend interpolate(Bend a, Bend b, float amount) {
            if (amount <= 0) return a;
            if (amount >= 1) return b;
            float difference = (float) Math.atan2(Math.sin(b.axis - a.axis), Math.cos(b.axis - a.axis));
            return new Bend(a.axis + difference * amount, a.angle + (b.angle - a.angle) * amount);
        }
    }

    private ParryPoseHistory() {}

    public static void record(UUID entity, long tick, Map<String, Matrix4f> parts) {
        record(entity, tick, parts, Map.of());
    }

    public static void record(UUID entity, long tick, Map<String, Matrix4f> parts, Map<String, Bend> bends) {
        if (!parts.keySet().containsAll(HumanoidRig.REQUIRED_CHANNELS)) return;
        Map<String, Matrix4f> snapshot = new HashMap<>();
        parts.forEach((name, pose) -> snapshot.put(name, new Matrix4f(pose)));
        Deque<Frame> frames = HISTORY.computeIfAbsent(entity, ignored -> new ArrayDeque<>());
        if (!frames.isEmpty() && frames.peekLast().tick == tick) frames.removeLast();
        frames.addLast(new Frame(tick, Map.copyOf(snapshot), Map.copyOf(bends)));
        while (frames.size() > CAPACITY) frames.removeFirst();
    }

    public static Rewind freeze(UUID entity, long hitStart) {
        Deque<Frame> history = HISTORY.get(entity);
        if (history == null) return Rewind.EMPTY;
        List<Frame> frames = new ArrayList<>();
        for (Frame frame : history) if (frame.tick <= hitStart) frames.add(frame);
        if (frames.size() < REWIND_FRAMES || hitStart - frames.getLast().tick > 1) return Rewind.EMPTY;
        frames = new ArrayList<>(frames.subList(frames.size() - REWIND_FRAMES, frames.size()));
        for (int i = 1; i < frames.size(); i++) {
            if (frames.get(i).tick != frames.get(i - 1).tick + 1) return Rewind.EMPTY;
        }
        return new Rewind(List.copyOf(frames));
    }

    public static void prune(long now) {
        HISTORY.values().removeIf(frames -> frames.isEmpty() || now - frames.peekLast().tick > 200);
    }

    public static void clear() { HISTORY.clear(); }

    public static final class Rewind {
        private static final Rewind EMPTY = new Rewind(List.of());
        private final List<Frame> frames;
        private Rewind(List<Frame> frames) { this.frames = frames; }
        public boolean available() { return !frames.isEmpty(); }

        public Matrix4f sample(String channel, float elapsed) {
            if (!available()) return null;
            float index = Math.max(0, Math.min(frames.size() - 1, frames.size() - 1 - elapsed));
            int first = (int) index;
            int second = Math.min(frames.size() - 1, first + 1);
            Matrix4f a = frames.get(first).parts.get(channel), b = frames.get(second).parts.get(channel);
            return a == null || b == null ? null : interpolate(a, b, index - first);
        }

        public Bend bend(String channel, float elapsed) {
            if (!available()) return Bend.ZERO;
            float index = Math.max(0, Math.min(frames.size() - 1, frames.size() - 1 - elapsed));
            int first = (int) index, second = Math.min(frames.size() - 1, first + 1);
            Bend a = frames.get(first).bends.getOrDefault(channel, Bend.ZERO);
            Bend b = frames.get(second).bends.getOrDefault(channel, Bend.ZERO);
            return Bend.interpolate(a, b, index - first);
        }
    }

    /** Keep independent scale axes; retain a full matrix fallback for sheared poses. */
    public static Matrix4f interpolate(Matrix4f a, Matrix4f b, float amount) {
        if (amount <= 0) return new Matrix4f(a);
        if (amount >= 1) return new Matrix4f(b);
        if (!orthogonal(a) || !orthogonal(b)) return new Matrix4f(a).lerp(b, amount);
        Vector3f scaleA = signedScale(a), scaleB = signedScale(b);
        if (Math.signum(scaleA.x) != Math.signum(scaleB.x)) return new Matrix4f(a).lerp(b, amount);
        Matrix4f rotationA = new Matrix4f(a).scale(1 / scaleA.x, 1 / scaleA.y, 1 / scaleA.z);
        Matrix4f rotationB = new Matrix4f(b).scale(1 / scaleB.x, 1 / scaleB.y, 1 / scaleB.z);
        Quaternionf rotation = rotationA.getNormalizedRotation(new Quaternionf())
                .slerp(rotationB.getNormalizedRotation(new Quaternionf()), amount);
        return new Matrix4f().translation(a.getTranslation(new Vector3f())
                .lerp(b.getTranslation(new Vector3f()), amount)).rotate(rotation).scale(scaleA.lerp(scaleB, amount));
    }

    private static Vector3f signedScale(Matrix4f matrix) {
        Vector3f scale = matrix.getScale(new Vector3f());
        if (matrix.determinant3x3() < 0) scale.x = -scale.x;
        return scale;
    }

    private static boolean orthogonal(Matrix4f matrix) {
        Vector3f x = new Vector3f(matrix.m00(), matrix.m01(), matrix.m02());
        Vector3f y = new Vector3f(matrix.m10(), matrix.m11(), matrix.m12());
        Vector3f z = new Vector3f(matrix.m20(), matrix.m21(), matrix.m22());
        if (x.lengthSquared() < 1.0E-8F || y.lengthSquared() < 1.0E-8F || z.lengthSquared() < 1.0E-8F) return false;
        x.normalize(); y.normalize(); z.normalize();
        return Math.abs(x.dot(y)) < 1.0E-4F && Math.abs(x.dot(z)) < 1.0E-4F && Math.abs(y.dot(z)) < 1.0E-4F;
    }
}
