package com.fiercerbattles.execution;

import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * A mob with its own execution instead of the generic paired kill (the Iron Sentinel boss). It names the paired
 * action and decides when it can be executed, whatever the humanoid allowlist, health line and body size say; the
 * action has no victim clip because this mob plays its half with its own model, from the session's start time;
 * and it takes its own finishing blow instead of being killed.
 */
public interface ExecutionTarget {
    /** Paired action to play on this mob now, or null when it cannot be executed at the moment. */
    @Nullable String executionAction(ServerPlayer executor);

    /** The session has started; the action's frame 0 plays at {@code startGameTime}. */
    void executionStarted(ServerPlayer executor, long startGameTime);

    /** The action played to its end: deal the finishing damage. Runs inside the execution damage scope. */
    void executionFinished(ServerPlayer executor);
}
