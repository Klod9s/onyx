package com.onyx.perfmonitor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;

public class FpsDetectorTest {

    /**
     * One synthetic clock, exposed in the units each detector supplier expects:
     * elapsedRealtime is milliseconds and nanoTime is nanoseconds. Advancing
     * {@code clock} by 1_000_000_000L therefore moves one second on both.
     */
    private static FpsDetector detectorAt(AtomicLong clock) {
        return new FpsDetector(() -> clock.get() / 1_000_000L, clock::get);
    }

    /**
     * Produces latency dumps whose newest presentation timestamp sits
     * {@code ageNs} before the injected clock, so the detector's staleness
     * check and the fixture share one timebase.
     */
    private static String latencyDump(AtomicLong clock, long ageNs, int frames) {
        StringBuilder dump = new StringBuilder("10000000\n");
        long end = clock.get() - ageNs;
        for (int i = frames - 1; i >= 0; i--) {
            dump.append("0 ").append(end - i * 10_000_000L).append(" 0\n");
        }
        return dump.toString();
    }

    private static FpsDetector.Shell shell(
            java.util.function.Supplier<String> dump,
            java.util.function.Supplier<String> layers,
            java.util.function.Function<String, String> latency,
            java.util.function.Supplier<String> timeStats) {
        return new FpsDetector.Shell() {
            @Override public String surfaceFlingerDump() { return dump.get(); }
            @Override public String surfaceFlingerLayers() { return layers.get(); }
            @Override public String surfaceFlingerLatency(String layerName) {
                return latency.apply(layerName);
            }
            @Override public String surfaceFlingerTimeStatsDump() { return timeStats.get(); }
        };
    }

    /**
     * Drives the detector until it reports something, advancing the clock
     * between calls. After the discovery pass the detector probes one source
     * per call and needs a second read before a frame counter can produce a
     * rate, so a single call is not enough.
     */
    private static FpsDetector.Result detectUntilValid(
            FpsDetector detector, FpsDetector.Shell shell, AtomicLong clock,
            long stepMs, int maxPasses) {
        FpsDetector.Result result = detector.detect(shell, "com.example.game");
        for (int i = 1; i < maxPasses && !result.valid(); i++) {
            clock.addAndGet(stepMs * 1_000_000L);
            result = detector.detect(shell, "com.example.game");
        }
        return result;
    }

    @Test
    public void defaultTimeStatsSnapshotKeepsLayerAndStatsSectionsSeparate() throws Exception {
        FpsDetector.Shell shell = shell(
                () -> "",
                () -> "SurfaceView com.example.game\n",
                layer -> "",
                () -> "layerName = SurfaceView com.example.game\ntotalFrames = 120\n");

        assertEquals("__ONYX_LAYERS__\nSurfaceView com.example.game\n"
                        + "__ONYX_TIMESTATS__\n"
                        + "layerName = SurfaceView com.example.game\n"
                        + "totalFrames = 120\n",
                shell.surfaceFlingerTimeStatsSnapshot());
    }




    @Test
    public void buildsSafeSingleShellLayerLatencyScript() {
        String script = FpsDetector.buildSurfaceFlingerLayerLatencyScript(
                "com.example.game", true);

        assertTrue(script.contains("dumpsys SurfaceFlinger --list"));
        assertTrue(script.contains("awk -v pkg='com.example.game' -v blast=1"));
        assertTrue(script.contains("dumpsys SurfaceFlinger --latency"));
        assertTrue(script.contains("$SELECTED"));
    }


    @Test
    public void rejectsUnknownPackageWithoutRunningShellCommands() {
        AtomicLong clock = new AtomicLong(1000);
        FpsDetector detector = detectorAt(clock);
        List<String> calls = new ArrayList<>();

        FpsDetector.Result result = detector.detect(shell(
                () -> { calls.add("dump"); return ""; },
                () -> { calls.add("layers"); return ""; },
                layer -> { calls.add("latency"); return ""; },
                () -> { calls.add("stats"); return ""; }
        ), "Unknown");

        assertFalse(result.valid());
        assertEquals("none", result.source);
        assertTrue(calls.isEmpty());
    }

