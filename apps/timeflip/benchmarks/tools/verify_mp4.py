#!/usr/bin/env python3
"""Verify a TimeFlip MP4 with ffprobe, and optionally with ffmpeg. Python 3 standard library only.

Usage:
  verify_mp4.py FILE --width W --height H --fps F --duration-ms D [--tolerance-ms 100]
                     [--check-content] [--min-content-diff 4.0]
  verify_mp4.py --self-test

Checks (every one must pass):
  one_video_stream      exactly one video stream
  no_audio_stream       no audio stream (the MVP is silent, ADR-005)
  mp4_container         the container format includes mp4
  codec_h264            video codec is H.264
  pixel_format_yuv420p  4:2:0 chroma, which common media players require
  dimensions            width and height equal the expected values
  no_rotation           no rotation tag or display-matrix rotation (0 mod 360)
  frame_rate            average frame rate equals the expected fps
  frame_count           frame count is within one frame of fps x duration
  duration              container duration is within --tolerance-ms of the expected duration
  content_changes       (only with --check-content) the first and the last sampled frame differ
                        by at least --min-content-diff on a 0-255 scale. A static or black video fails.

Output: one JSON object on stdout. It contains no file names, URIs or pixel values.
Exit codes: 0 all checks passed; 1 a check failed; 2 usage error, missing tool or unreadable input.
Files: nothing is written. --self-test uses a temporary directory and removes it afterwards.
"""

import argparse
import json
import math
import os
import shutil
import subprocess
import sys
import tempfile
from collections import namedtuple

FFPROBE = "ffprobe"
FFMPEG = "ffmpeg"

DEFAULT_TOLERANCE_MS = 100
DEFAULT_MIN_CONTENT_DIFF = 4.0
FRAME_TOLERANCE = 1.0      # frames
FPS_TOLERANCE = 0.01
SAMPLE_SIZE = (64, 64)     # content comparison works on a small RGB thumbnail

BASE_CHECKS = (
    "one_video_stream",
    "no_audio_stream",
    "mp4_container",
    "codec_h264",
    "pixel_format_yuv420p",
    "dimensions",
    "no_rotation",
    "frame_rate",
    "frame_count",
    "duration",
)

Expected = namedtuple("Expected", ["width", "height", "fps", "duration_ms"])


class ToolError(Exception):
    """Usage, missing-tool or unreadable-input problem. Messages never contain file names."""


def _run(command, timeout=120):
    try:
        return subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as error:
        raise ToolError("required tool not found on PATH: " + command[0]) from error
    except subprocess.TimeoutExpired as error:
        raise ToolError("tool timed out: " + command[0]) from error


def _as_int(value):
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _as_float(value):
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) else None


def _parse_rate(text):
    """Parses ffprobe rates such as '30/1'. Returns None for '0/0' or malformed input."""
    if not text or "/" not in str(text):
        return None
    numerator_text, denominator_text = str(text).split("/", 1)
    numerator = _as_int(numerator_text)
    denominator = _as_int(denominator_text)
    if numerator is None or denominator in (None, 0) or numerator <= 0:
        return None
    return numerator / float(denominator)


def _rotation_values(stream):
    """Every rotation signal ffprobe reports. None marks a value that could not be parsed."""
    values = []
    tags = stream.get("tags") or {}
    if "rotate" in tags:
        values.append(_as_float(tags["rotate"]))
    for item in stream.get("side_data_list") or []:
        if "rotation" in item:
            values.append(_as_float(item["rotation"]))
    return values


def probe(path):
    result = _run([FFPROBE, "-v", "error", "-show_streams", "-show_format", "-of", "json", path])
    if result.returncode != 0:
        raise ToolError("ffprobe could not read the file")
    try:
        return json.loads(result.stdout.decode("utf-8", "replace"))
    except ValueError as error:
        raise ToolError("ffprobe returned unreadable output") from error


def evaluate(probe_data, expected, tolerance_ms=DEFAULT_TOLERANCE_MS):
    """Pure function over ffprobe JSON. Returns (checks, observed). Missing data fails the check."""
    streams = probe_data.get("streams") or []
    container = probe_data.get("format") or {}
    video = [stream for stream in streams if stream.get("codec_type") == "video"]
    audio = [stream for stream in streams if stream.get("codec_type") == "audio"]

    checks = {name: False for name in BASE_CHECKS}
    checks["one_video_stream"] = len(video) == 1
    checks["no_audio_stream"] = len(audio) == 0
    checks["mp4_container"] = "mp4" in str(container.get("format_name", "")).split(",")

    observed = {"video_streams": len(video), "audio_streams": len(audio)}
    stream = video[0] if len(video) == 1 else {}

    observed["codec"] = stream.get("codec_name")
    checks["codec_h264"] = stream.get("codec_name") == "h264"
    checks["pixel_format_yuv420p"] = stream.get("pix_fmt") == "yuv420p"

    width = _as_int(stream.get("width"))
    height = _as_int(stream.get("height"))
    observed["width"] = width
    observed["height"] = height
    checks["dimensions"] = (width, height) == (expected.width, expected.height)

    rotations = _rotation_values(stream)
    observed["rotation_degrees"] = [value for value in rotations if value is not None]
    checks["no_rotation"] = all(value is not None and round(value) % 360 == 0 for value in rotations)

    fps = _parse_rate(stream.get("avg_frame_rate")) or _parse_rate(stream.get("r_frame_rate"))
    observed["fps"] = round(fps, 3) if fps is not None else None
    checks["frame_rate"] = fps is not None and abs(fps - expected.fps) <= FPS_TOLERANCE

    duration_s = _as_float(container.get("duration"))
    observed["duration_ms"] = int(round(duration_s * 1000)) if duration_s is not None else None
    checks["duration"] = (
        duration_s is not None and abs(duration_s * 1000.0 - expected.duration_ms) <= tolerance_ms
    )

    frames = _as_int(stream.get("nb_frames"))
    if frames is None and duration_s is not None and fps is not None:
        frames = int(round(duration_s * fps))
    observed["frames"] = frames
    expected_frames = expected.fps * expected.duration_ms / 1000.0
    checks["frame_count"] = frames is not None and abs(frames - expected_frames) <= FRAME_TOLERANCE

    return checks, observed


