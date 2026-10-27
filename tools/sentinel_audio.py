"""Converts the Iron Sentinel's provided audio into the mod's OGG files (needs ffmpeg with libvorbis on PATH).

    python tools/sentinel_audio.py --music "The Iron Golem Awakens.mp3" --saw "<chainsaw impact>.mp3"

music/iron_sentinel_battle  the fight, 0:00-3:23, looped: its last LOOP_FADE seconds cross-fade into the opening
                            seconds, so the stream starts LOOP_FADE in and each pass runs on into the next
music/iron_sentinel_finale  3:23 to the end, cross-faded in by the client when the boss dies
boss/iron_sentinel_saw_hit  a saw blow landing (mono, so it is heard from where it lands)

The music is tamed before it is cut: a gentle compressor pulls the climaxes towards the quiet parts, then the whole
track is set to MUSIC_LUFS, quieter than the source's -13.5 and about the level of the game's own music.
"""
import argparse
import os
import re
import subprocess
import tempfile

import numpy as np

ROOT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "fiercerbattles", "sounds")
RATE = 48000
FINALE_FROM = 3 * 60 + 23
LOOP_FADE = 6.0
MUSIC_LUFS = -21.0
COMPRESSOR = "acompressor=threshold=-26dB:ratio=2.5:knee=6:attack=80:release=1200"
SAW_FADE = 0.3


def decode(path, channels, filters=None):
    args = ["ffmpeg", "-v", "error", "-i", path]
    if filters:
        args += ["-af", filters]
    args += ["-ac", str(channels), "-ar", str(RATE), "-f", "f32le", "-"]
    data = np.frombuffer(subprocess.run(args, check=True, capture_output=True).stdout, np.float32)
    return data.reshape(-1, channels).copy()


def loudness(samples):
    """Integrated loudness (LUFS) of the samples, by ffmpeg's EBU R128 meter."""
    with tempfile.NamedTemporaryFile(suffix=".raw", delete=False) as raw:
        raw.write(samples.astype(np.float32).tobytes())
    try:
        out = subprocess.run(["ffmpeg", "-hide_banner", "-f", "f32le", "-ar", str(RATE), "-ac", str(samples.shape[1]),
                              "-i", raw.name, "-af", "ebur128=framelog=quiet", "-f", "null", "-"],
                             capture_output=True, text=True).stderr
    finally:
        os.unlink(raw.name)
    return float(re.findall(r"I:\s+(-?[\d.]+) LUFS", out)[-1])


def encode(samples, name, quality):
    path = os.path.join(ROOT, name + ".ogg")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "f32le", "-ar", str(RATE), "-ac", str(samples.shape[1]),
                    "-i", "-", "-c:a", "libvorbis", "-q:a", str(quality), path],
                   input=samples.astype(np.float32).tobytes(), check=True)
    print(f"{os.path.normpath(path)}: {len(samples) / RATE:.1f} s, peak {20 * np.log10(np.abs(samples).max()):.1f} dBFS")


def ramp(length, rising):
    """Equal-power fade, so a cross-fade keeps its loudness."""
    t = np.linspace(0.0, 1.0, length, dtype=np.float32)[:, None]
    return np.sin(t * np.pi / 2) if rising else np.cos(t * np.pi / 2)


def music(path):
    track = decode(path, 2, COMPRESSOR)
    track *= 10 ** ((MUSIC_LUFS - loudness(track)) / 20)
    cut, fade = FINALE_FROM * RATE, int(LOOP_FADE * RATE)
    battle = track[fade:cut].copy()
    battle[-fade:] = track[cut - fade:cut] * ramp(fade, False) + track[:fade] * ramp(fade, True)
    finale = track[cut:].copy()
    finale[:RATE // 50] *= ramp(RATE // 50, True)
    finale[-RATE // 2:] *= ramp(RATE // 2, False)
    print(f"music levelled to {loudness(track):.1f} LUFS")
    encode(battle, "music/iron_sentinel_battle", 4)
    encode(finale, "music/iron_sentinel_finale", 4)


def saw(path):
    hit = decode(path, 1)
    hit[-int(SAW_FADE * RATE):] *= ramp(int(SAW_FADE * RATE), False)
    encode(hit, "boss/iron_sentinel_saw_hit", 5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--music")
    parser.add_argument("--saw")
    args = parser.parse_args()
    if args.music:
        music(args.music)
    if args.saw:
        saw(args.saw)