    @Test
    public void doesNotUseDisplayRateAsAppFps() {
        AtomicLong clock = new AtomicLong(1000);
        FpsDetector detector = detectorAt(clock);
        List<String> calls = new ArrayList<>();

        FpsDetector.Result result = detector.detect(shell(
                () -> { calls.add("dump"); return ""; },
                () -> { calls.add("layers"); return ""; },
                layer -> { calls.add("latency"); return ""; },
                () -> { calls.add("stats"); return ""; }
        ), "com.example.game");

        // No layer and no frame counter: the result is "no FPS", never the
        // display refresh rate standing in for the app's rate.
        assertFalse(result.valid());
        assertEquals("none", result.source);
        assertTrue(calls.size() > 0);
    }

    @Test
    public void calculatesFrameCounterFpsFromConsecutiveSamples() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        AtomicLong frames = new AtomicLong(100);
        FpsDetector detector = detectorAt(clock);

        FpsDetector.Shell shell = shell(
                () -> "SurfaceView com.example.game\nframe=" + frames.get() + "\n",
                () -> "",
                layer -> "",
                () -> "");

        // First read establishes the counter baseline and returns nothing.
        FpsDetector.Result baseline = detector.detect(shell, "com.example.game");
        assertFalse(baseline.valid());

        // Clear the post-discovery quiet window without adding frames, so the
        // rate is measured over whatever interval actually elapsed.
        clock.addAndGet(6_000_000_000L);
        detectUntilValid(detector, shell, clock, 0, 4);

        // Now advance exactly one second and add exactly 60 frames.
        frames.set(160);
        clock.addAndGet(1_000_000_000L);

        FpsDetector.Result measured = detectUntilValid(
                detector, shell, clock, 1_000_000_000L, 3);

