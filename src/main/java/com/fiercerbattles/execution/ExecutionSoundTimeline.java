package com.fiercerbattles.execution;

import java.util.List;

/** Additional authored sound cues; the approach is outside the source animation clock. */
public final class ExecutionSoundTimeline {
    public enum Sound { BRUTAL_HIT, PUSH_OPEN }
    public record Cue(int frame, Sound sound) {}

    private static final List<Cue> SWORD = List.of(
            new Cue(12, Sound.BRUTAL_HIT), new Cue(20, Sound.BRUTAL_HIT), new Cue(46, Sound.PUSH_OPEN));
    private static final List<Cue> AXE = List.of(new Cue(12, Sound.BRUTAL_HIT));

    private ExecutionSoundTimeline() {}

    public static List<Cue> cues(boolean axe, int durationTicks) {
        return (axe ? AXE : SWORD).stream().filter(cue -> cue.frame() <= durationTicks).toList();
    }
}