def sample_rgb(path, seconds, size=SAMPLE_SIZE):
    """One frame decoded to raw rgb24 bytes, scaled to a small thumbnail. Written to a pipe only."""
    width, height = size
    command = [
        FFMPEG, "-v", "error",
        "-ss", "%.3f" % seconds,
        "-i", path,
        "-frames:v", "1",
        "-an", "-sn",
        "-vf", "scale=%d:%d:flags=area" % (width, height),
        "-f", "rawvideo", "-pix_fmt", "rgb24",
        "-",
    ]
    result = _run(command)
    if result.returncode != 0 or len(result.stdout) != width * height * 3:
        raise ToolError("could not decode a sample frame")
    return result.stdout


def mean_abs_difference(first, second):
    if not first or len(first) != len(second):
        raise ToolError("sample frames differ in size")
    return sum(abs(a - b) for a, b in zip(first, second)) / float(len(first))


def content_difference(path, duration_s, fps):
    """Mean absolute RGB difference between the first frame and the last frame of the video.

    The last sample is taken 1.5 frame intervals before the end. Seeking to exactly the last frame's
    timestamp can land just past it, and ffmpeg then returns no frame.
    """
    first = sample_rgb(path, 0.0)
    last = sample_rgb(path, max(0.0, duration_s - 1.5 / fps))
    return mean_abs_difference(first, last)


def verify(path, expected, tolerance_ms, check_content, min_content_diff):
    checks, observed = evaluate(probe(path), expected, tolerance_ms)
    if check_content:
        duration_s = (observed.get("duration_ms") or 0) / 1000.0
        if duration_s > 0:
            diff = content_difference(path, duration_s, expected.fps)
            observed["content_diff"] = round(diff, 2)
            checks["content_changes"] = diff >= min_content_diff
        else:
            checks["content_changes"] = False
    ok = all(checks.values())
    return ok, checks, observed


def _positive_int(text):
    value = _as_int(text)
    if value is None or value <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return value


def _positive_float(text):
    value = _as_float(text)
    if value is None or value <= 0:
        raise argparse.ArgumentTypeError("must be a positive number")
    return value


def _non_negative_float(text):
    value = _as_float(text)
    if value is None or value < 0:
        raise argparse.ArgumentTypeError("must be zero or a positive number")
    return value


def _print_json(payload):
    sys.stdout.write(json.dumps(payload, sort_keys=True) + "\n")


def _logic_self_tests():
    """Offline checks of evaluate() on synthetic ffprobe output. These need no ffmpeg."""
    expected = Expected(width=320, height=480, fps=30.0, duration_ms=1000)
    good_video = {
        "codec_type": "video", "codec_name": "h264", "pix_fmt": "yuv420p",
        "width": 320, "height": 480, "avg_frame_rate": "30/1", "r_frame_rate": "30/1", "nb_frames": "30",
    }
    good = {
        "streams": [good_video],
        "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "1.000000"},
    }
    cases = []

    def case(name, data, want_ok):
        checks, _ = evaluate(data, expected)
        got_ok = all(checks.values())
        cases.append({"case": name, "pass": got_ok == want_ok})

    case("logic: well-formed output passes", good, True)

    rotated = json.loads(json.dumps(good))
    rotated["streams"][0]["tags"] = {"rotate": "90"}
    case("logic: rotation tag fails", rotated, False)

    display_matrix = json.loads(json.dumps(good))
    display_matrix["streams"][0]["side_data_list"] = [{"side_data_type": "Display Matrix", "rotation": -90}]
    case("logic: display-matrix rotation fails", display_matrix, False)

    two_video = json.loads(json.dumps(good))
    two_video["streams"].append(dict(good_video))
    case("logic: two video streams fail", two_video, False)

    with_audio = json.loads(json.dumps(good))
    with_audio["streams"].append({"codec_type": "audio", "codec_name": "aac"})
    case("logic: audio stream fails (silent MVP)", with_audio, False)

    short_frames = json.loads(json.dumps(good))
    short_frames["streams"][0]["nb_frames"] = "26"
    case("logic: frame count off by four fails", short_frames, False)

    wrong_size = json.loads(json.dumps(good))
    wrong_size["streams"][0]["width"] = 480
    case("logic: wrong width fails", wrong_size, False)

    return cases


