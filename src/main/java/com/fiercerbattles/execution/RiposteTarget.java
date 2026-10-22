package com.fiercerbattles.execution;

/** Implemented by mobs that open a riposte execution whenever a parry stuns them, whatever their health. */
public interface RiposteTarget {
    boolean riposteAtAnyHealth();
}
