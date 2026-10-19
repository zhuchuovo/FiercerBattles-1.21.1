"""Synthesize an original metallic parry 'clang' (bright strike + inharmonic ring) as mono OGG Vorbis."""
import sys

import numpy as np
import soundfile as sf
from scipy.signal import butter, sosfilt

RATE = 48000
LENGTH = 1.35
rng = np.random.default_rng(7)
t = np.arange(int(RATE * LENGTH)) / RATE


def band(signal, low, high, order=4):
    sos = butter(order, [low, high], btype="band", fs=RATE, output="sos")
    return sosfilt(sos, signal)


def ring(freq, amp, tau, detune=0.0, glide=0.03):
    # Struck metal starts slightly sharp and settles within ~20 ms.
    f = freq * (1 + glide * np.exp(-t / 0.012))
    phase = 2 * np.pi * np.cumsum(f) / RATE
    tone = np.sin(phase + rng.uniform(0, 2 * np.pi))
    if detune:
        tone = 0.5 * tone + 0.5 * np.sin(phase * (1 + detune / freq) + rng.uniform(0, 2 * np.pi))
    return amp * tone * np.exp(-t / tau)


# Inharmonic modes of a struck steel blade: frequency (Hz), level, decay (s), beating detune (Hz).
modes = [
    (1318, 1.00, 0.62, 2.5), (2093, 0.80, 0.46, 3.0), (2963, 0.58, 0.34, 4.0),
    (3889, 0.46, 0.26, 3.5), (4811, 0.30, 0.18, 0.0), (6137, 0.20, 0.12, 0.0),
    (7466, 0.12, 0.08, 0.0), (9011, 0.07, 0.05, 0.0),
]
metal = sum(ring(f, a, tau * 0.75, d) for f, a, tau, d in modes)

# Hard contact transient and a short scrape of the edges sliding apart.
noise = rng.standard_normal(t.size)
click = band(noise, 1800, 12000) * np.exp(-t / 0.0035) * 2.2
scrape = band(noise, 5000, 14000) * np.exp(-t / 0.035) * 0.25
scrape *= 1 - np.exp(-t / 0.004)
body = np.sin(2 * np.pi * 430 * t) * np.exp(-t / 0.045) * 0.55

signal = metal * 0.55 + click + scrape + body
attack = np.minimum(1.0, t / 0.0008)
release = np.clip((LENGTH - t) / 0.15, 0, 1)
signal *= attack * release
signal = np.tanh(signal * 0.9)
signal *= 10 ** (-3.0 / 20) / np.max(np.abs(signal))

out = sys.argv[1]
sf.write(out, signal.astype(np.float32), RATE, format="OGG", subtype="VORBIS")
sf.write(out.replace(".ogg", ".wav"), signal.astype(np.float32), RATE)
print("wrote", out, f"{LENGTH}s", "peak dBFS", round(20 * np.log10(np.max(np.abs(signal))), 2))