def run_self_test():
    cases = _logic_self_tests()
    result = {"self_test": "logic", "cases": cases}

    if not (shutil.which(FFMPEG) and shutil.which(FFPROBE)):
        result["fixture_cases"] = "skipped: ffmpeg or ffprobe not found on PATH"
        result["ok"] = all(item["pass"] for item in cases)
        _print_json(result)
        return 0 if result["ok"] else 1

    moving_expected = Expected(width=320, height=480, fps=30.0, duration_ms=1000)
    fixture_cases = []
    with tempfile.TemporaryDirectory(prefix="verify-mp4-selftest-") as scratch:
        moving = os.path.join(scratch, "moving.mp4")
        still = os.path.join(scratch, "still.mp4")
        try:
            _encode_fixture(moving, "testsrc2=size=320x480:rate=30:duration=1")
            _encode_fixture(still, "color=c=gray:size=320x480:rate=30:duration=1")
            scenarios = [
                ("fixture: moving clip passes", moving, moving_expected, True),
                ("fixture: wrong dimensions fail", moving, Expected(480, 320, 30.0, 1000), False),
                ("fixture: wrong duration fails", moving, Expected(320, 480, 30.0, 2000), False),
                ("fixture: wrong fps fails", moving, Expected(320, 480, 25.0, 1000), False),
                ("fixture: static clip fails content check", still, moving_expected, False),
            ]
            for name, target, expected, want_ok in scenarios:
                got_ok, _, _ = verify(target, expected, DEFAULT_TOLERANCE_MS, True, DEFAULT_MIN_CONTENT_DIFF)
                fixture_cases.append({"case": name, "pass": got_ok == want_ok})
        except ToolError as error:
            result["fixture_cases"] = "error: " + str(error)
            result["ok"] = False
            _print_json(result)
            return 2

    result["fixture_cases"] = fixture_cases
    result["ok"] = all(item["pass"] for item in cases + fixture_cases)
    _print_json(result)
    return 0 if result["ok"] else 1


def _encode_fixture(target, source_expression):
    result = _run([
        FFMPEG, "-v", "error", "-y",
        "-f", "lavfi", "-i", source_expression,
        "-c:v", "libx264", "-pix_fmt", "yuv420p", "-movflags", "+faststart",
        target,
    ])
    if result.returncode != 0 or not os.path.isfile(target):
        raise ToolError("could not create the self-test fixture (is libx264 available?)")


def build_parser():
    parser = argparse.ArgumentParser(description="Verify a TimeFlip MP4 with ffprobe.")
    parser.add_argument("file", nargs="?", help="MP4 file to verify")
    parser.add_argument("--width", type=_positive_int, help="expected width in pixels")
    parser.add_argument("--height", type=_positive_int, help="expected height in pixels")
    parser.add_argument("--fps", type=_positive_float, help="expected frame rate")
    parser.add_argument("--duration-ms", type=_positive_int, help="expected duration in milliseconds")
    parser.add_argument("--tolerance-ms", type=_non_negative_float, default=DEFAULT_TOLERANCE_MS,
                        help="allowed container duration error in milliseconds (default 100)")
    parser.add_argument("--check-content", action="store_true",
                        help="also compare the first and last sampled frames (needs ffmpeg)")
    parser.add_argument("--min-content-diff", type=_non_negative_float, default=DEFAULT_MIN_CONTENT_DIFF,
                        help="minimum mean absolute RGB difference for --check-content (default 4.0)")
    parser.add_argument("--self-test", action="store_true",
                        help="run offline self-tests; fixture tests run only when ffmpeg is present")
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if args.self_test:
        return run_self_test()

    required = {"width": args.width, "height": args.height, "fps": args.fps, "duration_ms": args.duration_ms}
    missing = sorted(name for name, value in required.items() if value is None)
    if not args.file or missing:
        parser.error("FILE, --width, --height, --fps and --duration-ms are required")
    if not os.path.isfile(args.file):
        _print_json({"ok": False, "error": "input is not a readable file"})
        return 2
    if not shutil.which(FFPROBE):
        _print_json({"ok": False, "error": "required tool not found on PATH: ffprobe"})
        return 2
    if args.check_content and not shutil.which(FFMPEG):
        _print_json({"ok": False, "error": "required tool not found on PATH: ffmpeg"})
        return 2

    expected = Expected(width=args.width, height=args.height, fps=args.fps, duration_ms=args.duration_ms)
    try:
        ok, checks, observed = verify(
            args.file, expected, args.tolerance_ms, args.check_content, args.min_content_diff
        )
    except ToolError as error:
        _print_json({"ok": False, "error": str(error)})
        return 2

    _print_json({"ok": ok, "checks": checks, "observed": observed})
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
