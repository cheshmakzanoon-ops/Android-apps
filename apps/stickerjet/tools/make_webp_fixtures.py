#!/usr/bin/env python3
"""Generate small WebP test fixtures with Pillow and record PIL's view of them.

Writes into core/src/test/resources/webp/ (default):
  lossy_alpha_64.webp          still, VP8X + ALPH + VP8 (lossy with alpha)
  lossy_alpha_odd_33x17.webp   still, odd bitstream size (exercises chunk padding)
  lossless_alpha_48.webp       still, VP8L
  lossy_alpha_64_b.webp        still, 64x64 lossy with alpha, different phase (animation frame)
  lossless_alpha_64.webp       still, 64x64 VP8L (animation frame of the writer tests)
  pil_animated_3f.webp         PIL save_all, 3 lossy frames, durations 100/200/300 ms, loop 0
  pil_animated_lossless_2f.webp PIL save_all, 2 lossless frames, durations 40/60 ms, loop 3
  expected.json                what Pillow reports for every fixture (the test oracle)

Usage: python3 -I make_webp_fixtures.py [output_dir]
Requires Pillow built with WebP support (PIL 12.x, libwebp with mux/demux for animation).
"""
import json
import math
import os
import sys

from PIL import Image, ImageDraw

FIXTURE_DIR = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "core", "src", "test", "resources", "webp")


def frame(size, phase):
    """A deterministic RGBA frame: gradient background, transparent corners, a bright disc."""
    w, h = size
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    px = img.load()
    cx, cy, r = w * (0.5 + 0.1 * math.cos(phase)), h * 0.5, min(w, h) * 0.32
    for y in range(h):
        for x in range(w):
            d = math.hypot(x + 0.5 - cx, y + 0.5 - cy)
            if d <= r:
                px[x, y] = (255, int(255 * x / w), int(255 * y / h), 255)
            elif d <= r + 2:
                a = int(255 * (r + 2 - d) / 2)
                px[x, y] = (40, 90, 200, a)
            else:
                px[x, y] = (x * 3 % 256, y * 5 % 256, 90, 0)
    return img


def chunk_walk(path):
    """Independent RIFF walk (no Pillow): returns top-level FourCCs and every ANMF sub-chunk FourCC."""
    with open(path, "rb") as fh:
        data = fh.read()
    top, subs = [], []
    at, end = 12, len(data)
    while at < end:
        fourcc = data[at:at + 4].decode("ascii")
        size = int.from_bytes(data[at + 4:at + 8], "little")
        payload = at + 8
        top.append(fourcc)
        if fourcc == "ANMF":
            sat, send = payload + 16, payload + size
            while sat < send:
                sf = data[sat:sat + 4].decode("ascii")
                ss = int.from_bytes(data[sat + 4:sat + 8], "little")
                subs.append(sf)
                sat = sat + 8 + ss + (ss & 1)
        at = payload + size + (size & 1)
    return top, subs


def describe_pil(path):
    im = Image.open(path)
    n = getattr(im, "n_frames", 1)
    durations, alpha_ranges = [], []
    for i in range(n):
        im.seek(i)
        rgba = im.convert("RGBA")
        durations.append(im.info.get("duration") if n > 1 else None)
        alpha_ranges.append(list(rgba.getchannel("A").getextrema()))
    top, subs = chunk_walk(path)
    return {
        "file": os.path.basename(path),
        "size_bytes": os.path.getsize(path),
        "top_chunks": top,
        "anmf_subchunks": subs,
        "alph_present": "ALPH" in top or "ALPH" in subs,
        "width": im.size[0],
        "height": im.size[1],
        "mode": im.mode,
        "n_frames": n,
        "is_animated": bool(getattr(im, "is_animated", n > 1)),
        "durations_ms": durations if n > 1 else [],
        "loop": im.info.get("loop", 0) if n > 1 else None,
        "alpha_min_max_per_frame": alpha_ranges,
    }


def main():
    os.makedirs(FIXTURE_DIR, exist_ok=True)
    made = {}

    made["lossy_alpha_64.webp"] = frame((64, 64), 0.0)
    made["lossy_alpha_odd_33x17.webp"] = frame((33, 17), 1.0)
    made["lossless_alpha_48.webp"] = frame((48, 48), 2.0)
    made["lossy_alpha_64_b.webp"] = frame((64, 64), 3.0)
    made["lossless_alpha_64.webp"] = frame((64, 64), 4.0)

    anim3 = [frame((64, 64), p) for p in (0.0, 1.0, 2.0)]
    anim2 = [frame((32, 32), p) for p in (0.5, 1.5)]

    saves = {}
    for name, img in made.items():
        path = os.path.join(FIXTURE_DIR, name)
        if name.startswith("lossless"):
            img.save(path, "WEBP", lossless=True)
        else:
            img.save(path, "WEBP", quality=80, method=4)
        saves[name] = path

    p3 = os.path.join(FIXTURE_DIR, "pil_animated_3f.webp")
    anim3[0].save(p3, "WEBP", save_all=True, append_images=anim3[1:],
                  duration=[100, 200, 300], loop=0, quality=80, method=4)
    p2 = os.path.join(FIXTURE_DIR, "pil_animated_lossless_2f.webp")
    anim2[0].save(p2, "WEBP", save_all=True, append_images=anim2[1:],
                  duration=[40, 60], loop=3, lossless=True)

    report = {"generator": "tools/make_webp_fixtures.py", "pillow": Image.__version__, "fixtures": []}
    names = sorted(list(made.keys()) + ["pil_animated_3f.webp", "pil_animated_lossless_2f.webp"])
    for name in names:
        report["fixtures"].append(describe_pil(os.path.join(FIXTURE_DIR, name)))
    with open(os.path.join(FIXTURE_DIR, "expected.json"), "w", encoding="utf-8") as fh:
        json.dump(report, fh, indent=2)
        fh.write("\n")
    for entry in report["fixtures"]:
        print(json.dumps(entry))


if __name__ == "__main__":
    main()
