package com.fiercerbattles.boss.client;

import com.fiercerbattles.FiercerBattles;
import com.fiercerbattles.boss.IronSentinel;
import com.fiercerbattles.sound.ModSounds;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SelectMusicEvent;
import org.jetbrains.annotations.Nullable;

/**
 * The Iron Sentinel's boss music, in place of the game's own while it plays. An engaged sentinel near the player
 * fades in the fight (music/iron_sentinel_battle, 0:00-3:23 of the track, looping); it fades out once no engaged
 * sentinel has been near for a while. When the sentinel dies the fight cross-fades into the finale
 * (music/iron_sentinel_finale, 3:23 to the end), which plays out once. Both files are levelled well below the source
 * by tools/sentinel_audio.py and play at VOLUME under the Music slider.
 */
@EventBusSubscriber(modid = FiercerBattles.MOD_ID, value = Dist.CLIENT)
public final class IronSentinelMusic {
    /** An engaged sentinel this close starts the music; one within KEEP_RANGE keeps it going. */
    private static final double START_RANGE = 40.0;
    private static final double KEEP_RANGE = 64.0;
    /** The fight is over once no engaged sentinel has been in range for this long. */
    private static final int CALM_TICKS = 100;
    private static final int FADE_IN_TICKS = 40;
    private static final int FADE_OUT_TICKS = 60;
    /** On the sentinel's death the fight fades out over this while the finale fades in. */
    private static final int CROSSFADE_TICKS = 60;
    private static final float VOLUME = 0.8F;
    /** A track the sound engine would not take (no free stream) is not asked for again before this. */
    private static final int RETRY_TICKS = 40;

    @Nullable private static Track battle;
    @Nullable private static Track finale;
    /** Entity id of the sentinel the fight music follows, for its death. */
    private static int followed = -1;
    private static int calm;
    private static int retry;

    private IronSentinelMusic() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (minecraft.level == null || player == null || minecraft.options.getSoundSourceVolume(SoundSource.MUSIC) <= 0) {
            stopNow(minecraft.getSoundManager());
            return;
        }
        if (minecraft.isPaused()) return;
        if (retry > 0) retry--;
        SoundManager sounds = minecraft.getSoundManager();
        if (finale != null && !sounds.isActive(finale)) finale = null;
        if (battle != null && battle.fadingOut() && !sounds.isActive(battle)) battle = null;

        if (battle != null && !battle.fadingOut()
                && minecraft.level.getEntity(followed) instanceof IronSentinel sentinel && sentinel.isDeadOrDying()) {
            battle.fadeOut(CROSSFADE_TICKS);
            finale = play(sounds, ModSounds.IRON_SENTINEL_FINALE_MUSIC.get(), false, CROSSFADE_TICKS);
            followed = -1;
            return;
        }
        if (finale != null) return;

        boolean playing = battle != null && !battle.fadingOut();
        IronSentinel nearest = null;
        double best = Double.MAX_VALUE;
        double range = playing ? KEEP_RANGE : START_RANGE;
        for (IronSentinel sentinel : minecraft.level.getEntitiesOfClass(IronSentinel.class,
                player.getBoundingBox().inflate(range))) {
            double distance = sentinel.distanceToSqr(player);
            if (sentinel.isEngaged() && sentinel.isAlive() && distance < range * range && distance < best) {
                nearest = sentinel;
                best = distance;
            }
        }
        if (nearest != null) {
            calm = 0;
            followed = nearest.getId();
            if (battle != null && !battle.isStopped() && sounds.isActive(battle)) {
                // Back before it faded out: it fades back in where it is.
                if (battle.fadingOut()) battle.fadeIn(FADE_IN_TICKS);
            } else if (retry == 0) {
                // Not playing, or the sound engine dropped it (a resource reload).
                battle = play(sounds, ModSounds.IRON_SENTINEL_BATTLE_MUSIC.get(), true, FADE_IN_TICKS);
                retry = RETRY_TICKS;
            }
        } else if (playing && ++calm >= CALM_TICKS) {
            battle.fadeOut(FADE_OUT_TICKS);
            followed = -1;
        }
    }

    /** The game's own music stays silent while the sentinel's plays. */
    @SubscribeEvent
    public static void selectMusic(SelectMusicEvent event) {
        if (battle != null || finale != null) event.overrideMusic(null);
    }

    private static Track play(SoundManager sounds, SoundEvent event, boolean loop, int fadeInTicks) {
        Track track = new Track(event, loop, fadeInTicks);
        sounds.play(track);
        return track;
    }

    private static void stopNow(SoundManager sounds) {
        if (battle != null) sounds.stop(battle);
        if (finale != null) sounds.stop(finale);
        battle = null;
        finale = null;
        followed = -1;
        calm = 0;
        retry = 0;
    }

    /** A music track that fades in from silence and, when told, fades out and stops. */
    private static final class Track extends AbstractTickableSoundInstance {
        /** 0 silent to 1 full; moves by {@code step} every tick. */
        private float level;
        private float step;

        Track(SoundEvent event, boolean loop, int fadeInTicks) {
            super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
            this.looping = loop;
            this.delay = 0;
            this.relative = true;
            this.attenuation = Attenuation.NONE;
            this.volume = 0.0F;
            this.step = 1.0F / fadeInTicks;
        }

        void fadeIn(int ticks) {
            step = 1.0F / ticks;
        }

        void fadeOut(int ticks) {
            step = -Math.max(level, 0.01F) / ticks;
        }

        boolean fadingOut() { return step < 0; }

        @Override
        public void tick() {
            level = Mth.clamp(level + step, 0.0F, 1.0F);
            // Equal-power curve: the cross-fade into the finale holds its loudness.
            volume = VOLUME * Mth.sin(level * Mth.HALF_PI);
            if (step < 0 && level <= 0.0F) stop();
        }

        @Override
        public boolean canStartSilent() { return true; }
    }
}
