"""Draws the Iron Sentinel's mortar warning textures (white, tinted and animated by SentinelShellRenderer).

    python tools/sentinel_mortar_textures.py

mortar_ring     the edge of the 3 block blast: a lit rim, range ticks and an inner dashed ring
mortar_fill     the countdown disc, grown from the centre to the rim as the shell comes down
mortar_reticle  the crosshair turning on the spot the shell lands on

Each is drawn 4x oversized and box-filtered down. Entity shaders discard texels under 0.1 alpha (after the tint), so
the faint parts stay above that.
"""
import math
import os

import numpy as np
from PIL import Image

OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "assets", "fiercerbattles", "textures",
                   "entity", "iron_sentinel")
SUPER = 4


def grid(size):
    n = size * SUPER
    c = (np.arange(n) + 0.5) / n * 2.0 - 1.0
    x, y = np.meshgrid(c, c)
    return np.hypot(x, y), np.degrees(np.arctan2(y, x)) % 360.0


def band(r, inner, outer):
    return ((r >= inner) & (r <= outer)).astype(np.float32)


def falloff(r, edge, width):
    """1 at {@code edge}, 0 at {@code edge + width} (width may be negative to fall inward)."""
    t = (r - edge) / width
    return np.clip(1.0 - t, 0.0, 1.0) * (t >= 0)


def spokes(angle, step, half_width, offset=0.0):
    """1 within {@code half_width} degrees of every {@code step} degrees."""
    d = (angle - offset + step / 2.0) % step - step / 2.0
    return (np.abs(d) <= half_width).astype(np.float32)


def save(name, alpha, size):
    a = np.clip(alpha, 0.0, 1.0).reshape(size, SUPER, size, SUPER).mean(axis=(1, 3))
    rgba = np.zeros((size, size, 4), np.uint8)
    rgba[..., :3] = 255
    rgba[..., 3] = np.round(a * 255).astype(np.uint8)
    Image.fromarray(rgba, "RGBA").save(os.path.join(OUT, name + ".png"))


def ring():
    size = 128
    r, angle = grid(size)
    rim = band(r, 0.90, 0.97)
    glow_out = 0.55 * falloff(r, 0.97, 0.03)
    glow_in = 0.45 * falloff(-r, -0.90, 0.22) ** 1.5
    ticks = band(r, 0.80, 0.86) * spokes(angle, 15.0, 1.6)
    marks = band(r, 0.70, 0.86) * spokes(angle, 90.0, 2.4)
    dashes = band(r, 0.54, 0.575) * spokes(angle, 15.0, 4.0, 7.5)
    alpha = np.maximum.reduce([rim, glow_out, glow_in, ticks, marks, dashes * 0.85])
    save("mortar_ring", alpha * (r <= 1.0), size)


def fill():
    size = 128
    r, _ = grid(size)
    body = 0.30 + 0.35 * np.clip(r / 0.92, 0.0, 1.0) ** 2
    front = band(r, 0.93, 1.0) + 0.6 * falloff(-r, -0.93, 0.08)
    save("mortar_fill", np.maximum(body, front) * (r <= 1.0), size)


def reticle():
    size = 64
    r, angle = grid(size)
    circle = band(r, 0.40, 0.48)
    # Distance across the nearest of the four axes, so the pointers keep straight sides.
    across = r * np.abs(np.sin(np.radians((angle + 45.0) % 90.0 - 45.0)))
    pointers = band(r, 0.56, 0.96) * (across <= (r - 0.56) * 0.42)
    dot = (r <= 0.10).astype(np.float32)
    save("mortar_reticle", np.maximum.reduce([circle, pointers, dot]), size)


if __name__ == "__main__":
    ring()
    fill()
    reticle()
    print("wrote mortar_ring, mortar_fill, mortar_reticle to", os.path.normpath(OUT))
