#!/usr/bin/env python3
"""Independent check of a WebP file with Pillow, printed as JSON.

Reports the canvas size, frame count, per-frame durations, alpha mode and extrema, loop count, and a
structural walk of the RIFF container written by hand (not by Pillow): RIFF size, chunk sizes, ANMF
sub-chunks and their padding. Use it on Kotlin-written files to confirm they match what a real decoder sees.

Usage: python3 -I verify_animated_webp.py file.webp [more.webp ...]
Exit status is 1 when any structural check fails or Pillow cannot decode a file.
"""
import json
import os
import struct
import sys

from PIL import Image


def walk(data, start, end):
    """Yields (fourcc, payload_start, payload_size) for chunks in [start, end); raises on overrun."""
    at = start
    while at < end:
        if end - at < 8:
            raise ValueError(f"truncated chunk header at {at}")
        fourcc = data[at:at + 4].decode("ascii", "replace")
        size = struct.unpack_from("<I", data, at + 4)[0]
        payload = at + 8
        if payload + size > end:
            raise ValueError(f"chunk {fourcc} at {at} overruns its container")
        yield fourcc, payload, size
        at = payload + size + (size & 1)


def structure(path):
    with open(path, "rb") as fh:
        data = fh.read()
    problems = []
    if data[0:4] != b"RIFF" or data[8:12] != b"WEBP":
        return {"ok": False, "problems": ["not RIFF/WEBP"]}
    riff_size = struct.unpack_from("<I", data, 4)[0]
    riff_size_ok = riff_size + 8 == len(data)
    if not riff_size_ok:
        problems.append(f"RIFF size {riff_size}+8 != file length {len(data)}")
    top, anmf = [], []
    try:
        for fourcc, p, size in walk(data, 12, min(len(data), riff_size + 8)):
            top.append(fourcc)
            if fourcc == "ANMF":
                subs = [sf for sf, _, _ in walk(data, p + 16, p + size)]
                anmf.append({
                    "frame_w": int.from_bytes(data[p + 6:p + 9], "little") + 1,
                    "frame_h": int.from_bytes(data[p + 9:p + 12], "little") + 1,
                    "duration_ms": int.from_bytes(data[p + 12:p + 15], "little"),
                    "flags": data[p + 15],
                    "subchunks": subs,
                })
    except ValueError as exc:
        problems.append(str(exc))
    return {"ok": not problems, "riff_size_ok": riff_size_ok, "top_chunks": top,
            "anmf": anmf, "problems": problems}


def pil_view(path):
    im = Image.open(path)
    n = getattr(im, "n_frames", 1)
    frames = []
    for i in range(n):
        im.seek(i)
        lo, hi = im.convert("RGBA").getchannel("A").getextrema()
        frames.append({"index": i, "duration_ms": im.info.get("duration"),
                       "alpha_min": lo, "alpha_max": hi})
    return {"size": list(im.size), "format": im.format, "mode": im.mode,
            "n_frames": n, "is_animated": bool(getattr(im, "is_animated", n > 1)),
            "loop": im.info.get("loop", 0), "frames": frames}


def main(paths):
    status = 0
    for path in paths:
        report = {"file": path, "size_bytes": os.path.getsize(path)}
        try:
            report["pillow"] = pil_view(path)
        except Exception as exc:  # report the decoder failure instead of hiding it
            report["pillow"] = {"error": repr(exc)}
            status = 1
        report["structure"] = structure(path)
        if not report["structure"]["ok"]:
            status = 1
        print(json.dumps(report, indent=2))
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
