package com.onyx.perfmonitor;

import android.os.Handler;

/**
 * Coordinates background sampling and delivery of samples to the Activity.
 * The Activity remains the owner of session state and UI rendering.
 */
final class SamplingController {
    private final MainActivity activity;
    private boolean busy;

    private final Runnable sampler = new Runnable() {
        @Override
        public void run() {
            if (busy) return;
            busy = true;

            final int currentSessionId = activity.sessionId;
            final boolean recording = activity.running
                    && !activity.isOverlaySession();

            activity.worker.execute(() -> {
                String detected = "ANDROID";
                Sample taken = null;
                final long sampleStartMs = android.os.SystemClock.elapsedRealtime();

                try {
                    taken = activity.monitoringEngine.sample(recording);
                    detected = activity.monitoringEngine.getMonitoringMode();
                    activity.cachedMonitoringMode = detected;

                    if ("SHIZUKU".equals(detected)
                            && activity.shizukuService == null
                            && !activity.shizukuBindRequested) {
                        activity.runOnUiThread(() -> {
                            if (activity.shizukuService == null
                                    && !activity.shizukuBindRequested) {
                                activity.shizukuBindRequested = true;
                                activity.startShizukuService();
                            }
                        });
                    }
                } catch (Throwable error) {
                    android.util.Log.e("ONYX_SAMPLE", "sample failed", error);
                }

                final String mode = detected;
                final Sample sample = taken;

                activity.runOnUiThread(() -> {
                    busy = false;

                    if (currentSessionId != activity.sessionId) {
                        scheduleAt(sampleStartMs + 500L);
                        return;
                    }
                    if (activity.isAppForeground && activity.selectedTab == 0) {
                        activity.setStatus(mode
                                + (activity.running ? " · RECORDING" : " · READY"), false);
                    }
                    if (sample != null) {
                        activity.overlayMetricsStore.publish(
                                sample.fps,
                                sample.fpsSource,
                                sample.temperatureC,
                                sample.averageFrameTimeMs,
                                sample.cpu,
                                sample.gpuLoadPercent,
                                sample.ramUsagePercent,
                                sample.thermalStatus,
                                sample.cpuCoolingState,
                                sample.cpuCoolingMaxState,
                                sample.gpuCoolingState,
                                sample.gpuCoolingMaxState
                        );
                        if (activity.running && !activity.isOverlaySession()) {
                            if (!activity.samples.isEmpty()) {
                                Sample previous = activity.samples.get(
                                        activity.samples.size() - 1);

                                int drop = previous.battery - sample.battery;
                                long elapsed = sample.timeMs - previous.timeMs;

                                boolean previousBatteryValid =
                                        previous.battery >= 0 && previous.battery <= 100;
                                boolean currentBatteryValid =
                                        sample.battery >= 0 && sample.battery <= 100;

                                if (previousBatteryValid && currentBatteryValid
                                        && drop > 0 && elapsed > 0) {
                                    sample.secondsPerPercent =
                                            (elapsed / 1000.0) / drop;
                                }
                            }

                            Sample sessionSample = SessionDataPreferences.forSession(
                                    sample, activity);
                            activity.samples.add(sample);
                            SessionCsvFormatter.appendSample(activity.csv, sessionSample);
                        }

                        if (activity.isAppForeground && activity.selectedTab == 0) {
                            activity.render(sample);
                        }
                    }

                    scheduleAt(sampleStartMs + 500L);
                });
            });
        }
    };

    SamplingController(MainActivity activity) {
        this.activity = activity;
    }

    private boolean isNeeded() {
        // The overlay is fed by this sampler while the app is in the foreground
        // (the service engine is stopped), so it must keep running on every tab.
        return (activity.isAppForeground
                && (activity.selectedTab == 0 || activity.isOverlayEnabled()))
                || (activity.running && !activity.isOverlaySession());
    }

    void onMonitorTabChanged() {
        activity.handler.removeCallbacks(sampler);
        if (isNeeded()) {
            wake();
        } else {
            activity.monitoringEngine.resetCpuBaseline();
        }
    }

    void schedule() {
        if (isNeeded()) {
            activity.handler.postDelayed(sampler, 500);
        } else {
            activity.monitoringEngine.resetCpuBaseline();
        }
    }

    private void scheduleAt(long targetTimeMs) {
        if (isNeeded()) {
            long delayMs = Math.max(
                    0L,
                    targetTimeMs - android.os.SystemClock.elapsedRealtime()
            );
            activity.handler.postDelayed(sampler, delayMs);
        } else {
            activity.monitoringEngine.resetCpuBaseline();
        }
    }

    void wake() {
        activity.handler.removeCallbacks(sampler);
        if (isNeeded()) {
            sampler.run();
        } else {
            activity.monitoringEngine.resetCpuBaseline();
        }
    }

    void stop() {
        activity.handler.removeCallbacks(sampler);
    }
}
