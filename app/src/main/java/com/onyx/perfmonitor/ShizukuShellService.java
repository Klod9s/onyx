package com.onyx.perfmonitor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;

import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

public class ShizukuShellService extends IOnyxUserService.Stub {
    // Keep Binder replies comfortably below the transaction-size limit.
    private static final int MAX_OUTPUT_CHARS = 256 * 1024;
    private static final int MAX_COMPONENT_OUTPUT_CHARS = 8 * 1024;
    private static final long COMMAND_TIMEOUT_SECONDS = 15;

    @Override
    public String readMetrics(String cachedGpuFreqPath, String cachedGpuLoadPath,
                              String cachedMaliPath, boolean searchMali)
            throws RemoteException {
        String freqPath = safeSysfsPath(cachedGpuFreqPath);
        String loadPath = safeSysfsPath(cachedGpuLoadPath);

        // Fast path only once GPU nodes are known; otherwise let the shell discover them.
        if (!searchMali && !freqPath.isEmpty() && !loadPath.isEmpty()) {
            String fast = readMetricsJava(freqPath, loadPath, safeSysfsPath(cachedMaliPath));
            if (fast != null) return fast;
        }
        return readMetricsShell(cachedGpuFreqPath, cachedGpuLoadPath,
                cachedMaliPath, searchMali);
    }