        assertTrue(measured.valid());
        assertEquals("SF Counter", measured.source);
        assertTrue("Rate should be in a believable range: " + measured.fps,
                measured.fps > 0 && measured.fps <= 120);
    }

    @Test
    public void keepsSfCounterValidWhenLayerIsIdleAndMeasuresWhenItResumes() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        AtomicLong frames = new AtomicLong(100);
        FpsDetector detector = detectorAt(clock);

        FpsDetector.Shell shell = shell(
                () -> "SurfaceView com.example.game\nframe=" + frames.get() + "\n",
                () -> "",
                layer -> "",
                () -> "");

        FpsDetector.Result baseline = detector.detect(shell, "com.example.game");
        assertFalse(baseline.valid());

        clock.addAndGet(1_000_000_000L);
        FpsDetector.Result idle = detectUntilValid(detector, shell, clock, 6000, 6);
        assertTrue(idle.valid());
        assertEquals(0.0, idle.fps, 0.0001);
        assertEquals("SF Counter", idle.source);

        frames.set(160);
        clock.addAndGet(1_000_000_000L);

        FpsDetector.Result resumed = detectUntilValid(detector, shell, clock, 6000, 6);
        assertTrue(resumed.valid());
        assertEquals(60.0, resumed.fps, 0.05);
        assertEquals("SF Counter", resumed.source);
    }

    @Test
    public void frameCounterResetEstablishesANewBaseline() {
        AtomicLong clock = new AtomicLong(1_000_000_000L);
        AtomicLong frames = new AtomicLong(100);
        FpsDetector detector = detectorAt(clock);

        FpsDetector.Shell shell = shell(
                () -> "SurfaceView com.example.game\nframe=" + frames.get() + "\n",
                () -> "",
                layer -> "",
                () -> "");

        detector.detect(shell, "com.example.game");

        // The counter goes backwards: that must re-baseline, not report FPS.
        frames.set(50);
        clock.addAndGet(1_000_000_000L);
        FpsDetector.Result afterReset = detectUntilValid(detector, shell, clock, 6000, 6);
        assertTrue("A counter reset should not report a rate",
                !afterReset.valid() || afterReset.fps == 0.0);

        frames.set(110);
        clock.addAndGet(1_000_000_000L);
        FpsDetector.Result recovered = detectUntilValid(detector, shell, clock, 6000, 6);

        assertTrue(recovered.valid());
        assertEquals(60.0, recovered.fps, 0.05);
        assertEquals("SF Counter", recovered.source);
    }

    @Test
    public void reportsFpsFromBlastLatency() {
        // Above LOOKUP_RETRY_MS (5 s) so the first BLAST probe is not gated off.
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        AtomicLong snapshotCalls = new AtomicLong();
        FpsDetector detector = detectorAt(clock);

        FpsDetector.Shell shell = new FpsDetector.Shell() {
            @Override public String surfaceFlingerDump() { return ""; }
            @Override public String surfaceFlingerLayers() { return ""; }
            @Override public String surfaceFlingerLatency(String layerName) { return ""; }
            @Override public String surfaceFlingerTimeStatsDump() { return ""; }
            @Override public String surfaceFlingerLayerLatencySnapshot(
                    String packageName, boolean blastOnly) {
                assertTrue("BLAST is probed first", blastOnly);
                snapshotCalls.incrementAndGet();
                return "__ONYX_SELECTED_LAYER__\n"
                        + "BLAST SurfaceView com.example.game/BLAST\n"
                        + "__ONYX_LATENCY__\n"
                        + latencyDump(clock, 500_000_000L, 100);
            }
        };

        FpsDetector.Result result = detector.detect(shell, "com.example.game");

        assertTrue(result.valid());
        assertEquals("BLAST SF latency", result.source);
        assertEquals(10.0, result.frameRefreshPeriodMs, 0.0001);
        assertTrue("plausible FPS for a 10 ms refresh: " + result.fps,
                result.fps > 50 && result.fps <= 120);
        assertEquals(1, snapshotCalls.get());
    }

    @Test
    public void fallsBackToSeparateLookupWhenSnapshotIsNull() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        AtomicLong layerCalls = new AtomicLong();
        AtomicLong latencyCalls = new AtomicLong();
        FpsDetector detector = detectorAt(clock);

        // No snapshot override: the interface default returns null, which is also
        // what a malformed reply or a RemoteException produces. The detector must
        // still find the layer through the separate two-command lookup.
        FpsDetector.Shell shell = new FpsDetector.Shell() {
            @Override public String surfaceFlingerDump() { return ""; }
            @Override public String surfaceFlingerLayers() {
                layerCalls.incrementAndGet();
                return "BLAST SurfaceView com.example.game\n";
            }
            @Override public String surfaceFlingerLatency(String layerName) {
                latencyCalls.incrementAndGet();
                return latencyDump(clock, 500_000_000L, 100);
            }
            @Override public String surfaceFlingerTimeStatsDump() { return ""; }
        };

        FpsDetector.Result result = detector.detect(shell, "com.example.game");

        assertTrue("FPS must be found through the separate lookup", result.valid());
        assertEquals("BLAST SF latency", result.source);
        assertTrue("layer list was queried", layerCalls.get() >= 1);
        assertTrue("latency was read separately", latencyCalls.get() >= 1);
    }

    @Test
    public void fallsBackToSeparateLookupWhenSnapshotLatencyIsEmpty() {
        AtomicLong clock = new AtomicLong(10_000_000_000L);
        AtomicLong layerCalls = new AtomicLong();
        AtomicLong latencyCalls = new AtomicLong();
        FpsDetector detector = detectorAt(clock);

        FpsDetector.Shell shell = new FpsDetector.Shell() {
            @Override public String surfaceFlingerDump() { return ""; }
            @Override public String surfaceFlingerLayers() {
                layerCalls.incrementAndGet();
                return "BLAST SurfaceView com.example.game\n";
            }
            @Override public String surfaceFlingerLatency(String layerName) {
                latencyCalls.incrementAndGet();
                return latencyDump(clock, 500_000_000L, 100);
            }
            @Override public String surfaceFlingerTimeStatsDump() { return ""; }
            @Override public String surfaceFlingerLayerLatencySnapshot(
                    String packageName, boolean blastOnly) {
                // Markers present but both sections empty: readLayerLatencySnapshot
                // returns null, so the fallback must run.
                return "__ONYX_SELECTED_LAYER__\n__ONYX_LATENCY__\n";
            }
        };

        FpsDetector.Result result = detector.detect(shell, "com.example.game");

        assertTrue(result.valid());
        assertEquals("BLAST SF latency", result.source);
        assertEquals(1, layerCalls.get());
        assertEquals(1, latencyCalls.get());
    }


}