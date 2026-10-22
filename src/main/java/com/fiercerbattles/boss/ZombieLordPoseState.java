package com.fiercerbattles.boss;

import com.fiercerbattles.animation.core.AnimationClip;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-entity render memory of the client poser: which synced action it last drew and the pose it
 * blends from after the server switches actions. Plain data so the entity class needs no client code.
 */
public final class ZombieLordPoseState {
    public int action = -1;
    public long actionStart;
    public double blendStart;
    public int blendTicks;
    public Map<String, AnimationClip.Transform> blendFrom = Map.of();
    public final Map<String, AnimationClip.Transform> lastPose = new HashMap<>();
    /** Feet positions sampled each client tick during a charge dash, for the ground scar. */
    public final List<TrailPoint> trail = new ArrayList<>();

    public record TrailPoint(double x, double y, double z, long time) {}
}
