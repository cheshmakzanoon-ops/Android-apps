# Engine benchmarks

This folder describes how to measure the TimeFlip engine. It holds no generated numbers. The only
results below are the ones written down explicitly, with the method used to get them.

## What exists today

| Check | Where | What it measures | Kind |
|---|---|---|---|
| Alignment solver correctness | `src/test/kotlin/com/agency/timeflip/engine/alignment/` | Recovered translation, scale, rotation, bounds and determinism | Correctness, not timing |
| Export correctness on device | `src/androidTest/kotlin/com/agency/timeflip/engine/Mp4ExportInstrumentedTest.kt` | Track codec and size, duration within 2 %, first and last frames differ | Correctness, not timing |

Neither test records timing. Add timing only with the procedures below, and record the device model,
Android version, build type and commit next to every number.

## Running the JVM alignment checks

The pure-Kotlin solver runs on the JVM without an emulator. Use the JVM harness, which compiles
`domain/` and `engine/alignment/` from the app source tree:

```
cd /home/user/scratchpad_tf/jvm-harness
gradle test --console=plain --no-daemon
```

Results are written to `build/test-results/test/*.xml`.

## Procedures for timing (not yet implemented)

These are the measurements the PM should add when device time is available. Each one measures
one stage, so regressions can be assigned to a stage.

1. **Alignment latency (`AutoAligner.align`).** Time one call for a typical 12 MP before/after pair
   on a mid-range device, cold and warm. The call covers decoding at 2× the working size, the
   working raster render, and the solver on `Dispatchers.Default`. Split the timings with
   `android.os.Trace` sections, one for each stage.
2. **Frame draw cost (`FrameRenderer.draw`).** Time one call to `draw` into a 720×1280 and a
   1080×1920 bitmap, for transition progress 0.0, 0.5 and 1.0. Those cover the Empty, Rect and Full
   clip paths.
3. **Export throughput (`Mp4VideoExporter`).** Time the full export for each `OutputPreset` at
   30 fps, with a 2.8 s timeline (the default holds). Report wall time, frames per second, output
   file size and the bitrate actually used.
4. **Export memory.** Sample `Debug.getNativeHeapAllocatedSize()` and the Java heap before and after
   an export. The design keeps one ARGB frame bitmap, one `IntArray` of pixels and two decoded
   photo bitmaps, so the peak should not depend on timeline length. Check that.

## Measured results

### One-off JVM timing of `FeatureAligner.estimate` (not a device number)

- **Method:** a temporary JVM test called `estimate` once per case, wrapped in `System.nanoTime`.
  The inputs were 256×144 synthetic pairs from the test scene generator, with translation,
  scale, rotation and mixed cases, unrelated scenes and a flat raster. The timing test was then
  removed, so the numbers cannot be reproduced from this repository without re-adding it.
- **Result:** 5 to 26 ms per call on the sandbox JVM, for 16 calls. The figures are not from a device.
  They show that the solver is cheap at the 256-pixel working size, but they say nothing about
  Android performance.

No other results are recorded.
