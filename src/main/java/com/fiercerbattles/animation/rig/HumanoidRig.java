package com.fiercerbattles.animation.rig;

import com.fiercerbattles.animation.core.AnimationClip;

import java.util.Set;

/** Server-safe descriptor for the first built-in rig; it contains no client model classes. */
public final class HumanoidRig {
    public static final String ID = "fiercerbattles:humanoid_v1";
    public static final Set<String> REQUIRED_CHANNELS = Set.of("head", "body", "rightArm", "leftArm", "rightLeg", "leftLeg");
    private HumanoidRig() {}

    public static boolean supports(AnimationClip clip) { return clip.channels().containsAll(REQUIRED_CHANNELS); }
}
