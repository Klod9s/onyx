package com.onyx.perfmonitor;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.content.pm.PackageManager;
import rikka.shizuku.Shizuku;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Context-independent owner for the live metric sampling state.
 * The Activity and foreground overlay service can each own an instance;
 * only one should sample at a time.
 */
final class MonitoringEngine {
    private final Context context;
    private final BatteryCurrent batteryCurrent;
    private volatile IOnyxUserService shizukuService;
    private final FpsDetector fpsDetector = new FpsDetector();
    private volatile boolean frameCaptureRequested;
    private volatile boolean frameCaptureActive;
    private volatile boolean frameCaptureStopRequested;
    private volatile long frameCaptureStartNs = Long.MAX_VALUE;
    private long startElapsedMs = android.os.SystemClock.elapsedRealtime();
    private final CpuUsageTracker cpuUsageTracker = new CpuUsageTracker();
    private volatile String cachedForegroundPackage = "";
    private volatile long lastForegroundCheckMs = 0;
    // Root-only: GPU sysfs discovery (devfreq scan) is throttled while nothing has
    // ever been found, instead of forking a shell per devfreq node every sample.
    private long lastGpuScanMs = 0;
    private volatile long rootFgRefreshMs = 1500;
    private volatile long shizukuFgRefreshMs = 1500;
    private static final java.util.regex.Pattern COMPONENT_PATTERN =
            java.util.regex.Pattern.compile(
                    "([A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+");
    // Root helper (app_process running ShizukuShellService's reader as uid 0).
    private volatile boolean rootHelperReady = false;
    private volatile RootShell rootHelperShell;
    private volatile long rootHelperRetryAtMs = 0;
    private volatile int rootHelperFailures = 0;
    private final java.util.concurrent.atomic.AtomicBoolean rootHelperStarting =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private boolean gpuLoadEverFound = false;
    // Root-only timing, logged as a rolling average (tag OnyxRootPerf).
    private int perfCount = 0;
    private long perfFgMs = 0;
    private long perfMetricsMs = 0;
    private long perfTotalMs = 0;
    // Shizuku-only timing, logged as a rolling average (tag OnyxShizukuPerf).
    private int szPerfCount = 0;
    private long szPerfMetricsMs = 0;
    private long szPerfFgMs = 0;
    private long szPerfTotalMs = 0;
    private String cachedGpuFreqPath = "";
    private String cachedGpuLoadPath = "";
    private String cachedMaliPath = "";
    private boolean maliSearchCompleted = false;
    private volatile boolean rootChecked = false;
    private volatile boolean cachedRoot = false;
    private volatile int rootFailStreak = 0;
    private final java.util.concurrent.atomic.AtomicBoolean rootProbeInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private long lastRootCheckMs = 0;
    private String cachedMonitoringMode = "";
    private long lastModeCheckMs = 0;
    private volatile String lastSamplingMode = "";

    MonitoringEngine(Context context) {
        this.context = context.getApplicationContext();
        this.batteryCurrent = new BatteryCurrent(this.context);
        try {
            android.hardware.display.DisplayManager dm =
                    this.context.getSystemService(android.hardware.display.DisplayManager.class);
            android.view.Display d = dm.getDisplay(android.view.Display.DEFAULT_DISPLAY);
            float max = 0;
            for (android.view.Display.Mode m : d.getSupportedModes()) {
                max = Math.max(max, m.getRefreshRate());
            }
            if (max > 0) fpsDetector.setMaxDisplayFps(max);
        } catch (Exception ignored) {
        }
    }

    void setShizukuService(IOnyxUserService service) {
        this.shizukuService = service;
    }

    String getMonitoringMode() {
        return cachedMonitoringMode.isEmpty() ? "ANDROID" : cachedMonitoringMode;
    }

    void resetCpuBaseline() {
        cpuUsageTracker.reset();
    }

    void resetSessionClock() {
        startElapsedMs = android.os.SystemClock.elapsedRealtime();
    }

    void beginFrameCapture() {
        frameCaptureStopRequested = false;
        frameCaptureStartNs = System.nanoTime();
        frameCaptureRequested = true;
        frameCaptureActive = true;
    }

    void endFrameCapture() {
        frameCaptureActive = false;
        frameCaptureRequested = false;
        frameCaptureStopRequested = true;
        frameCaptureStartNs = Long.MAX_VALUE;
    }

    void resetMonitoringModeCheck() {
        rootChecked = false;
        rootFailStreak = 0;
        lastRootCheckMs = 0;
        lastModeCheckMs = 0;
        cachedMonitoringMode = "";
    }

    void resetBaselines() {
        resetCpuBaseline();
        fpsDetector.reset();
    }

    // ---- Fast FPS loop (Shizuku and root) ----
    private volatile FpsDetector.Result latestFps = null;
    private volatile long latestFpsAtMs = 0;
    private volatile String fpsPackage = "";
    private volatile boolean fpsRunning = false;
    private volatile long lastEnsureMs = 0;
    private Thread fpsThread;


    private final java.util.ArrayList<Double> pendingIntervals = new java.util.ArrayList<>();

    private synchronized void ensureFpsLoop() {
        lastEnsureMs = android.os.SystemClock.elapsedRealtime();
        if (fpsRunning && fpsThread != null && fpsThread.isAlive()) return;
        fpsRunning = true;
        fpsThread = new Thread(() -> {
            while (fpsRunning) {
                try {
                    // Self-stop if sample() hasn't called ensureFpsLoop() for 3 s
                    if (android.os.SystemClock.elapsedRealtime() - lastEnsureMs > 3000) {
                        fpsRunning = false;
                        latestFps = null;
                        return;
                    }

                    String loopMode = lastSamplingMode;
                    if ("ROOT".equals(loopMode)) {
                        // Foreground lookup (dumpsys window) also runs here, off the
                        // sampling thread, on this loop's own su shell.
                        refreshForegroundRootIfDue(fpsRootShell);
                    } else if ("SHIZUKU".equals(loopMode)) {
                        // Same off-thread refresh for Shizuku: the dumpsys round
                        // trip costs ~46 ms and belongs here, not in sample().
                        IOnyxUserService svc = shizukuService;
                        if (svc != null) refreshForegroundShizukuIfDue(svc);
                    }
                    String pkg = fpsPackage;
                    FpsDetector.Shell loopShell = null;
                    if (!pkg.isEmpty()) {
                        if ("SHIZUKU".equals(loopMode)) {
                            IOnyxUserService svc = shizukuService;
                            if (svc != null) loopShell = shizukuFpsShell(svc);
                        } else if ("ROOT".equals(loopMode)) {
                            loopShell = rootFpsShell(fpsRootShell);
                        }
                    }
                    if (loopShell != null) {
                        FpsDetector.Result r = fpsDetector.detect(loopShell, pkg);
                        long now = android.os.SystemClock.elapsedRealtime();
                        if (r.valid()) {
                            if (!r.frameIntervalsMs.isEmpty()) {
                                synchronized (pendingIntervals) {
                                    pendingIntervals.addAll(r.frameIntervalsMs);
                                    if (pendingIntervals.size() > 20000) {
                                        pendingIntervals.subList(0,
                                                pendingIntervals.size() - 20000).clear();
                                    }
                                }
                            }
                            latestFps = r;
                            latestFpsAtMs = now;
                        } else if (now - latestFpsAtMs > 1500) {
                            latestFps = null;
                        }
                    }
                    // Static screen (0 FPS): poll half as often. Rendering resumes within ~0.7 s.
                    FpsDetector.Result last = latestFps;
                    long sleepMs = (last != null && last.fps == 0) ? 600 : 300;
                    Thread.sleep(sleepMs);
                } catch (InterruptedException e) {
                    return;
                } catch (Exception ignored) {
                }
            }
        }, "OnyxFpsLoop");
        fpsThread.setDaemon(true);
        fpsThread.start();
    }

    void stopFpsLoop() {
        fpsRunning = false;
        if (fpsThread != null) fpsThread.interrupt();
    }

    FpsDetector.Result getLatestFps() {
        FpsDetector.Result r = latestFps;
        if (r != null && r.valid()
                && android.os.SystemClock.elapsedRealtime() - latestFpsAtMs < 1500) {
            return r;
        }
        return null;
    }
    private java.util.List<Double> drainIntervals() {
        synchronized (pendingIntervals) {
            java.util.ArrayList<Double> copy = new java.util.ArrayList<>(pendingIntervals);
            pendingIntervals.clear();
            return copy;
        }
    }

