package com.fiercerbattles.mobcombat.api;

/** A mob whose swing pace varies from swing to swing, on top of its weapon's attack interval. */
public interface MobAttackRhythm {
    /** Called as a Better Combat swing starts; returns extra cooldown ticks before the next swing may start. */
    int bmc$extraCooldownAfterSwing();
}