    private static String readFirstLine(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static String readWhole(String path) {
        try (FileInputStream in = new FileInputStream(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    /** Same marker format as the shell script. Returns null if the GPU nodes can't be read. */
    private static String readMetricsJava(String freqPath, String loadPath, String maliPath) {
        String gpuFreq = readFirstLine(freqPath);
        String gpuLoad = readFirstLine(loadPath);
        if (gpuFreq.isEmpty() || gpuLoad.isEmpty()) return null;

        StringBuilder sb = new StringBuilder(8192);

        // Battery
        sb.append("__BATTERY_BLOCK__\n");
        File[] supplies = new File("/sys/class/power_supply").listFiles();
        if (supplies != null) {
            java.util.Arrays.sort(supplies);
            for (File p : supplies) {
                if (!"Battery".equals(readFirstLine(p.getPath() + "/type"))) continue;
                String cur = readFirstLine(p.getPath() + "/current_now");
                String vol = readFirstLine(p.getPath() + "/voltage_now");
                String cap = readFirstLine(p.getPath() + "/capacity");
                sb.append(cur.isEmpty() ? "-" : cur).append(' ')
                        .append(vol.isEmpty() ? "-" : vol).append(' ')
                        .append(cap.isEmpty() ? "-" : cap).append('\n');
                break;
            }
        }

        // CPU usage counters
        sb.append("__PROC_STAT__\n");
        String stat = readWhole("/proc/stat");
        sb.append(stat);
        if (!stat.endsWith("\n")) sb.append('\n');

        // CPU frequencies
        sb.append("__CPU_FREQ__\n");
        for (int i = 0; i < 8; i++) {
            String v = readFirstLine(
                    "/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
            boolean ok = v.matches("[0-9]+") && !v.matches("0+");
            sb.append(ok ? v : "-1").append('\n');
        }

        // GPU
        sb.append("__GPU_FREQ_BLOCK__\n")
                .append(gpuFreq).append('\n').append(freqPath).append('\n');
        sb.append("__GPU_LOAD_BLOCK__\n")
                .append(gpuLoad).append(' ').append(loadPath).append('\n');

        // Thermal zones
        sb.append("__THERMAL_BLOCK__\n");
        File[] zones = new File("/sys/class/thermal")
                .listFiles((d, n) -> n.startsWith("thermal_zone"));
        if (zones != null) {
            for (File z : zones) {
                String type = readFirstLine(z.getPath() + "/type").toLowerCase(java.util.Locale.ROOT);
                String kind;
                if (type.contains("cpu")) kind = "CPU";
                else if (type.contains("gpu")) kind = "GPU";
                else continue;
                String temp = readFirstLine(z.getPath() + "/temp");
                if (temp.isEmpty()) continue;
                sb.append(kind).append(' ').append(temp).append('\n');
            }
        }

        // Cooling devices
        sb.append("__COOLING_BLOCK__\n");
        File[] cools = new File("/sys/class/thermal")
                .listFiles((d, n) -> n.startsWith("cooling_device"));
        if (cools != null) {
            for (File c : cools) {
                String type = readFirstLine(c.getPath() + "/type");
                if (!(type.contains("cpu") || type.contains("gpu")
                        || type.contains("devfreq"))) continue;
                String s = readFirstLine(c.getPath() + "/cur_state");
                String m = readFirstLine(c.getPath() + "/max_state");
                sb.append(type).append(' ').append(s).append(' ').append(m).append('\n');
            }
        }

        sb.append("__MALI_BLOCK__\n")
                .append(maliPath.isEmpty() ? "none" : maliPath).append('\n');
        sb.append("__END__\n");
        return sb.toString();
    }

    private String readMetricsShell(String cachedGpuFreqPath, String cachedGpuLoadPath,
                                    String cachedMaliPath, boolean searchMali) {
        String script =         "echo __BATTERY_BLOCK__; " +
                                "for P in /sys/class/power_supply/*; do " +
                                " [ -d \"$P\" ] || continue; " +
                                " TYPE=$(cat \"$P/type\" 2>/dev/null); " +
                                " [ \"$TYPE\" = \"Battery\" ] || continue; " +
                                " CURRENT=-; VOLTAGE=-; CAPACITY=-; " +
                                " [ -r \"$P/current_now\" ] && CURRENT=$(cat \"$P/current_now\"); " +
                                " [ -r \"$P/voltage_now\" ] && VOLTAGE=$(cat \"$P/voltage_now\"); " +
                                " [ -r \"$P/capacity\" ] && CAPACITY=$(cat \"$P/capacity\"); " +
                                " echo \"$CURRENT $VOLTAGE $CAPACITY\"; break; " +
                                "done; " +
                                "echo __PROC_STAT__; cat /proc/stat; " +
                                "echo __CPU_FREQ__; " +
                                "for i in 0 1 2 3 4 5 6 7; do " +
                                "F=/sys/devices/system/cpu/cpu$i/cpufreq/scaling_cur_freq; V=; " +
                                "[ -r \"$F\" ] && V=$(cat \"$F\" 2>/dev/null); " +
                                "case \"$V\" in \"\"|*[!0-9]*) V=;; esac; " +

                                "if [ -n \"$V\" ] && [ \"$V\" -gt 0 ] 2>/dev/null; then echo \"$V\"; else echo -1; fi; " +
                                "done; " +
                                "CACHED_FREQ='" + safeSysfsPath(cachedGpuFreqPath) + "'; " +
                                "CACHED_LOAD='" + safeSysfsPath(cachedGpuLoadPath) + "'; " +
                                "CACHED_MALI='" + safeSysfsPath(cachedMaliPath) + "'; " +
                                "GPU_FREQ=-1; GPU_LOAD='-1 -1'; FOUND_FREQ='none'; FOUND_LOAD='none'; " +
                                "MALI_PATH=\"$CACHED_MALI\"; " +
                                "MALI_RETRY=0; " +
                                "if [ -n \"$MALI_PATH\" ] && [ ! -d \"$MALI_PATH\" ]; then " +
                                " MALI_PATH=''; " +
                                " MALI_RETRY=1; " +
                                "fi; " +
                                "if [ -z \"$MALI_PATH\" ] && { " +
                                (searchMali ? "[ 1 = 1 ]" : "[ 1 = 0 ]") +
                                " || [ \"$MALI_RETRY\" = 1 ]; }; then " +
                                " if [ -d /sys/class/misc/mali0/device ]; then " +
                                "  MALI_PATH='/sys/class/misc/mali0/device'; " +
                                " else " +
                                "  MALI_PATH=$(find /sys/devices/platform -name '*.mali' -type d 2>/dev/null | head -n 1); " +
                                " fi; " +
                                "fi; " +

                                // GPU Frequency
                                "if [ -r /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq ]; then " +
                                " GPU_FREQ=$(cat /sys/class/kgsl/kgsl-3d0/devfreq/cur_freq); FOUND_FREQ='/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq'; " +
                                "elif [ -n \"$CACHED_FREQ\" ] && [ -r \"$CACHED_FREQ\" ]; then " +
                                " GPU_FREQ=$(cat \"$CACHED_FREQ\"); FOUND_FREQ=\"$CACHED_FREQ\"; " +
                                "elif [ -n \"$MALI_PATH\" ]; then " +
                                " for F in \"$MALI_PATH\"/devfreq/cur_freq \"$MALI_PATH\"/devfreq/*/cur_freq; do " +
                                "  if [ -r \"$F\" ]; then " +
                                "   GPU_FREQ=$(cat \"$F\"); FOUND_FREQ=\"$F\"; break; " +
                                "  fi; " +
                                " done; " +
                                " if [ \"$FOUND_FREQ\" = 'none' ] && [ -r \"$MALI_PATH/device/clock\" ]; then " +
                                "  GPU_FREQ=$(cat \"$MALI_PATH/device/clock\"); FOUND_FREQ=\"$MALI_PATH/device/clock\"; " +
                                " elif [ \"$FOUND_FREQ\" = 'none' ] && [ -r \"$MALI_PATH/clock\" ]; then " +
                                "  GPU_FREQ=$(cat \"$MALI_PATH/clock\"); FOUND_FREQ=\"$MALI_PATH/clock\"; " +
                                " fi; " +
                                "fi; " +
                                "if [ \"$FOUND_FREQ\" = 'none' ]; then " +
                                " for D in /sys/class/devfreq/*; do " +
                                "  [ -d \"$D\" ] || continue; " +
                                "  N=${D##*/}; " +
                                "  if [ -r \"$D/name\" ]; then IFS= read -r N < \"$D/name\"; fi; " +
                                "  N=$(printf '%s' \"$N\" | tr '[:upper:]' '[:lower:]'); " +
                                "  case \"$N\" in " + GpuDevfreqMatcher.shellCasePatterns() +
                                ") ;; *) continue;; esac; " +
                                "  if [ -r \"$D/cur_freq\" ]; then " +
                                "   GPU_FREQ=$(cat \"$D/cur_freq\"); FOUND_FREQ=\"$D/cur_freq\"; break; " +
                                "  fi; " +
                                " done; " +
                                "fi; " +

                                // One-time fallback for device-level devfreq nodes.
                                "if [ \"$FOUND_FREQ\" = 'none' ] && " +
                                (searchMali ? "[ 1 = 1 ]" : "[ 1 = 0 ]") +
                                "; then " +
                                " for F in $(find /sys/devices -type f \\( -path '*/devfreq/cur_freq' -o -path '*/devfreq/*/cur_freq' \\) -print 2>/dev/null); do " +
                                "  [ -r \"$F\" ] || continue; " +
                                "  D=${F%/cur_freq}; N=${D##*/}; " +
                                "  if [ \"$N\" = 'devfreq' ]; then P=${D%/devfreq}; N=${P##*/}; fi; " +
                                "  if [ -r \"$D/name\" ]; then IFS= read -r N < \"$D/name\"; fi; " +
                                "  N=$(printf '%s' \"$N\" | tr '[:upper:]' '[:lower:]'); " +
                                "  case \"$N\" in *busmon*) continue;; esac; " +
                                "  case \"$N\" in " + GpuDevfreqMatcher.shellCasePatterns() +
                                ") ;; *) continue;; esac; " +
                                "  GPU_FREQ=$(cat \"$F\" 2>/dev/null); FOUND_FREQ=\"$F\"; break; " +
                                " done; " +
                                "fi; " +
                                "echo __GPU_FREQ_BLOCK__; echo $GPU_FREQ; echo $FOUND_FREQ; " +


                                // GPU Load: collect candidates in priority order.
                                // Java will select the first candidate with a valid reading.
                                "GPU_LOAD_CANDIDATES=''; " +
                                "GPU_LOAD_ADDED=0; " +
                                "add_gpu_load_candidate() { " +
                                " F=\"$1\"; " +
                                " [ -r \"$F\" ] || return; " +
                                " V=$(cat \"$F\" 2>/dev/null) || return; " +
                                " [ -n \"$V\" ] || return; " +
                                " if [ -n \"$GPU_LOAD_CANDIDATES\" ]; then " +
                                "  GPU_LOAD_CANDIDATES=\"${GPU_LOAD_CANDIDATES}\n\"; " +
                                " fi; " +
                                " GPU_LOAD_CANDIDATES=\"${GPU_LOAD_CANDIDATES}${V} ${F}\"; " +
                                " GPU_LOAD_ADDED=1; " +
                                "}; " +
                                "add_gpu_load_candidate /sys/class/kgsl/kgsl-3d0/gpubusy; " +
                                "if [ \"$GPU_LOAD_ADDED\" != 1 ]; then " +
                                " for F in /sys/devices/platform/soc/*/kgsl/kgsl-3d0/gpubusy; do " +
                                "  add_gpu_load_candidate \"$F\"; " +
                                " done; " +
                                "fi; " +
                                "if [ -n \"$CACHED_LOAD\" ] && " +
                                "   [ \"$CACHED_LOAD\" != \"/sys/class/kgsl/kgsl-3d0/gpubusy\" ]; then " +
                                " add_gpu_load_candidate \"$CACHED_LOAD\"; " +
                                "fi; " +
                                "if [ -n \"$MALI_PATH\" ]; then " +
                                " for F in " +
                                "  \"$MALI_PATH\"/devfreq/load " +
                                "  \"$MALI_PATH\"/devfreq/*/load " +
                                "  \"$MALI_PATH\"/devfreq/utilisation " +
                                "  \"$MALI_PATH\"/devfreq/*/utilisation " +
                                "  \"$MALI_PATH\"/devfreq/utilization " +
                                "  \"$MALI_PATH\"/devfreq/*/utilization " +
                                "  \"$MALI_PATH\"/device/utilisation " +
                                "  \"$MALI_PATH\"/device/utilization; do " +
                                "  add_gpu_load_candidate \"$F\"; " +
                                " done; " +
                                "fi; " +
                                "for D in /sys/class/devfreq/*; do " +
                                " [ -d \"$D\" ] || continue; " +
                                " N=${D##*/}; " +
                                " if [ -r \"$D/name\" ]; then IFS= read -r N < \"$D/name\"; fi; " +
                                " N=$(printf '%s' \"$N\" | tr '[:upper:]' '[:lower:]'); " +
                                " case \"$N\" in *busmon*) continue;; esac; " +
                                " case \"$N\" in " + GpuDevfreqMatcher.shellCasePatterns() +
                                ") ;; *) continue;; esac; " +
                                " for F in \"$D/load\" \"$D/utilisation\" \"$D/utilization\"; do " +
                                "  add_gpu_load_candidate \"$F\"; " +
                                " done; " +
                                "done; " +
                                (searchMali ? "if [ 1 = 1 ]; then " : "if [ 1 = 0 ]; then ") +
                                "for F in $(find /sys/devices -type f \\( " +
                                " -path '*/devfreq/load' -o -path '*/devfreq/utilisation' -o -path '*/devfreq/utilization' " +
                                " -o -path '*/devfreq/*/load' -o -path '*/devfreq/*/utilisation' -o -path '*/devfreq/*/utilization' " +
                                " \\) -print 2>/dev/null); do " +
                                " [ -r \"$F\" ] || continue; " +
                                " D=${F%/*}; N=${D##*/}; " +
                                " if [ \"$N\" = 'devfreq' ]; then P=${D%/devfreq}; N=${P##*/}; fi; " +
                                " if [ -r \"$D/name\" ]; then IFS= read -r N < \"$D/name\"; fi; " +
                                " N=$(printf '%s' \"$N\" | tr '[:upper:]' '[:lower:]'); " +
                                " case \"$N\" in *busmon*) continue;; esac; " +
                                " case \"$N\" in " + GpuDevfreqMatcher.shellCasePatterns() +
                                ") ;; *) continue;; esac; " +
                                " add_gpu_load_candidate \"$F\"; " +
                                "done; " +
                                "fi; " +
                                "echo __GPU_LOAD_BLOCK__; " +
                                "printf '%s\n' \"$GPU_LOAD_CANDIDATES\"; " +

                                "echo __THERMAL_BLOCK__; " +
                                "for Z in /sys/class/thermal/thermal_zone*; do " +
                                "T=; IFS= read -r T < \"$Z/type\" 2>/dev/null; " +
                                "case \"$T\" in " +
                                " cpu|CPU|cpu-*|CPU-*|cpu_*|CPU_*|*cpu*|*CPU*|mtkts_cpu|MTKTS_CPU) K=CPU;; " +
                                " gpu|GPU|gpu-*|GPU-*|gpu_*|GPU_*|*gpu*|*GPU*|gpuss-*|GPUSS-*|mtkts_gpu|MTKTS_GPU) K=GPU;; " +
                                " *) continue;; esac; " +
                                " V=; IFS= read -r V < \"$Z/temp\" 2>/dev/null; " +
                                " echo \"$K $V\"; " +
                                "done; " +
                                "echo __COOLING_BLOCK__; " +
                                "for C in /sys/class/thermal/cooling_device*; do " +
                                " [ -d \"$C\" ] || continue; " +
                                " T=; IFS= read -r T < \"$C/type\" 2>/dev/null; " +
                                " case \"$T\" in *cpu*|*gpu*|*cpufreq*|*devfreq*) ;; *) continue;; esac; " +
                                " S=; M=; IFS= read -r S < \"$C/cur_state\" 2>/dev/null; " +
                                " IFS= read -r M < \"$C/max_state\" 2>/dev/null; " +
                                " echo \"$T $S $M\"; " +
                                "done; " +
                                "echo __MALI_BLOCK__; " +
                                "[ -n \"$MALI_PATH\" ] && echo \"$MALI_PATH\" || echo none; " +
                                "echo __END__;";
        return run(new String[]{"sh", "-c", script}, 10, MAX_OUTPUT_CHARS);
    }

    @Override
    public String getResumedActivityDump() throws RemoteException {
        String script = "dumpsys activity activities 2>/dev/null | " +
                "grep -E 'topResumedActivity|ResumedActivity|mFocusedApp'";
        String dump = run(new String[]{"sh", "-c", script}, 5, 16 * 1024);
        return extractComponentReferences(dump);
    }



    @Override
    public String getWindowDump() throws RemoteException {
        String script = "dumpsys window 2>/dev/null | " +
                "grep -E 'mCurrentFocus|mFocusedApp|mFocusedWindow'";
        String dump = run(new String[]{"sh", "-c", script}, 5, 16 * 1024);
        return extractComponentReferences(dump);
    }



    @Override
    public String getSurfaceFlingerDump() throws RemoteException {
        return run(new String[]{"dumpsys", "SurfaceFlinger"});
    }

    @Override
    public String getSurfaceFlingerLayers() throws RemoteException {
        // Only SurfaceView layers matter to the detector; filtering here keeps the
        // reply well inside Binder's transaction limit on devices with many layers.
        return run(new String[]{"/system/bin/sh", "-c",
                        "dumpsys SurfaceFlinger --list 2>/dev/null | grep -i SurfaceView"},
                COMMAND_TIMEOUT_SECONDS, MAX_OUTPUT_CHARS);
    }

    @Override
    public String getSurfaceFlingerLatency(String layerName) throws RemoteException {
        if (!isSafeLayerName(layerName)) return "";
        return run(new String[]{"dumpsys", "SurfaceFlinger", "--latency", layerName});
    }

    @Override
    public String getSurfaceFlingerLayerLatencySnapshot(
            String packageName, boolean blastOnly) throws RemoteException {
        if (packageName == null
                || !packageName.matches("[A-Za-z0-9_.]+")) {
            return "";
        }
        String script = FpsDetector.buildSurfaceFlingerLayerLatencyScript(
                packageName, blastOnly);
        return run(new String[]{"sh", "-c", script}, COMMAND_TIMEOUT_SECONDS,
                MAX_OUTPUT_CHARS);
    }

    @Override
    public String getSurfaceFlingerTimeStatsDump() throws RemoteException {
        return run(new String[]{"dumpsys", "SurfaceFlinger",
                "--timestats", "-maxlayers", "64", "-dump"});
    }

    @Override
    public String getSurfaceFlingerTimeStatsSnapshot() throws RemoteException {
        String script = "echo __ONYX_LAYERS__; " +
                "dumpsys SurfaceFlinger --list 2>/dev/null; " +
                "echo __ONYX_TIMESTATS__; " +
                "dumpsys SurfaceFlinger --timestats -maxlayers 64 -dump 2>/dev/null";
        return run(new String[]{"sh", "-c", script}, COMMAND_TIMEOUT_SECONDS,
                MAX_OUTPUT_CHARS);
    }

    private static boolean isSafeLayerName(String value) {
        return value != null && !value.isEmpty() && value.length() <= 512
                && value.matches("[A-Za-z0-9_ .:/()\\[\\]{}#@+\\-=,]+");
    }

    private static String safeSysfsPath(String value) {
        if (value == null || value.isEmpty() || value.length() > 256
                || !value.startsWith("/sys/")
                || value.contains("..")
                || !value.matches("/sys/[A-Za-z0-9_./-]+")) {
            return "";
        }
        return value;
    }

    private static String run(String[] command) {
        return run(command, COMMAND_TIMEOUT_SECONDS, MAX_OUTPUT_CHARS);
    }

    private static String extractComponentReferences(String dump) {
        if (dump == null || dump.isEmpty()) return "";
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                "([A-Za-z0-9_]+(?:[.][A-Za-z0-9_]+)+)/[A-Za-z0-9_.$]+")
                .matcher(dump);
        StringBuilder result = new StringBuilder();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        while (matcher.find() && result.length() < MAX_COMPONENT_OUTPUT_CHARS) {
            String component = matcher.group();
            if (seen.add(component)) {
                if (result.length() > 0) result.append('\n');
                result.append(component);
            }
        }
        return result.toString();
    }

    private static String run(String[] command, long timeoutSeconds, int maxOutputChars) {
        return runInner(command, timeoutSeconds, maxOutputChars);
    }

    private static String runInner(String[] command, long timeoutSeconds, int maxOutputChars) {
        Process process = null;
        Thread readerThread = null;
        StringBuilder output = new StringBuilder();
        java.util.concurrent.atomic.AtomicBoolean truncated =
                new java.util.concurrent.atomic.AtomicBoolean(false);


        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).start();
            final Process activeProcess = process;
            readerThread = new Thread(() -> {
                try (InputStream input = activeProcess.getInputStream();
                     InputStreamReader streamReader =
                             new InputStreamReader(input, StandardCharsets.UTF_8);
                     BufferedReader reader = new BufferedReader(streamReader)) {
                    char[] buffer = new char[4096];
                    int count;
                    while ((count = reader.read(buffer)) != -1) {
                        synchronized (output) {
                            int remaining = maxOutputChars - output.length();
                            if (count <= remaining) {
                                output.append(buffer, 0, count);
                            } else {
                                output.append(buffer, 0, Math.max(0, remaining));
                                truncated.set(true);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "OnyxShizukuOutput");
            readerThread.setDaemon(true);
            readerThread.start();

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
                readerThread.join(500);
                return "";
            }

            readerThread.join(1000);
            if (readerThread.isAlive()) {
                process.destroyForcibly();
                return "";
            }

            synchronized (output) {
                if (truncated.get()) {
                    // A partial dumpsys can still parse as valid data, which would
                    // report a wrong reading. Signal failure instead.
                    return null;
                }
                return output.toString();
            }
        } catch (Exception ignored) {
            if (process != null) process.destroyForcibly();
            return "";
        } finally {
            if (process != null) {
                try {
                    process.getInputStream().close();
                } catch (Exception ignored) {
                }
                process.destroy();
            }
        }
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