    Sample sample(boolean recording) {
        if (frameCaptureStopRequested) {
            fpsDetector.endFrameCapture();
            frameCaptureStopRequested = false;
        }
        if (frameCaptureRequested) {
            fpsDetector.beginFrameCapture(frameCaptureStartNs);
            frameCaptureRequested = false;
        }
        if (!recording && frameCaptureActive) {
            fpsDetector.endFrameCapture();
            frameCaptureActive = false;
        }

        String mode = detectMonitoringMode();

        // CPU counters and FPS frame baselines are source-dependent. Do not
        // calculate across a transition between ROOT, SHIZUKU, and ANDROID.
        if (!mode.equals(lastSamplingMode)) {
            resetBaselines();
            if ("ROOT".equals(mode) || "ROOT".equals(lastSamplingMode)) {
                // Drop FPS results produced under the other source.
                latestFps = null;
                synchronized (pendingIntervals) {
                    pendingIntervals.clear();
                }
            }
            lastSamplingMode = mode;
        }

        Sample sample;

        if ("ROOT".equals(mode)) {
            sample = null;
            if (rootHelperReady) {
                sample = readRootHelperSample(recording);
            } else {
                maybeStartRootHelper();
            }
            // Helper not up yet (or it failed): the shell-script reader still works.
            if (sample == null) sample = readSample(recording);
        } else if ("SHIZUKU".equals(mode)) {
            sample = readShizukuSample(recording);
        } else {
            sample = readAndroidSample(recording);
        }

        if (sample != null) {
            sample.thermalStatus = ThermalStatusReader.read(context);
        }

        if (sample != null) {
            sample.ramUsagePercent = readRamUsagePercent();
        }

        return sample;
    }

    private double readRamUsagePercent() {
        try {
            android.app.ActivityManager manager =
                    (android.app.ActivityManager) context.getSystemService(
                            Context.ACTIVITY_SERVICE);
            if (manager == null) return -1;

            android.app.ActivityManager.MemoryInfo memoryInfo =
                    new android.app.ActivityManager.MemoryInfo();
            manager.getMemoryInfo(memoryInfo);

            if (memoryInfo.totalMem <= 0 || memoryInfo.availMem < 0) return -1;

            double used = Math.max(0,
                    memoryInfo.totalMem - Math.min(memoryInfo.availMem, memoryInfo.totalMem));
            return Math.max(0, Math.min(100, used * 100.0 / memoryInfo.totalMem));
        } catch (RuntimeException ignored) {
            return -1;
        }
    }


    private String readMetricsShellRoot() {
        long scanNow = android.os.SystemClock.elapsedRealtime();
        // Full devfreq discovery forks several processes per node. Once a GPU
        // path has been found it is cached, so only keep scanning every sample
        // if something was found before; otherwise retry every 30 s.
        boolean scanGpu = gpuLoadEverFound || !cachedGpuFreqPath.isEmpty()
                || lastGpuScanMs == 0 || scanNow - lastGpuScanMs >= 30_000;
        if (scanGpu) lastGpuScanMs = scanNow;
        String scanGpuTest = scanGpu ? "[ 1 = 1 ]" : "[ 1 = 0 ]";

        String script =
                "rd() { R=; [ -r \"$1\" ] && { IFS= read -r R < \"$1\"; } 2>/dev/null; }; " +
                        "echo __TEMP__; rd /sys/class/power_supply/battery/temp; echo \"$R\"; " +
                        "echo __BAT__; " +
                        "for P in /sys/class/power_supply/*; do " +
                        " [ -d \"$P\" ] || continue; " +
                        " TYPE=; IFS= read -r TYPE < \"$P/type\" 2>/dev/null; " +
                        " [ \"$TYPE\" = \"Battery\" ] || continue; " +
                        " CURRENT=-; VOLTAGE=-; CAPACITY=-; " +
                        " rd \"$P/current_now\"; [ -n \"$R\" ] && CURRENT=$R; " +
                        " rd \"$P/voltage_now\"; [ -n \"$R\" ] && VOLTAGE=$R; " +
                        " rd \"$P/capacity\"; [ -n \"$R\" ] && CAPACITY=$R; " +
                        " echo \"$CURRENT $VOLTAGE $CAPACITY\"; break; " +
                        "done; " +
                        "echo __CPU__; cat /proc/stat; " +
                        "echo __CPU_FREQ__; " +
                        "for i in 0 1 2 3 4 5 6 7; do " +
                        "F=/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq; V=; " +
                        "rd \"$F\"; V=$R; " +
                        "case \"$V\" in \"\"|*[!0-9]*) V=;; esac; " +
                        "if [ -z \"$V\" ] || [ \"$V\" -le 0 ] 2>/dev/null; then " +
                        " F=/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_cur_freq; V=; " +
                        " rd \"$F\"; V=$R; " +
                        " case \"$V\" in \"\"|*[!0-9]*) V=;; esac; " +
                        "fi; " +
                        "if [ -n \"$V\" ] && [ \"$V\" -gt 0 ] 2>/dev/null; then echo \"$V\"; else echo -1; fi; " +
                        "done; " +
                        "CACHED_FREQ='" + validateSysfsPath(cachedGpuFreqPath) + "'; " +
                        "CACHED_LOAD='" + validateSysfsPath(cachedGpuLoadPath) + "'; " +
                        "CACHED_MALI='" + validateSysfsPath(cachedMaliPath) + "'; " +
                        "GPU_FREQ=-1; GPU_LOAD='-1 -1'; FOUND_FREQ='none'; FOUND_LOAD='none'; " +
                        "MALI_PATH=\"$CACHED_MALI\"; MALI_RETRY=0; " +
                        "if [ -n \"$MALI_PATH\" ] && [ ! -d \"$MALI_PATH\" ]; then MALI_PATH=''; MALI_RETRY=1; fi; " +
                        "if [ -z \"$MALI_PATH\" ] && { " + (!maliSearchCompleted ? "[ 1 = 1 ]" : "[ 1 = 0 ]") + " || [ \"$MALI_RETRY\" = 1 ]; }; then " +
                        " if [ -d /sys/class/misc/mali0/device ]; then MALI_PATH='/sys/class/misc/mali0/device'; " +
                        " else MALI_PATH=$(find /sys/devices/platform -name '*.mali' -type d 2>/dev/null | head -n 1); fi; fi; " +
                        "if [ -r /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq ]; then rd /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq; GPU_FREQ=$R; FOUND_FREQ='/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq'; " +
                        "elif [ -n \"$CACHED_FREQ\" ] && [ -r \"$CACHED_FREQ\" ]; then rd \"$CACHED_FREQ\"; GPU_FREQ=$R; FOUND_FREQ=\"$CACHED_FREQ\"; " +
                        "elif [ -n \"$MALI_PATH\" ]; then " +
                        " for F in \"$MALI_PATH\"/devfreq/cur_freq \"$MALI_PATH\"/devfreq/*/cur_freq; do " +
                        "  if [ -r \"$F\" ]; then rd \"$F\"; GPU_FREQ=$R; FOUND_FREQ=\"$F\"; break; fi; done; " +
                        " if [ \"$FOUND_FREQ\" = 'none' ] && [ -r \"$MALI_PATH/device/clock\" ]; then rd \"$MALI_PATH/device/clock\"; GPU_FREQ=$R; FOUND_FREQ=\"$MALI_PATH/device/clock\"; " +
                        " elif [ \"$FOUND_FREQ\" = 'none' ] && [ -r \"$MALI_PATH/clock\" ]; then rd \"$MALI_PATH/clock\"; GPU_FREQ=$R; FOUND_FREQ=\"$MALI_PATH/clock\"; fi; fi; " +
                        "if [ \"$FOUND_FREQ\" = 'none' ] && " + scanGpuTest + "; then " +
                        " for D in /sys/class/devfreq/*; do " +
                        "  [ -d \"$D\" ] || continue; N=${D##*/}; " +
                        "  if [ -r \"$D/name\" ]; then IFS= read -r N < \"$D/name\"; fi; " +
                        "  N=$(printf '%s' \"$N\" | tr '[:upper:]' '[:lower:]'); " +
                        "  case \"$N\" in " + GpuDevfreqMatcher.shellCasePatterns() + ") ;; *) continue;; esac; " +
                        "  if [ -r \"$D/cur_freq\" ]; then rd \"$D/cur_freq\"; GPU_FREQ=$R; FOUND_FREQ=\"$D/cur_freq\"; break; fi; done; fi; " +
                        "if [ \"$FOUND_FREQ\" = 'none' ] && " + (!maliSearchCompleted ? "[ 1 = 1 ]" : "[ 1 = 0 ]") + "; then " +
                        " for F in $(find /sys/devices -type f \\( -path '*/devfreq/cur_freq' -o -path '*/devfreq/*/cur_freq' \\) -print 2>/dev/null); do " +
                        "  [ -r \"$F\" ] || continue; D=${F%/cur_freq}; N=${D##*/}; " +
                        "  if [ \"$N\" = 'devfreq' ]; then P=${D%/devfreq}; N=${P##*/}; fi; " +
                        "  if [ -r \"$D/name\" ]; then IFS= read -r N < \"$D/name\"; fi; " +
                        "  N=$(printf '%s' \"$N\" | tr '[:upper:]' '[:lower:]'); " +
                        "  case \"$N\" in *busmon*) continue;; esac; " +
                        "  case \"$N\" in " + GpuDevfreqMatcher.shellCasePatterns() + ") ;; *) continue;; esac; " +
                        "  rd \"$F\"; GPU_FREQ=$R; FOUND_FREQ=\"$F\"; break; done; fi; " +
                        "echo __GPU_FREQ_BLOCK__; echo $GPU_FREQ; echo $FOUND_FREQ; " +
                        "GPU_LOAD_CANDIDATES=''; " +
                        "add_gpu_load_candidate() { F=\"$1\"; [ -r \"$F\" ] || return; rd \"$F\"; V=$R; [ -n \"$V\" ] || return; " +
                        " if [ -n \"$GPU_LOAD_CANDIDATES\" ]; then GPU_LOAD_CANDIDATES=\"${GPU_LOAD_CANDIDATES}\n\"; fi; " +
                        " GPU_LOAD_CANDIDATES=\"${GPU_LOAD_CANDIDATES}${V} ${F}\"; }; " +
                        "add_gpu_load_candidate /sys/class/kgsl/kgsl-3d0/gpubusy; " +
                        "for F in /sys/devices/platform/soc/*/kgsl/kgsl-3d0/gpubusy; do add_gpu_load_candidate \"$F\"; done; " +
                        "if [ -n \"$CACHED_LOAD\" ]; then add_gpu_load_candidate \"$CACHED_LOAD\"; fi; " +
                        "if [ -n \"$MALI_PATH\" ]; then " +
                        " for F in \"$MALI_PATH\"/devfreq/load \"$MALI_PATH\"/devfreq/*/load \"$MALI_PATH\"/devfreq/utilisation \"$MALI_PATH\"/devfreq/*/utilisation \"$MALI_PATH\"/devfreq/utilization \"$MALI_PATH\"/devfreq/*/utilization \"$MALI_PATH\"/device/utilisation \"$MALI_PATH\"/device/utilization; do " +
                        "  add_gpu_load_candidate \"$F\"; done; fi; " +
                        "if [ -z \"$CACHED_LOAD\" ] && " + scanGpuTest + "; then for D in /sys/class/devfreq/*; do " +
                        " [ -d \"$D\" ] || continue; N=${D##*/}; " +
                        " if [ -r \"$D/name\" ]; then IFS= read -r N < \"$D/name\"; fi; " +
                        " N=$(printf '%s' \"$N\" | tr '[:upper:]' '[:lower:]'); " +
                        " case \"$N\" in *busmon*) continue;; esac; " +
                        " case \"$N\" in " + GpuDevfreqMatcher.shellCasePatterns() + ") ;; *) continue;; esac; " +
                        " for F in \"$D/load\" \"$D/utilisation\" \"$D/utilization\"; do add_gpu_load_candidate \"$F\"; done; done; fi; " +
                        "echo __GPU_LOAD_BLOCK__; printf '%s\\n' \"$GPU_LOAD_CANDIDATES\"; " +
                        "echo __THERMAL_BLOCK__; " +
                        "CACHE=/data/local/tmp/onyx_thermal_cache; " +
                        "set -- /sys/class/thermal/thermal_zone*; ZONES=$#; " +
                        "scan_zones() { " +
                        " : > \"$CACHE\"; echo \"$ZONES\" >> \"$CACHE\"; " +
                        " for Z in /sys/class/thermal/thermal_zone*; do " +
                        "  T=; IFS= read -r T < \"$Z/type\" 2>/dev/null; " +
                        "  case \"$T\" in cpu|CPU|Cpu|cpu-*|CPU-*|Cpu-*|cpu_*|CPU_*|Cpu_*|*cpu*|*CPU*|mtkts_cpu|MTKTS_CPU) K=CPU;; " +
                        "  gpu|GPU|Gpu|gpu-*|GPU-*|Gpu-*|gpu_*|GPU_*|Gpu_*|*gpu*|*GPU*|gpuss-*|GPUSS-*|mtkts_gpu|MTKTS_GPU) K=GPU;; " +
                        "  *) continue;; esac; " +
                        "  N=${Z##*thermal_zone}; echo \"$K $N\" >> \"$CACHE\"; " +
                        "  V=; IFS= read -r V < \"$Z/temp\" 2>/dev/null; echo \"$K $V\"; " +
                        " done; }; " +
                        "CACHED=; [ -s \"$CACHE\" ] && { IFS= read -r CACHED < \"$CACHE\"; }; " +
                        "if [ -n \"$CACHED\" ] && [ \"$CACHED\" = \"$ZONES\" ]; then " +
                        " HIT=0; while IFS= read -r K N; do " +
                        "  V=; IFS= read -r V < /sys/class/thermal/thermal_zone$N/temp 2>/dev/null; " +
                        "  if [ -n \"$V\" ]; then echo \"$K $V\"; HIT=1; fi; done < \"$CACHE\"; " +
                        " [ \"$HIT\" = 0 ] && { rm -f \"$CACHE\"; scan_zones; }; " +
                        "else " +
                        " scan_zones; " +
                        "fi; " +
                        "echo __COOLING_BLOCK__; " +
                        "for C in /sys/class/thermal/cooling_device*; do " +
                        " [ -d \"$C\" ] || continue; T=; IFS= read -r T < \"$C/type\" 2>/dev/null; " +
                        " case \"$T\" in *cpu*|*gpu*|*cpufreq*|*devfreq*) ;; *) continue;; esac; " +
                        " S=; M=; IFS= read -r S < \"$C/cur_state\" 2>/dev/null; IFS= read -r M < \"$C/max_state\" 2>/dev/null; " +
                        " echo \"$T $S $M\"; done; " +
                        "echo __MALI_BLOCK__; [ -n \"$MALI_PATH\" ] && echo \"$MALI_PATH\" || echo none; " +
                        "echo __END__; ";

        return rootCommand(script);
    }

    /** Zero-fork metrics: the root helper reads sysfs in-process. Null = use the script. */
    private Sample readRootHelperSample(boolean recording) {
        long tStart = android.os.SystemClock.elapsedRealtime();
        rootFgRefreshMs = recording ? 1000 : 1500;
        String app = getForegroundAppRoot();
        long tFg = android.os.SystemClock.elapsedRealtime();

        Sample sample = readAndroidSample(recording);
        if (sample == null) return null;

        RootShell helper = rootHelperShell;
        String freqOutput = helper == null ? null : helper.exec(
                "M\t" + cachedGpuFreqPath + "\t" + cachedGpuLoadPath + "\t"
                        + cachedMaliPath + "\t" + (maliSearchCompleted ? "0" : "1"));
        if (freqOutput == null || !freqOutput.contains("__END__")) {
            rootHelperFailed("no/short reply");
            return null;
        }
        long tMetrics = android.os.SystemClock.elapsedRealtime();

        try {
            java.util.Arrays.fill(sample.cpu, -1);
            java.util.Arrays.fill(sample.cpuFreqMHz, -1);
            sample.gpuFreqMHz = -1;
            sample.gpuLoadPercent = -1;
            applySysfsMetrics(freqOutput, sample);
        } catch (Exception e) {
            rootHelperFailed("parse error");
            return null;
        }

        sample.app = (app == null || app.isEmpty()) ? "Unknown" : app;
        applyCachedRootFps(sample);
        recordRootPerf(tStart, tFg, tMetrics, true);
        return sample;
    }

    private void maybeStartRootHelper() {
        if (rootHelperReady || rootHelperFailures >= 3) return;
        if (android.os.SystemClock.elapsedRealtime() < rootHelperRetryAtMs) return;
        if (!rootHelperStarting.compareAndSet(false, true)) return;

        final String apk = context.getPackageCodePath();
        if (apk == null || !apk.matches("[A-Za-z0-9_./=~+-]+")) {
            rootHelperFailures = 3;
            rootHelperStarting.set(false);
            return;
        }

        Thread starter = new Thread(() -> {
            try {
                RootShell old = rootHelperShell;
                if (old != null) old.close();
                RootShell h = new RootShell("OnyxRootHelperReader",
                        new String[]{"su", "-c", "CLASSPATH='" + apk
                                + "' exec app_process /system/bin"
                                + " --nice-name=onyx_root_helper "
                                + RootMetricsHelper.class.getName()},
                        4_000);
                rootHelperShell = h;
                String pong = h.exec("PING");
                if (pong != null && pong.contains("OK")) {
                    rootHelperFailures = 0;
                    rootHelperReady = true;
                    android.util.Log.d("OnyxRootHelper", "root helper ready");
                } else {
                    rootHelperFailed("no PING reply");
                }
            } catch (Throwable t) {
                rootHelperFailed("start: " + t);
            } finally {
                rootHelperStarting.set(false);
            }
        }, "OnyxRootHelperStart");
        starter.setDaemon(true);
        starter.start();
    }

    private void rootHelperFailed(String why) {
        rootHelperReady = false;
        rootHelperFailures++;
        rootHelperRetryAtMs = android.os.SystemClock.elapsedRealtime() + 60_000;
        android.util.Log.w("OnyxRootHelper", "helper unavailable (" + why
                + "), attempt " + rootHelperFailures + "; using shell reader");
        RootShell h = rootHelperShell;
        if (h != null) h.close();
    }

    private void closeRootHelper() {
        rootHelperReady = false;
        RootShell h = rootHelperShell;
        if (h != null) h.close();
    }

    private Sample readSample(boolean recording) {
        long tStart = android.os.SystemClock.elapsedRealtime();
        rootFgRefreshMs = recording ? 1000 : 1500;
        String app = getForegroundAppRoot();
        if (app == null) app = "Unknown";
        long tFg = android.os.SystemClock.elapsedRealtime();
        String output = readMetricsShellRoot();
        long tMetrics = android.os.SystemClock.elapsedRealtime();
        if (output == null) return null;

        try {
            // Note: __APP__ is no longer in the output string, we use the 'app' variable directly
            String tempRaw = between(output, "__TEMP__\n", "__BAT__").trim();
            int batIndex = output.indexOf("__BAT__");

            Sample sample = new Sample();
            sample.currentMa = -1;
            sample.voltage = -1;
            sample.power = -1;
            sample.temperatureC = -1;
            sample.battery = -1;
            sample.timeMs = Math.max(0, android.os.SystemClock.elapsedRealtime() - startElapsedMs);
            sample.app = app.isEmpty() ? "Unknown" : app;

            applyCachedRootFps(sample);

            try {
                double rawTemp = Double.parseDouble(tempRaw.trim());
                sample.temperatureC = rawTemp > 100 ? rawTemp / 10.0 : rawTemp;
            } catch (Exception ignored) {
                sample.temperatureC = -1;
            }

            java.util.Arrays.fill(sample.cpu, -1);
            java.util.Arrays.fill(sample.cpuFreqMHz, -1);
            sample.gpuFreqMHz = -1;
            sample.gpuLoadPercent = -1;

            if (batIndex >= 0) {
                String bat = between(output, "__BAT__\n", "__CPU__").trim();
                SysfsBatteryValues batteryValues = parseSysfsBatteryValues(bat);

                Intent powerIntent = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
                boolean unplugged = powerIntent != null && powerIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) == 0;
                int status = powerIntent != null ? powerIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) : -1;

                sample.batteryUnplugged = unplugged;
                sample.charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL;
                sample.currentMa = batteryValues.hasCurrent() ? batteryCurrent.toMilliampsFromSysfs(batteryValues.currentMicroamps, unplugged) : -1;
                sample.voltage = batteryValues.hasVoltage() ? batteryValues.voltageMicrovolts / 1000000.0 : -1;
                sample.power = calculatePowerWatts(sample.currentMa, sample.voltage, batteryValues.hasCurrent() && batteryValues.hasVoltage());
                sample.battery = batteryValues.batteryPercent;
            }

            // Never report "-" just because one source failed: fill the gaps from
            // Android's own battery API, which always works.
            if (sample.currentMa == -1 || sample.battery == -1
                    || sample.temperatureC == -1) {
                Sample fallback = readAndroidSample(recording);
                if (fallback != null) {
                    if (sample.currentMa == -1) sample.currentMa = fallback.currentMa;
                    if (sample.voltage < 0) sample.voltage = fallback.voltage;
                    if (sample.battery < 0) sample.battery = fallback.battery;
                    if (sample.temperatureC < 0) sample.temperatureC = fallback.temperatureC;
                    if (!sample.charging) sample.charging = fallback.charging;
                    sample.batteryUnplugged = fallback.batteryUnplugged;
                    sample.power = calculatePowerWatts(sample.currentMa, sample.voltage,
                            sample.currentMa != -1 && sample.voltage > 0);
                }
            }

            String cpu = between(output, "__CPU__\n", "__CPU_FREQ__");
            if (!cpu.isEmpty()) {
                sample.cpu = parseCpu(cpu);
            }

            String cpuFreqRaw = between(output, "__CPU_FREQ__\n", "__GPU_FREQ_BLOCK__").trim();
            parseCpuFrequencies(cpuFreqRaw, sample.cpuFreqMHz);

            String gpuFreqBlock = between(output, "__GPU_FREQ_BLOCK__\n", "__GPU_LOAD_BLOCK__").trim();
            String[] gpuFreqParts = gpuFreqBlock.split("\\s+");
            String gpuFreqPath = gpuFreqParts.length >= 2 ? gpuFreqParts[1] : "";
            sample.gpuFreqMHz = parseGpuFrequencyMHz(gpuFreqBlock, gpuFreqPath);

            if (!gpuFreqPath.equals("none") && !gpuFreqPath.isEmpty()) {
                cachedGpuFreqPath = validateSysfsPath(gpuFreqPath);
            }

            String gpuLoadBlock = between(output, "__GPU_LOAD_BLOCK__\n", "__THERMAL_BLOCK__").trim();
            String selectedGpuLoad = firstValidGpuLoadCandidate(gpuLoadBlock);
            sample.gpuLoadPercent = -1;
            cachedGpuLoadPath = "";

            if (!selectedGpuLoad.isEmpty()) {
                String[] gpuLoadParts = selectedGpuLoad.split("\\s+");
                String gpuLoadPath = gpuLoadParts[gpuLoadParts.length - 1];
                String validatedPath = validateSysfsPath(gpuLoadPath);
                if (!validatedPath.isEmpty()) {
                    sample.gpuLoadPercent = parseGpuLoadPercent(selectedGpuLoad);
                    cachedGpuLoadPath = validatedPath;
                    gpuLoadEverFound = true;
                }
            }

            parseThermalTemperatures(between(output, "__THERMAL_BLOCK__\n", "__COOLING_BLOCK__"), sample);
            applyCoolingReading(between(output, "__COOLING_BLOCK__\n", "__MALI_BLOCK__"), sample);

            String maliBlock = between(output, "__MALI_BLOCK__\n", "__END__").trim();
            if (!maliBlock.isEmpty()) {
                cachedMaliPath = maliBlock.equals("none") ? "" : validateSysfsPath(maliBlock);
            }

            maliSearchCompleted = true;
            recordRootPerf(tStart, tFg, tMetrics, false);
            return sample;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses the marker-delimited reply of the in-process (Java) metrics reader
     * into {@code sample}. Shared by the Shizuku path and the root helper path.
     */
    private void applySysfsMetrics(String freqOutput, Sample sample) {
        String batteryBlock = between(
                freqOutput, "__BATTERY_BLOCK__\n", "__PROC_STAT__"
        ).trim();
        if (!batteryBlock.isEmpty()) {
            SysfsBatteryValues batteryValues =
                    parseSysfsBatteryValues(batteryBlock);
            if (batteryValues.hasCurrent()
                    && (batteryValues.currentMicroamps != 0 || sample.currentMa < 0)) {
                sample.currentMa = batteryCurrent.toMilliampsFromSysfs(
                        batteryValues.currentMicroamps, sample.batteryUnplugged);
            }
            if (batteryValues.hasVoltage()) {
                sample.voltage = batteryValues.voltageMicrovolts / 1000000.0;
            }
            if (batteryValues.batteryPercent >= 0) {
                sample.battery = batteryValues.batteryPercent;
            }
            sample.power = calculatePowerWatts(
                    sample.currentMa, sample.voltage,
                    sample.currentMa != -1 && sample.voltage > 0);
        }

        String cpuOutput = between(
                freqOutput, "__PROC_STAT__\n", "__CPU_FREQ__"
        ).trim();
        if (!cpuOutput.isEmpty()) {
            sample.cpu = parseCpu(cpuOutput);
        }

        String cpuFreqRaw = between(freqOutput, "__CPU_FREQ__\n", "__GPU_FREQ_BLOCK__").trim();
        parseCpuFrequencies(cpuFreqRaw, sample.cpuFreqMHz);

        String gpuFreqBlock = between(
                freqOutput, "__GPU_FREQ_BLOCK__\n", "__GPU_LOAD_BLOCK__"
        ).trim();
        String[] gpuFreqParts = gpuFreqBlock.split("\\s+");
        String gpuFreqPath = gpuFreqParts.length >= 2
                ? gpuFreqParts[1] : "";
        sample.gpuFreqMHz = parseGpuFrequencyMHz(
                gpuFreqBlock, gpuFreqPath);
        if (!gpuFreqPath.equals("none") && !gpuFreqPath.isEmpty()) {
            cachedGpuFreqPath = validateSysfsPath(gpuFreqPath);
        }

        String gpuLoadBlock = between(
                freqOutput, "__GPU_LOAD_BLOCK__\n", "__THERMAL_BLOCK__").trim();
        String selectedGpuLoad = firstValidGpuLoadCandidate(gpuLoadBlock);

        sample.gpuLoadPercent = -1;
        cachedGpuLoadPath = "";

        if (!selectedGpuLoad.isEmpty()) {
            String[] gpuLoadParts = selectedGpuLoad.split("\\s+");
            String gpuLoadPath = gpuLoadParts[gpuLoadParts.length - 1];
            String validatedPath = validateSysfsPath(gpuLoadPath);
            if (!validatedPath.isEmpty()) {
                sample.gpuLoadPercent = parseGpuLoadPercent(selectedGpuLoad);
                cachedGpuLoadPath = validatedPath;
            }
        }

        parseThermalTemperatures(
                between(freqOutput, "__THERMAL_BLOCK__\n", "__COOLING_BLOCK__"), sample);
        applyCoolingReading(
                between(freqOutput, "__COOLING_BLOCK__\n", "__MALI_BLOCK__"), sample);

        String maliBlock = between(freqOutput, "__MALI_BLOCK__\n", "__END__").trim();
        if (!maliBlock.isEmpty()) {
            cachedMaliPath = maliBlock.equals("none")
                    ? ""
                    : validateSysfsPath(maliBlock);
        }
        maliSearchCompleted = true;
    }

    private Sample readShizukuSample(boolean recording) {
        long tStart = android.os.SystemClock.elapsedRealtime();
        Sample sample = readAndroidSample(recording);
        if (sample == null) return null;

        IOnyxUserService service = shizukuService;
        if (service == null) {
            clearFps(sample);
            fpsDetector.reset();
            fpsPackage = "";
            latestFps = null;
            return sample;
        }

        try {
            long now = android.os.SystemClock.elapsedRealtime();

            try {
                java.util.Arrays.fill(sample.cpu, -1);
                java.util.Arrays.fill(sample.cpuFreqMHz, -1);
                sample.gpuFreqMHz = -1;
                sample.gpuLoadPercent = -1;

                String freqOutput = service.readMetrics(
                        cachedGpuFreqPath, cachedGpuLoadPath, cachedMaliPath,
                        !maliSearchCompleted);

                if (freqOutput != null) {
                    applySysfsMetrics(freqOutput, sample);
                }
            } catch (Exception ignored) {
                // Keep metrics unavailable if the command fails
            }
            long tMetrics = android.os.SystemClock.elapsedRealtime();

            // Foreground package: normally kept fresh by the FPS loop, so the
            // sampling thread does not pay a dumpsys round trip (~46 ms). Only
            // look it up here on the first sample, or if the cache went stale.
            shizukuFgRefreshMs = recording ? 1000 : 1500;
            if (cachedForegroundPackage.isEmpty()
                    || now - lastForegroundCheckMs >= 5000) {
                String foundPackage = lookupForegroundShizuku(service);
                if (!foundPackage.isEmpty()) {
                    cachedForegroundPackage = foundPackage;
                    lastForegroundCheckMs = now;
                }
            }

            long tFg = android.os.SystemClock.elapsedRealtime();
            recordShizukuPerf(tStart, tMetrics, tFg);


            String foregroundPackage = cachedForegroundPackage;
            if (foregroundPackage.isEmpty()) {
                clearFps(sample);
                fpsDetector.reset();
                fpsPackage = "";
                latestFps = null;
                return sample;
            }

            sample.app = foregroundPackage;
            fpsPackage = foregroundPackage;
            ensureFpsLoop();

            FpsDetector.Result r = getLatestFps();
            if (r != null && r.valid()) {
                sample.fps = r.fps;
                sample.fpsSource = r.source;
                sample.averageFrameTimeMs = r.averageFrameTimeMs;
                sample.frameTimeStdDevMs = r.frameTimeStdDevMs;
                sample.stutterCount = r.stutterCount;
                sample.frameRefreshPeriodMs = r.frameRefreshPeriodMs;
                sample.frameIntervalsMs.addAll(drainIntervals());
            } else {
                clearFps(sample);
            }

        } catch (Exception e) {
            clearFps(sample);
        }
        return sample;
    }

    private Sample readAndroidSample(boolean recording) {
        try {
            Intent batteryIntent = context.registerReceiver(
                    null,
                    new IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            );

            if (batteryIntent == null) return null;

            Sample sample = new Sample();

            sample.timeMs = Math.max(0,
                    android.os.SystemClock.elapsedRealtime() - startElapsedMs);
            sample.app = "Unknown";
            sample.fps = -1;
            sample.fpsSource = "none";

            int temp = batteryIntent.getIntExtra(
                    BatteryManager.EXTRA_TEMPERATURE, -1);
            sample.temperatureC = temp >= 0 ? temp / 10.0 : -1;

            // Explicitly initialize all arrays/metrics to -1
            java.util.Arrays.fill(sample.cpu, -1);
            java.util.Arrays.fill(sample.cpuFreqMHz, -1);
            sample.gpuFreqMHz = -1;
            sample.gpuLoadPercent = -1;

            BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);

            int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            sample.battery = (level >= 0 && scale > 0) ? Math.round(level * 100f / scale) : -1;

            int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            sample.charging =
                    status == BatteryManager.BATTERY_STATUS_CHARGING
                            || status == BatteryManager.BATTERY_STATUS_FULL;

            int voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            sample.voltage = voltage >= 0 ? voltage / 1000.0 : -1;

            long current = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            sample.batteryUnplugged = batteryIntent.getIntExtra(
                    BatteryManager.EXTRA_PLUGGED, -1) == 0;
            boolean unplugged = sample.batteryUnplugged;
            boolean currentValid = current != Long.MIN_VALUE;

            sample.currentMa = currentValid ? batteryCurrent.toMilliamps(current, unplugged) : -1;
            sample.power = calculatePowerWatts(sample.currentMa, sample.voltage, currentValid);

            return sample;

        } catch (Exception e) {
            return null;
        }
    }

    private static final String ROOT_FOREGROUND_SCRIPT =
            "dumpsys window 2>/dev/null | grep -m 1 'mCurrentFocus=Window'"
                    + " | sed 's/.* u[0-9]* //; s#/.*##'";

    /**
     * Normally just returns the package kept fresh by the background loop, so the
     * sampling thread never waits on dumpsys. It only does the lookup itself for
     * the very first sample, or if the loop has not refreshed it for a while.
     */
    private String getForegroundAppRoot() {
        long now = android.os.SystemClock.elapsedRealtime();
        String cached = cachedForegroundPackage;

        if (!cached.isEmpty() && now - lastForegroundCheckMs < 5000) {
            return cached;
        }

        refreshForegroundRoot(mainRootShell);

        cached = cachedForegroundPackage;
        // Keep the last known app rather than flashing "Unknown".
        return cached.isEmpty() ? "Unknown" : cached;
    }

    private void refreshForegroundRootIfDue(RootShell sh) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cachedForegroundPackage.isEmpty()
                || now - lastForegroundCheckMs >= rootFgRefreshMs) {
            refreshForegroundRoot(sh);
            String pkg = cachedForegroundPackage;
            if (!pkg.isEmpty()) fpsPackage = pkg;
        }
    }

    private void refreshForegroundRoot(RootShell sh) {
        String out = sh.exec(ROOT_FOREGROUND_SCRIPT);
        if (out != null) {
            String app = out.trim();
            if (!app.isEmpty()) {
                cachedForegroundPackage = app;
                lastForegroundCheckMs = android.os.SystemClock.elapsedRealtime();
            }
        }
    }

    private void refreshForegroundShizukuIfDue(IOnyxUserService service) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (cachedForegroundPackage.isEmpty()
                || now - lastForegroundCheckMs >= shizukuFgRefreshMs) {
            String pkg = lookupForegroundShizuku(service);
            if (!pkg.isEmpty()) {
                cachedForegroundPackage = pkg;
                lastForegroundCheckMs = now;
                fpsPackage = pkg;
            }
        }
    }

    /** One dumpsys round trip: resumed activity, then window focus as fallback. */
    private String lookupForegroundShizuku(IOnyxUserService service) {
        try {
            String foregroundOutput = service.getResumedActivityDump();
            if (foregroundOutput == null) foregroundOutput = "";

            java.util.regex.Matcher matcher =
                    COMPONENT_PATTERN.matcher(foregroundOutput);
            String found = matcher.find() ? matcher.group(1) : "";

            if (found.isEmpty()) {
                String windowOutput = service.getWindowDump();
                if (windowOutput != null) {
                    java.util.regex.Matcher windowMatcher =
                            COMPONENT_PATTERN.matcher(windowOutput);
                    if (windowMatcher.find()) found = windowMatcher.group(1);
                }
            }
            return found;
        } catch (Exception e) {
            return "";
        }
    }


    /**
     * FPS is produced by the background OnyxFpsLoop (own su shell); the sampling
     * thread only reads the latest result and drains the new frame intervals
     * exactly once, like the Shizuku path.
     */
    private void applyCachedRootFps(Sample sample) {
        if ("Unknown".equals(sample.app)) {
            fpsPackage = "";
            fpsDetector.reset();
            latestFps = null;
        } else {
            fpsPackage = sample.app;
            ensureFpsLoop();
        }
        FpsDetector.Result r = getLatestFps();

        if (r != null && r.valid()) {
            sample.fps = r.fps;
            sample.fpsSource = r.source;
            sample.averageFrameTimeMs = r.averageFrameTimeMs;
            sample.frameTimeStdDevMs = r.frameTimeStdDevMs;
            sample.stutterCount = r.stutterCount;
            sample.frameRefreshPeriodMs = r.frameRefreshPeriodMs;
            sample.frameIntervalsMs.addAll(drainIntervals());
        } else {
            sample.fps = -1;
            sample.fpsSource = "none";
            sample.averageFrameTimeMs = -1;
            sample.frameTimeStdDevMs = -1;
            sample.stutterCount = -1;
        }
    }

    private void recordRootPerf(long tStart, long tFg, long tMetrics, boolean viaHelper) {
        long end = android.os.SystemClock.elapsedRealtime();
        perfFgMs += tFg - tStart;
        perfMetricsMs += tMetrics - tFg;
        perfTotalMs += end - tStart;
        if (++perfCount >= 20) {
            android.util.Log.d("OnyxRootPerf", (viaHelper ? "[helper] " : "[script] ")
                    + "avg over " + perfCount
                    + " samples: foreground=" + (perfFgMs / perfCount)
                    + "ms metrics=" + (perfMetricsMs / perfCount)
                    + "ms total=" + (perfTotalMs / perfCount) + "ms");
            perfCount = 0;
            perfFgMs = 0;
            perfMetricsMs = 0;
            perfTotalMs = 0;
        }
    }

    private void recordShizukuPerf(long tStart, long tMetrics, long tFg) {
        long end = android.os.SystemClock.elapsedRealtime();
        szPerfMetricsMs += tMetrics - tStart;
        szPerfFgMs += tFg - tMetrics;
        szPerfTotalMs += end - tStart;
        if (++szPerfCount >= 20) {
            android.util.Log.d("OnyxShizukuPerf", "avg over " + szPerfCount
                    + " samples: metrics=" + (szPerfMetricsMs / szPerfCount)
                    + "ms foreground=" + (szPerfFgMs / szPerfCount)
                    + "ms total=" + (szPerfTotalMs / szPerfCount) + "ms");
            szPerfCount = 0;
            szPerfMetricsMs = 0;
            szPerfFgMs = 0;
            szPerfTotalMs = 0;
        }
    }


    private double[] parseCpu(String raw) {
        return cpuUsageTracker.parse(raw);
    }

    static final class SysfsBatteryValues {
        final long currentMicroamps;
        final long voltageMicrovolts;
        final int batteryPercent;

        SysfsBatteryValues(long currentMicroamps, long voltageMicrovolts, int batteryPercent) {
            this.currentMicroamps = currentMicroamps;
            this.voltageMicrovolts = voltageMicrovolts;
            this.batteryPercent = batteryPercent;
        }

        boolean hasCurrent() {
            return currentMicroamps != Long.MIN_VALUE;
        }

        boolean hasVoltage() {
            return voltageMicrovolts > 0;
        }
    }

    /**
     * Parses current, voltage, and capacity independently so one malformed
     * sysfs field cannot discard the other valid battery readings.
     */
    static SysfsBatteryValues parseSysfsBatteryValues(String raw) {
        long current = Long.MIN_VALUE;
        long voltage = -1;
        int percent = -1;
        if (raw == null || raw.trim().isEmpty()) {
            return new SysfsBatteryValues(current, voltage, percent);
        }

        String[] values = raw.trim().split("\\s+");
        if (values.length > 0 && !"-".equals(values[0])) {
            try {
                long parsed = Long.parseLong(values[0]);
                if (parsed != Long.MIN_VALUE) current = parsed;
            } catch (NumberFormatException ignored) {
                // Keep only the current unavailable.
            }
        }
        if (values.length > 1 && !"-".equals(values[1])) {
            try {
                long parsed = Long.parseLong(values[1]);
                if (parsed > 0) voltage = parsed;
            } catch (NumberFormatException ignored) {
                // Keep only the voltage unavailable.
            }
        }
        if (values.length > 2 && !"-".equals(values[2])) {
            try {
                int parsed = Integer.parseInt(values[2]);
                if (parsed >= 0 && parsed <= 100) percent = parsed;
            } catch (NumberFormatException ignored) {
                // Keep only the battery percentage unavailable.
            }
        }
        return new SysfsBatteryValues(current, voltage, percent);
    }

    static double calculatePowerWatts(
            double currentMa, double voltageV, boolean currentValid) {
        if (!currentValid || !Double.isFinite(currentMa)
                || !Double.isFinite(voltageV) || voltageV <= 0) return -1;
        return currentMa * voltageV / 1000.0;
    }

    static void applyCoolingReading(String raw, Sample sample) {
        if (sample == null) return;
        ThermalCoolingReader.Reading reading = ThermalCoolingReader.read(raw);
        sample.coolingDeviceActive = reading.overallState();
        sample.cpuCoolingState = reading.cpuState;
        sample.cpuCoolingMaxState = reading.cpuMaxState;
        sample.gpuCoolingState = reading.gpuState;
        sample.gpuCoolingMaxState = reading.gpuMaxState;
    }

    static void parseThermalTemperatures(String raw, Sample sample) {
        if (raw == null || raw.isEmpty() || sample == null) return;
        double maxCpu = -1;
        double maxGpu = -1;
        for (String line : raw.split("\\r?\\n")) {
            String[] parts = line.trim().split("\\s+");
            if (parts.length != 2) continue;
            try {
                double milliCelsius = Double.parseDouble(parts[1]);
                if (!Double.isFinite(milliCelsius)
                        || milliCelsius <= 0 || milliCelsius > 150000) continue;
                double celsius = milliCelsius / 1000.0;
                if ("CPU".equals(parts[0])) maxCpu = Math.max(maxCpu, celsius);
                else if ("GPU".equals(parts[0])) maxGpu = Math.max(maxGpu, celsius);
            } catch (NumberFormatException ignored) {
                // Ignore unreadable or vendor sentinel values.
            }
        }
        sample.cpuTemperatureC = maxCpu;
        sample.gpuTemperatureC = maxGpu;
    }

    /**
     * Parses per-core scaling_cur_freq values. Linux cpufreq sysfs reports kHz;
     * the app stores MHz. Missing, malformed, non-finite, and non-positive
     * readings remain unavailable (-1), without shifting later core indices.
     */
    static void parseCpuFrequencies(String raw, double[] destination) {
        if (destination == null) return;
        java.util.Arrays.fill(destination, -1);
        if (raw == null || raw.trim().isEmpty()) return;

        String[] values = raw.trim().split("\\s+");
        for (int i = 0; i < destination.length && i < values.length; i++) {
            try {
                double khz = Double.parseDouble(values[i]);
                if (Double.isFinite(khz) && khz > 0) {
                    destination[i] = khz / 1000.0;
                }
            } catch (NumberFormatException ignored) {
                // Keep this core unavailable; preserve later core indices.
            }
        }
    }

    static double parseGpuFrequencyMHz(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;

        String[] parts = raw.trim().split("\\s+");
        try {
            double value = Double.parseDouble(parts[0]);
            if (!Double.isFinite(value) || value <= 0) return -1;
            if (value > 100000) return value / 1000000.0;
            if (value > 100) return value / 1000.0;
            return value;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    static double parseGpuFrequencyMHz(String raw, String sourcePath) {
        if (raw == null || raw.trim().isEmpty()) return -1;

        if (sourcePath != null
                && sourcePath.startsWith("/sys/")
                && sourcePath.contains("/devfreq/")
                && sourcePath.endsWith("/cur_freq")) {

            String[] parts = raw.trim().split("\\s+");

            // Accept a frequency alone or a frequency followed by its source path.
            if (parts.length < 1 || parts.length > 2) return -1;

            if (parts.length == 2 && !parts[1].equals(sourcePath)) {
                return -1;
            }

            try {
                double hz = Double.parseDouble(parts[0]);

                if (!Double.isFinite(hz) || hz <= 0) return -1;

                return hz / 1_000_000.0;

            } catch (NumberFormatException ignored) {
                return -1;
            }
        }

        return parseGpuFrequencyMHz(raw);
    }





    static double parseGpuLoadPercent(String raw) {
        if (raw == null || raw.trim().isEmpty()) return -1;

        String[] parts = raw.trim().split("\\s+");

        try {
            if (parts.length == 3 && "%".equals(parts[1])) {
                // Direct percentage followed by a separate percent symbol and source path.
                double load = Double.parseDouble(parts[0]);
                if (Double.isFinite(load) && load >= 0 && load <= 100) {
                    return load;
                }
            } else if (parts.length == 3) {
                // Busy/total counters followed by source path.
                double busy = Double.parseDouble(parts[0]);
                double total = Double.parseDouble(parts[1]);

                if (!Double.isFinite(busy) || !Double.isFinite(total) || busy < 0) {
                    return -1;
                }

                if (total == -1 && busy <= 100) return busy;
                if (total > 0) return Math.max(0, Math.min(100, busy / total * 100.0));

            } else if (parts.length == 2) {
                // Direct percentage followed by source path.
                String value = parts[0];
                if (value.endsWith("%")) {
                    value = value.substring(0, value.length() - 1);
                }

                double load = Double.parseDouble(value);
                if (Double.isFinite(load) && load >= 0 && load <= 100) {
                    return load;
                }

            } else if (parts.length == 1 && parts[0].endsWith("%")) {
                // Standalone percentage with an explicit percent symbol.
                double load = Double.parseDouble(
                        parts[0].substring(0, parts[0].length() - 1));

                if (Double.isFinite(load) && load >= 0 && load <= 100) {
                    return load;
                }
            }
        } catch (NumberFormatException ignored) {
            // Unsupported or malformed driver output.
        }

        return -1;
    }

    static String firstValidGpuLoadCandidate(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }

        for (String line : raw.split("\\R")) {
            String candidate = line.trim();

            if (candidate.isEmpty()) {
                continue;
            }

            String[] parts = candidate.split("\\s+");

            // A candidate must contain its reading and its sysfs source path.
            if (parts.length < 2) {
                continue;
            }

            String path = parts[parts.length - 1];

            if (!path.startsWith("/sys/")) {
                continue;
            }

            if (parseGpuLoadPercent(candidate) >= 0) {
                return candidate;
            }
        }

        return "";
    }

    private String validateSysfsPath(String path) {
        if (path == null || path.isEmpty() ||
                !path.startsWith("/sys/") ||
                !path.matches("[A-Za-z0-9_./,-]+") ||
                path.contains("..")) {
            return "";
        }
        return path;
    }

    private String between(String value, String start, String end) {
        int x = value.indexOf(start);
        if (x < 0) return "";

        x += start.length();
        int y = value.indexOf(end, x);

        return y < 0 ? "" : value.substring(x, y);
    }

    private FpsDetector.Shell rootFpsShell(final RootShell sh) {
        return new FpsDetector.Shell() {
            @Override public String surfaceFlingerDump() {
                String out = sh.exec("dumpsys SurfaceFlinger 2>/dev/null");
                return out == null ? "" : out;
            }
            @Override public String surfaceFlingerLayers() {
                String out = sh.exec("dumpsys SurfaceFlinger --list 2>/dev/null | grep -i SurfaceView");
                return out == null ? "" : out;
            }
            @Override public String surfaceFlingerLatency(String layerName) {
                String out = sh.exec("dumpsys SurfaceFlinger --latency " + shellQuote(layerName));
                return out == null ? "" : out;
            }
            @Override public String surfaceFlingerLayerLatencySnapshot(
                    String packageName, boolean blastOnly) {
                String out = sh.exec(
                        FpsDetector.buildSurfaceFlingerLayerLatencyScript(
                                packageName, blastOnly));
                return out == null ? "" : out;
            }
            @Override public String surfaceFlingerTimeStatsDump() {
                String out = sh.exec(
                        "dumpsys SurfaceFlinger --timestats -maxlayers 64 -dump 2>/dev/null");
                return out == null ? "" : out;
            }
            @Override public String surfaceFlingerTimeStatsSnapshot() {
                String out = sh.exec(
                        "echo __ONYX_LAYERS__; " +
                                "dumpsys SurfaceFlinger --list 2>/dev/null; " +
                                "echo __ONYX_TIMESTATS__; " +
                                "dumpsys SurfaceFlinger --timestats -maxlayers 64 -dump 2>/dev/null");
                return out == null ? "" : out;
            }
        };
    }

    private FpsDetector.Shell shizukuFpsShell(IOnyxUserService service) {
        return new FpsDetector.Shell() {
            @Override public String surfaceFlingerDump() throws Exception {
                return service.getSurfaceFlingerDump();
            }
            @Override public String surfaceFlingerLayers() throws Exception {
                return service.getSurfaceFlingerLayers();
            }
            @Override public String surfaceFlingerLatency(String layerName) throws Exception {
                return service.getSurfaceFlingerLatency(layerName);
            }
            @Override public String surfaceFlingerLayerLatencySnapshot(
                    String packageName, boolean blastOnly) throws Exception {
                return service.getSurfaceFlingerLayerLatencySnapshot(
                        packageName, blastOnly);
            }
            @Override public String surfaceFlingerTimeStatsDump() throws Exception {
                return service.getSurfaceFlingerTimeStatsDump();
            }
            @Override public String surfaceFlingerTimeStatsSnapshot() throws Exception {
                return service.getSurfaceFlingerTimeStatsSnapshot();
            }
        };
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private void clearFps(Sample sample) {
        sample.fps = -1;
        sample.fpsSource = "none";
        sample.averageFrameTimeMs = -1;
        sample.frameTimeStdDevMs = -1;
        sample.stutterCount = -1;
        sample.frameRefreshPeriodMs = -1;
    }

    private static final int ROOT_OUTPUT_LIMIT = 2_000_000;
    // The layer list is small (a few hundred bytes per layer) but must never be
    // truncated: it decides which FPS source is used, and a cut-off list
    // silently hides the game's layer.
    private static final int ROOT_LAYER_LIST_LIMIT = 8_000_000;
    private static final long ROOT_COMMAND_TIMEOUT_MS = 8_000;
    private static final String ROOT_SHELL_EOF = "__ONYX_ROOT_SHELL_EOF__";

    // Two independent su shells so slow FPS dumpsys calls never block the
    // metrics read (and vice versa).
    private final RootShell mainRootShell = new RootShell("OnyxRootShellReader");
    private final RootShell fpsRootShell = new RootShell("OnyxRootFpsShellReader");

    private String rootCommand(String command) {
        return mainRootShell.exec(command);
    }

    void closeRootShell() {
        mainRootShell.close();
        fpsRootShell.close();
        closeRootHelper();
    }

    /**
     * One reusable authorized su shell for successive commands.
     * Unique end markers keep each command's output isolated.
     */
    private static final class RootShell {
        private final String readerName;
        private final Object lock = new Object();
        private Process process;
        private java.io.BufferedWriter writer;
        private java.util.concurrent.BlockingQueue<String> lines;
        private Thread reader;
        private long commandId;

        private final String[] launchCommand;   // null = plain interactive "su"
        private final long timeoutMs;

        RootShell(String readerName) {
            this(readerName, null, ROOT_COMMAND_TIMEOUT_MS);
        }

        RootShell(String readerName, String[] launchCommand, long timeoutMs) {
            this.readerName = readerName;
            this.launchCommand = launchCommand;
            this.timeoutMs = timeoutMs;
        }

        String exec(String command) {
            if (command == null || command.isEmpty()) return null;

            synchronized (lock) {
                try {
                    ensureLocked();

                    long id = ++commandId;
                    String marker;
                    if (launchCommand != null) {
                        // Helper protocol: one request line, reply ends with its own marker.
                        marker = RootMetricsHelper.END_MARKER;
                        writer.write(command);
                        writer.newLine();
                    } else {
                        marker = "__ONYX_ROOT_DONE_" + id + "__";
                        writer.write(command);
                        writer.newLine();
                        writer.write("printf '\\n%s\\n' '" + marker + "'");
                        writer.newLine();
                    }
                    writer.flush();

                    StringBuilder output = new StringBuilder();
                    long deadline = android.os.SystemClock.elapsedRealtime()
                            + timeoutMs;
                    boolean outputLimitReached = false;

                    while (true) {
                        long remaining = deadline
                                - android.os.SystemClock.elapsedRealtime();
                        if (remaining <= 0) {
                            throw new java.util.concurrent.TimeoutException(
                                    "Root command timed out");
                        }

                        String line = lines.poll(
                                remaining, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (line == null) {
                            throw new java.util.concurrent.TimeoutException(
                                    "Root shell stopped responding");
                        }
                        if (ROOT_SHELL_EOF.equals(line)) {
                            throw new java.io.IOException("Root shell exited");
                        }
                        if (marker.equals(line)) {
                            if (outputLimitReached) {
                                return null;
                            }
                            return output.toString();
                        }

                        if (!outputLimitReached) {
                            int needed = line.length() + 1;
                            if (output.length() + needed > ROOT_OUTPUT_LIMIT) {
                                outputLimitReached = true;
                            } else {
                                output.append(line).append('\n');
                            }
                        }
                    }
                } catch (Exception ignored) {
                    destroyLocked();
                    return null;
                }
            }
        }

        void close() {
            synchronized (lock) {
                destroyLocked();
            }
        }

        private void ensureLocked() throws Exception {
            if (process != null && writer != null
                    && lines != null && reader != null
                    && reader.isAlive()) {
                return;
            }

            destroyLocked();
            Process p = new ProcessBuilder(
                    launchCommand != null ? launchCommand : new String[]{"su"})
                    .redirectErrorStream(true)
                    .start();
            final java.util.concurrent.BlockingQueue<String> q =
                    new java.util.concurrent.ArrayBlockingQueue<>(8192);
            final java.io.BufferedReader r = new java.io.BufferedReader(
                    new InputStreamReader(p.getInputStream(),
                            StandardCharsets.UTF_8));

            Thread readerThread = new Thread(() -> {
                try {
                    String line;
                    while ((line = r.readLine()) != null) {
                        q.put(line);
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                } finally {
                    q.offer(ROOT_SHELL_EOF);
                }
            }, readerName);
            readerThread.setDaemon(true);
            readerThread.start();

            process = p;
            writer = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(
                            p.getOutputStream(), StandardCharsets.UTF_8));
            lines = q;
            reader = readerThread;
        }

        private void destroyLocked() {
            if (writer != null) {
                try {
                    writer.write("exit");
                    writer.newLine();
                    writer.flush();
                } catch (Exception ignored) {
                }
                try {
                    writer.close();
                } catch (Exception ignored) {
                }
            }
            if (process != null) {
                try {
                    process.destroy();
                    if (!process.waitFor(
                            500, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (Exception ignored) {
                    process.destroyForcibly();
                }
            }
            if (reader != null) {
                reader.interrupt();
            }
            process = null;
            writer = null;
            lines = null;
            reader = null;
        }
    }

    private boolean checkRootAccess() {
        try {
            Process process = Runtime.getRuntime().exec(
                    new String[]{"su", "-c", "id"});

            boolean finished = process.waitFor(
                    2, java.util.concurrent.TimeUnit.SECONDS);

            if (finished) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));
                String output = reader.readLine();
                reader.close();

                boolean ok = output != null && output.contains("uid=0");
                return ok;
            }

            process.destroyForcibly();
            process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS);

        } catch (Exception ignored) { }

        return false;
    }

    private String detectMonitoringMode() {
        long now = android.os.SystemClock.elapsedRealtime();

        // The very first sample must not guess: check root inline once, so the
        // app doesn't show empty metrics for the first half minute.
        if (!rootChecked && rootProbeInProgress.compareAndSet(false, true)) {
            try {
                boolean hasRoot = checkRootAccess();
                cachedRoot = hasRoot;
                rootFailStreak = hasRoot ? 0 : rootFailStreak + 1;
                lastRootCheckMs = android.os.SystemClock.elapsedRealtime();
                rootChecked = true;
            } finally {
                rootProbeInProgress.set(false);
            }
        }

        // Rooted: re-verify every 30 s. Not rooted: retry every 5 s a few times
        // (covers tapping Allow on a prompt), then only once a minute.
        long interval = cachedRoot ? 30_000
                : (rootFailStreak < 3 ? 1_000 : 60_000);

        if ((!rootChecked || now - lastRootCheckMs >= interval)
                && rootProbeInProgress.compareAndSet(false, true)) {
            Thread probe = new Thread(() -> {
                try {
                    boolean hasRoot = checkRootAccess();
                    cachedRoot = hasRoot;
                    rootFailStreak = hasRoot ? 0 : rootFailStreak + 1;
                    lastRootCheckMs = android.os.SystemClock.elapsedRealtime();
                    rootChecked = true;
                } finally {
                    rootProbeInProgress.set(false);
                }
            }, "OnyxRootProbe");
            probe.setDaemon(true);
            probe.start();
        }

        String mode;
        if (cachedRoot) {
            mode = "ROOT";
        } else if (Shizuku.pingBinder()
                && Shizuku.checkSelfPermission()
                == PackageManager.PERMISSION_GRANTED) {
            mode = "SHIZUKU";
        } else {
            mode = "ANDROID";
        }

        cachedMonitoringMode = mode;
        lastModeCheckMs = now;
        if (!"ROOT".equals(mode)) {
            closeRootShell();
        }
        return mode;
    }
}