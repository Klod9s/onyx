package com.onyx.perfmonitor;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.ComponentName;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import rikka.shizuku.Shizuku;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.graphics.LinearGradient;
import android.graphics.Path;
import android.graphics.Shader;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FloatingFpsService extends Service {
    public static final String ACTION_TOGGLE =
            "com.onyx.perfmonitor.TOGGLE_SESSION";
    public static final String ACTION_START_RECORDING =
            "com.onyx.perfmonitor.START_RECORDING";
    public static final String ACTION_STOP_RECORDING =
            "com.onyx.perfmonitor.STOP_RECORDING";
    public static final String ACTION_RECORDING_STATE_CHANGED =
            "com.onyx.perfmonitor.RECORDING_STATE_CHANGED";
    public static final String SESSION_OWNER_APP = "app";
    public static final String SESSION_OWNER_OVERLAY = "overlay";
    public static final String SESSION_OWNER_NONE = "none";
    public static final String ACTION_ENGINE_HANDOFF =
            "com.onyx.perfmonitor.ENGINE_HANDOFF";
    public static final String ACTION_ENGINE_STOP =
            "com.onyx.perfmonitor.ENGINE_STOP";
    /** Boolean extra on ACTION_ENGINE_HANDOFF: also re-add the overlay window. */
    public static final String EXTRA_REATTACH = "reattach";
    private long lastReattachRequestMs = 0;

    private static final String CHANNEL = "fps_overlay";
    private static final int NOTIFICATION_ID = 71;
    private static final String PREFS = "overlay_settings";

    private static final int DIVIDER = Color.argb(45, 255, 255, 255);

    private static final int STYLE_CLASSIC = 0;
    private static final int STYLE_STRIP = 1;
    private int currentStyle = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService overlayExecutor =
            Executors.newSingleThreadExecutor();
    private final ExecutorService samplingExecutor =
            Executors.newSingleThreadExecutor();
    private MonitoringEngine monitoringEngine;
    private OverlayMetricsStore overlayMetricsStore;
    private OverlaySessionRecorder sessionRecorder;
    private boolean backgroundSamplingEnabled = false;
    private boolean backgroundShizukuBindRequested = false;
    private boolean backgroundShizukuBound = false;
    private boolean shizukuDisconnectToastShown = false;
    private Shizuku.UserServiceArgs backgroundShizukuArgs;
    private boolean serviceDestroyed = false;
    private final Runnable backgroundSampleTick = new Runnable() {
        @Override
        public void run() {
            if (serviceDestroyed || !backgroundSamplingEnabled
                    || !overlayPrefs.getBoolean("overlay_enabled", false)) {
                return;
            }

            try {
                ensureBackgroundShizukuBinding();
            } catch (RuntimeException ignored) {
                // A temporary Shizuku failure must not stop overlay sampling.
            }

            try {
                samplingExecutor.execute(() -> {
                    try {
                        boolean recording =
                                sessionPrefs.getBoolean("recording", false);
                        Sample sample = monitoringEngine.sample(recording);
                        if (sample != null) {
                            overlayMetricsStore.publish(
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
                                    sample.gpuCoolingMaxState);
                            // Re-check shared state after sampling; Stop may have been
                            // pressed while this sample was in flight. Never auto-start
                            // the recorder from the sampling loop.
                            if (sessionPrefs.getBoolean("recording", false)
                                    && sessionRecorder != null
                                    && sessionRecorder.isRecording()) {
                                sessionRecorder.append(
                                        sample,
                                        monitoringEngine.getMonitoringMode());
                            }
                        }
                    } catch (RuntimeException ignored) {
                        // Keep the sampling loop alive after an individual failure.
                    } finally {
                        handler.post(() -> {
                            if (!serviceDestroyed
                                    && backgroundSamplingEnabled
                                    && overlayPrefs.getBoolean(
                                    "overlay_enabled", false)) {
                                handler.postDelayed(backgroundSampleTick, 500);
                            }
                        });
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // The executor may be shutting down as the service is destroyed.
            }
        }
    };

    private final ServiceConnection backgroundShizukuConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder binder) {
                    backgroundShizukuBindRequested = false;
                    backgroundShizukuBound = binder != null && binder.pingBinder();
                    if (backgroundShizukuBound) {
                        monitoringEngine.setShizukuService(
                                IOnyxUserService.Stub.asInterface(binder));
                        handler.post(() -> shizukuDisconnectToastShown = false);
                    } else {
                        monitoringEngine.setShizukuService(null);
                        notifyShizukuDisconnected();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    monitoringEngine.setShizukuService(null);
                    backgroundShizukuBindRequested = false;
                    backgroundShizukuBound = false;
                    notifyShizukuDisconnected();
                }
            };

    private final Shizuku.OnBinderDeadListener backgroundBinderDeadListener = () -> {
        monitoringEngine.setShizukuService(null);
        backgroundShizukuBindRequested = false;
        backgroundShizukuBound = false;
        notifyShizukuDisconnected();
    };
    private SharedPreferences overlayPrefs;
    private SharedPreferences sessionPrefs;
    private int overlayAccentColor = Color.rgb(69, 168, 245);
    private int overlayTextColor = Color.WHITE;
    private int overlayBackgroundColor = Color.rgb(20, 25, 32);

    private WindowManager windowManager;
    private WindowManager.LayoutParams overlayParams;
    private LinearLayout overlay;
    private ScaleFrame overlayRoot;
    private GradientDrawable overlayBackground;
    private LinearLayout overlayContent;
    private TextView fpsView;
    private TextView fpsLabelView;
    private String lastFpsSourceLabel = "";
    private long sourceLabelUntil = 0;

    private TextView temperatureView;
    private ImageView batteryIconView;
    private TextView frameTimeView;
    private TextView cpuView;
    private TextView gpuView;
    private TextView ramView;
    private FrameTimeGraphView frameTimeGraph;
    private LinearLayout leftColumn;
    private LinearLayout rightColumn;
    private LinearLayout cpuGpuRow;

    // Kept at service level so the graph doesn't reset when the overlay is rebuilt
    private final ArrayList<Float> frameHistory = new ArrayList<>();

    private boolean attached = false;

    private int x, y;
    private float downRawX, downRawY;
    private int downX, downY;
    private boolean moved;
    private boolean recording = false;
    private int currentLayoutMode = -1;
    private int currentLayoutConfig = -1;
    private int currentOpacity = -1;
    private int currentScale = -1;
    private float overlayContentScale = 1f;
    private int baseOverlayWidth = 0;
    private int baseOverlayHeight = 0;

    // Regular 1-second tick: read live values, refresh, add a graph point
    private final Runnable updater = new Runnable() {
        @Override
        public void run() {
            if (!attached) return;

            overlayExecutor.execute(() -> {
                final String[] readings = readOverlayValues();

                handler.post(() -> {
                    if (!attached) return;
                    boolean addPoint = (++graphTick % 4 == 0);
                    refreshOverlay(readings, addPoint);
                    handler.postDelayed(updater, 500);
                });
            });
        }
    };

    // Instant refresh when a setting changes (no graph point added)
    private boolean prefRefreshPending = false;

    private int graphTick = 0;

    // Instant refresh when a setting changes (no graph point added)
    private final Runnable prefRefresh = () -> {
        prefRefreshPending = false;
        if (attached) refreshOverlay(readOverlayValues(), false);
    };

    // Field (not a local) on purpose: SharedPreferences holds listeners weakly
    private final SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            (prefs, key) -> {
                // Throttle: at most one refresh per ~16 ms, even during a fast drag
                if (!prefRefreshPending) {
                    prefRefreshPending = true;
                    handler.postDelayed(prefRefresh, 16);
                }
            };

    private void refreshOverlay(String[] readings, boolean addToGraph) {
        String fps = readings[0];
        String temperature = readings[1];
        String frameTime = readings[2];
        String cpu = readings[3];
        String gpu = readings[4];
        String ram = readings[5];
        String fpsSource = readings.length > 11 ? readings[11] : "";
        int thermalStatus;
        try {
            thermalStatus = Integer.parseInt(readings[6]);
        } catch (Exception ignored) {
            thermalStatus = ThermalStatusReader.UNAVAILABLE;
        }

        boolean showFps = overlayPrefs.getBoolean("show_fps", true);
        boolean showTemperature =
                overlayPrefs.getBoolean("show_temperature", true);
        boolean showGraph =
                overlayPrefs.getBoolean("show_frame_time_graph", true);
        boolean showCpu = overlayPrefs.getBoolean("show_cpu", true);
        boolean showGpu = overlayPrefs.getBoolean("show_gpu", true);
        boolean showRam = overlayPrefs.getBoolean("show_ram", true);
        int opacity = overlayPrefs.getInt("overlay_opacity", 85);
        int scalePercent = overlayPrefs.getInt("overlay_scale", 100);
        int style = overlayPrefs.getInt("overlay_style", STYLE_CLASSIC);

        int layoutMode = showGraph ? 1 : (showCpu || showGpu || showRam ? 2 : 3);

        int layoutConfig =
                (showFps ? 1 : 0)
                        | (showTemperature ? 2 : 0)
                        | (showGraph ? 4 : 0)
                        | (showCpu ? 8 : 0)
                        | (showGpu ? 16 : 0)
                        | (showRam ? 32 : 0);

        if (scalePercent != currentScale) {
            applyScale(scalePercent);
        }

        if (style != currentStyle
                || layoutMode != currentLayoutMode
                || layoutConfig != currentLayoutConfig
                || overlayPrefs.getInt("overlay_accent_color",
                Color.rgb(69, 168, 245)) != overlayAccentColor
                || overlayPrefs.getInt("overlay_text_color",
                Color.WHITE) != overlayTextColor
                || overlayPrefs.getInt("overlay_background_color",
                Color.rgb(20, 25, 32)) != overlayBackgroundColor) {

            buildOverlayLayout(
                    showFps,
                    showTemperature,
                    showGraph,
                    showCpu,
                    showGpu,
                    showRam);

            applyScale(scalePercent);

            currentLayoutConfig = layoutConfig;
            currentOpacity = opacity;

            overlayParams.width = WindowManager.LayoutParams.WRAP_CONTENT;
            overlayParams.height = WindowManager.LayoutParams.WRAP_CONTENT;

            if (attached) {
                windowManager.updateViewLayout(overlayRoot, overlayParams);
            }

        }

        if (opacity != currentOpacity && overlayBackground != null) {
            overlayBackground.setColor(Color.argb(
                    (int) (255 * opacity / 100f),
                    Color.red(overlayBackgroundColor),
                    Color.green(overlayBackgroundColor),
                    Color.blue(overlayBackgroundColor)));
            currentOpacity = opacity;
        }

        recording = sessionPrefs.getBoolean("recording", false);

        if (overlayBackground != null) {
            overlayBackground.setStroke(
                    dp(1),
                    recording
                            ? Color.rgb(128, 0, 32)
                            : Color.argb(100, 255, 255, 255));
        }

        if (fpsView != null) {
            fpsView.setVisibility(showFps ? View.VISIBLE : View.GONE);
        }

        if (temperatureView != null) {
            temperatureView.setVisibility(
                    showTemperature ? View.VISIBLE : View.GONE);
        }

        if (frameTimeView != null) {
            frameTimeView.setVisibility(showGraph ? View.VISIBLE : View.GONE);
        }

        if (frameTimeGraph != null) {
            frameTimeGraph.setVisibility(showGraph ? View.VISIBLE : View.GONE);
        }

        if (cpuView != null) {
            cpuView.setVisibility(showCpu ? View.VISIBLE : View.GONE);
        }

        if (gpuView != null) {
            gpuView.setVisibility(showGpu ? View.VISIBLE : View.GONE);
        }
        if (ramView != null) {
            ramView.setVisibility(showRam ? View.VISIBLE : View.GONE);
        }

        // Text is re-applied after a rebuild, so nothing flashes back to "--"
        if (fpsView != null) fpsView.setText(fps);
        if (fpsLabelView != null) {
            fpsLabelView.setText("FPS");
            fpsLabelView.setContentDescription("Frames per second");
        }
        if (temperatureView != null) temperatureView.setText(temperature);
        if (batteryIconView != null) {
            int thermalLevel = ThermalStatusReader.overlayLevel(thermalStatus);
            if (thermalLevel > 0) {
                batteryIconView.setImageResource(R.drawable.ic_thermal_flame);
                int flameColor = thermalLevel == 1
                        ? Color.rgb(255, 152, 0)
                        : Color.rgb(244, 67, 54);
                batteryIconView.setImageTintList(
                        android.content.res.ColorStateList.valueOf(flameColor));
                batteryIconView.setContentDescription(
                        "Android thermal status: " + ThermalStatusReader.label(thermalStatus));
            } else {
                batteryIconView.setImageResource(R.drawable.ic_battery_temp);
                batteryIconView.setImageTintList(null);
                batteryIconView.setContentDescription(
                        thermalStatus == ThermalStatusReader.UNAVAILABLE
                                ? "Android thermal status unavailable"
                                : "Normal Android thermal status");
            }
        }
        if (fpsLabelView != null) {
            long now = android.os.SystemClock.elapsedRealtime();
            String sourceLabel = compactFpsSourceLabel(fpsSource);

            // Show the source briefly when it changes, then fall back to "FPS"
            // so the label does not compete with the number for attention.
            if (!sourceLabel.equals(lastFpsSourceLabel)) {
                lastFpsSourceLabel = sourceLabel;
                if (!"FPS".equals(sourceLabel)) {
                    sourceLabelUntil = now + 2000;
                }
            }

            if (sourceLabelUntil > now && !"FPS".equals(sourceLabel)) {
                fpsLabelView.setText(sourceLabel);
                fpsLabelView.setAlpha(1f);
            } else if (sourceLabelUntil != 0 && sourceLabelUntil <= now) {
                fpsLabelView.setText("FPS");
                sourceLabelUntil = 0;
            } else {
                fpsLabelView.setText("FPS");
            }
        }
        if (frameTimeView != null) frameTimeView.setText(frameTime);
        if (cpuView != null) cpuView.setText("CPU " + cpu);
        if (gpuView != null) gpuView.setText("GPU " + gpu);
        if (ramView != null) ramView.setText("RAM " + ram);

        if (addToGraph
                && frameTimeGraph != null
                && frameTimeGraph.getVisibility() == View.VISIBLE) {

            frameTimeGraph.addFrameTime(
                    parseReading(frameTime),
                    parseReading(fps),
                    recording);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        overlayPrefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        sessionPrefs = getSharedPreferences("session_state", MODE_PRIVATE);
        monitoringEngine = new MonitoringEngine(getApplicationContext());
        overlayMetricsStore = new OverlayMetricsStore(getApplicationContext());
        sessionRecorder = new OverlaySessionRecorder(getApplicationContext());
        Shizuku.addBinderDeadListener(backgroundBinderDeadListener);

        overlayPrefs.registerOnSharedPreferenceChangeListener(prefListener);
        sessionPrefs.registerOnSharedPreferenceChangeListener(prefListener);

        createChannel();

        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("Onyx Performance Monitor")
                .setContentText("Floating performance overlay is active")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setOngoing(true)
                .build();

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo
                            .FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        windowManager =
                (WindowManager) getSystemService(WINDOW_SERVICE);

        // A stop-recording command may start the service only to clean up
        // stale session state. Never recreate the window when the user disabled it.
        if (overlayPrefs.getBoolean("overlay_enabled", false)) {
            attachOverlay();
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL,
                    "Floating performance overlay",
                    NotificationManager.IMPORTANCE_LOW);

            channel.setDescription(
                    "Keeps the performance overlay available over games");

            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .createNotificationChannel(channel);
        }
    }

    private void setOverlayTextSize(TextView view, float baseDp) {
        view.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX,
                baseDp * overlayContentScale
                        * getResources().getDisplayMetrics().density);
    }

    private LinearLayout makeColumn(int width, int height) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);
        column.setClipChildren(false);
        column.setClipToPadding(false);
        column.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        return column;
    }

    // Fixed-height slot; child positioned by gravity
    private FrameLayout cell(View child, int height, int gravity) {
        FrameLayout cell = new FrameLayout(this);
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        cell.addView(child, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                gravity));
        cell.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        return cell;
    }

    private FrameLayout cell(View child, int height) {
        return cell(child, height, Gravity.CENTER);
    }

    // Same, but with a thin horizontal divider along the bottom edge
    private FrameLayout cellWithBottomLine(View child, int height, int gravity) {
        FrameLayout c = cell(child, height, gravity);
        View line = new View(this);
        line.setBackgroundColor(DIVIDER);
        c.addView(line, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, dpScaled(1)),
                Gravity.BOTTOM));
        return c;
    }

    private TextView createMetricView(String text) {
        TextView view = new TextView(this);

        view.setText(text);
        setOverlayTextSize(view, 10);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        overlayTextColor = overlayPrefs.getInt(
                "overlay_text_color",
                Color.WHITE
        );

        view.setTextColor(overlayTextColor);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setSingleLine(true);

        return view;
    }

    private void buildOverlayLayout(
            boolean showFps, boolean showTemperature, boolean showGraph,
            boolean showCpu, boolean showGpu, boolean showRam) {

        currentStyle = overlayPrefs.getInt("overlay_style", STYLE_CLASSIC);

        if (currentStyle == STYLE_STRIP) {
            buildStripLayout(showFps, showTemperature, showGraph, showCpu, showGpu, showRam);
        } else {
            buildClassicLayout(showFps, showTemperature, showGraph, showCpu, showGpu, showRam);
        }
    }

    private void buildStripLayout(
            boolean showFps, boolean showTemperature, boolean showGraph,
            boolean showCpu, boolean showGpu, boolean showRam) {

        overlay.removeAllViews();
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        // Strip mode always keeps the larger FPS number and its label.
        final boolean compactFps = false;

        overlayAccentColor = overlayPrefs.getInt(
                "overlay_accent_color", Color.rgb(69, 168, 245));

        // views (always created, the updater touches all of them)
        fpsView = createMetricView("--");
        fpsView.setIncludeFontPadding(false);
        setOverlayTextSize(fpsView, compactFps ? 14 : 17);
        fpsView.setGravity(Gravity.CENTER);
        fpsView.setTextColor(overlayAccentColor);

        fpsLabelView = createMetricView("FPS");
        lastFpsSourceLabel = "";
        sourceLabelUntil = 0;
        TextView fpsLabel = fpsLabelView;
        setOverlayTextSize(fpsLabel, 5);
        fpsLabel.setTranslationY(-dpScaled(9));

        temperatureView = createMetricView("--°C");
        temperatureView.setGravity(Gravity.CENTER);

        batteryIconView = new ImageView(this);
        batteryIconView.setImageResource(R.drawable.ic_battery_temp);
        batteryIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        frameTimeView = createMetricView("--.- ms");
        setOverlayTextSize(frameTimeView, 7);
        frameTimeView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        frameTimeGraph = new FrameTimeGraphView(this);

        cpuView = createMetricView("CPU --%");
        gpuView = createMetricView("GPU --%");
        ramView = createMetricView("RAM --%");
        setOverlayTextSize(cpuView, 8);
        setOverlayTextSize(gpuView, 8);
        setOverlayTextSize(ramView, 8);
        cpuView.setGravity(Gravity.CENTER);
        gpuView.setGravity(Gravity.CENTER);
        ramView.setGravity(Gravity.CENTER);

        // fixed widths so values don't make the strip jitter
        int fpsNumW = (int) Math.ceil(fpsView.getPaint().measureText("000"));
        int tempTextW = (int) Math.ceil(
                temperatureView.getPaint().measureText("88.8°C")) + dpScaled(2);
        int slot = (int) Math.ceil(Math.max(
                cpuView.getPaint().measureText("CPU 100%"),
                Math.max(gpuView.getPaint().measureText("GPU 100%"),
                        ramView.getPaint().measureText("RAM 100%")))) + dpScaled(2);

        overlayContent = new LinearLayout(this);
        overlayContent.setOrientation(LinearLayout.HORIZONTAL);
        overlayContent.setGravity(Gravity.CENTER_VERTICAL);
        overlayContent.setClipChildren(false);
        overlayContent.setClipToPadding(false);
        overlayContent.setMinimumHeight(dpScaled(28));

        // section 1: FPS + temp
        if (showFps || showTemperature) {
            LinearLayout left = new LinearLayout(this);
            left.setOrientation(LinearLayout.HORIZONTAL);
            left.setGravity(Gravity.CENTER_VERTICAL);
            left.setClipChildren(false);

            if (showFps) {
                LinearLayout fpsRow = new LinearLayout(this);
                fpsRow.setOrientation(LinearLayout.HORIZONTAL);
                fpsRow.setGravity(Gravity.CENTER_VERTICAL);
                fpsRow.setClipChildren(false);
                fpsRow.addView(fpsView, new LinearLayout.LayoutParams(
                        fpsNumW, ViewGroup.LayoutParams.WRAP_CONTENT));
                if (!compactFps) {
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT);
                    lp.leftMargin = dpScaled(2);
                    fpsRow.addView(fpsLabel, lp);
                }
                left.addView(fpsRow);
            }

            if (showTemperature) {
                LinearLayout tempRow = new LinearLayout(this);
                tempRow.setOrientation(LinearLayout.HORIZONTAL);
                tempRow.setGravity(Gravity.CENTER_VERTICAL);
                tempRow.addView(batteryIconView, new LinearLayout.LayoutParams(
                        dpScaled(11), dpScaled(10)));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(
                        tempTextW, ViewGroup.LayoutParams.WRAP_CONTENT);
                tp.leftMargin = dpScaled(1);
                tempRow.addView(temperatureView, tp);

                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.leftMargin = showFps ? dpScaled(10) : 0;
                left.addView(tempRow, rp);
            }
            addStripSection(left);
        }

        // section 2: graph with ms on top
        if (showGraph) {
            int graphW = dpScaled(80);
            FrameLayout graphBlock = new FrameLayout(this);
            graphBlock.setClipChildren(false);
            graphBlock.addView(frameTimeGraph, new FrameLayout.LayoutParams(
                    graphW, dpScaled(18), Gravity.BOTTOM));
            graphBlock.addView(frameTimeView, new FrameLayout.LayoutParams(
                    graphW, dpScaled(12), Gravity.TOP));
            graphBlock.setLayoutParams(new LinearLayout.LayoutParams(
                    graphW, dpScaled(28)));
            addStripSection(graphBlock);
        }

        // section 3: CPU · GPU
        if (showCpu || showGpu || showRam) {
            LinearLayout stats = new LinearLayout(this);
            stats.setOrientation(LinearLayout.HORIZONTAL);
            stats.setGravity(Gravity.CENTER_VERTICAL);
            stats.setClipChildren(false);

            if (showCpu) {
                stats.addView(cpuView, new LinearLayout.LayoutParams(
                        slot, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (showCpu && showGpu) {
                TextView dot = createMetricView("·");
                dot.setGravity(Gravity.CENTER);
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                dp.leftMargin = dpScaled(4);
                dp.rightMargin = dpScaled(4);
                stats.addView(dot, dp);
            }
            if (showGpu) {
                stats.addView(gpuView, new LinearLayout.LayoutParams(
                        slot, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (showRam) {
                if (showCpu || showGpu) addStripSeparator(stats);
                stats.addView(ramView, new LinearLayout.LayoutParams(
                        slot, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            addStripSection(stats);
        }

        currentLayoutMode = showGraph ? 1 : ((showCpu || showGpu || showRam) ? 2 : 3);

        overlay.addView(overlayContent);
        overlay.setPadding(dpScaled(8), dpScaled(3), dpScaled(8), dpScaled(3));

        int opacity = overlayPrefs.getInt("overlay_opacity", 85);
        overlayBackgroundColor = overlayPrefs.getInt(
                "overlay_background_color", Color.rgb(20, 25, 32));

        overlayBackground = background(Color.argb(
                (int) (255 * opacity / 100f),
                Color.red(overlayBackgroundColor),
                Color.green(overlayBackgroundColor),
                Color.blue(overlayBackgroundColor)));
        overlay.setBackground(overlayBackground);
    }

    private void addStripSeparator(LinearLayout stats) {
        TextView dot = createMetricView("·");
        dot.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dpScaled(4);
        params.rightMargin = dpScaled(4);
        stats.addView(dot, params);
    }

    // Adds a section, with a thin divider before it if it isn't the first
    private void addStripSection(View section) {
        if (overlayContent.getChildCount() > 0) {
            View d = new View(this);
            d.setBackgroundColor(DIVIDER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Math.max(1, dpScaled(1)), dpScaled(18));
            lp.leftMargin = dpScaled(6);
            lp.rightMargin = dpScaled(6);
            overlayContent.addView(d, lp);
        }
        overlayContent.addView(section);
    }

    private void buildClassicLayout(
            boolean showFps,
            boolean showTemperature,
            boolean showGraph,
            boolean showCpu,
            boolean showGpu,
            boolean showRam) {

        overlay.removeAllViews();
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        final boolean showCpuGpu = showCpu || showGpu;
        final boolean compactFps = showRam && showTemperature;

        // ---------- views (always created; the updater touches all of them) ----------
        overlayAccentColor = overlayPrefs.getInt(
                "overlay_accent_color", Color.rgb(69, 168, 245));

        fpsView = createMetricView("--");
        setOverlayTextSize(fpsView, compactFps ? 16 : 22);
        fpsView.setGravity(Gravity.CENTER);
        fpsView.setTextColor(overlayAccentColor);

        fpsLabelView = createMetricView("FPS");
        lastFpsSourceLabel = "";
        sourceLabelUntil = 0;
        TextView fpsLabel = fpsLabelView;
        setOverlayTextSize(fpsLabel, 6);
        fpsLabel.setTranslationY(-dpScaled(15));
        fpsLabel.setTranslationX(-dpScaled(4));   // label moves left

        temperatureView = createMetricView("--°C");
        temperatureView.setGravity(Gravity.CENTER);

        batteryIconView = new ImageView(this);
        batteryIconView.setImageResource(R.drawable.ic_battery_temp);
        batteryIconView.setScaleType(ImageView.ScaleType.FIT_CENTER);

        frameTimeView = createMetricView("--.- ms");
        frameTimeView.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        frameTimeGraph = new FrameTimeGraphView(this);

        cpuView = createMetricView("CPU --%");
        gpuView = createMetricView("GPU --%");
        ramView = createMetricView("RAM --%");
        cpuView.setGravity(Gravity.CENTER);
        gpuView.setGravity(Gravity.CENTER);
        ramView.setGravity(Gravity.CENTER);

        // ---------- widths, measured from the real font (worst-case text) ----------
        int fpsNumW = (int) Math.ceil(fpsView.getPaint().measureText("000"));
        int fpsLabelW = (int) Math.ceil(
                fpsLabel.getPaint().measureText("FPS · TIMESTATS"));

        int fpsRowW = compactFps ? fpsNumW : fpsNumW + dpScaled(3) + fpsLabelW;
        // Keep FPS compact only when it shares the left stack with RAM and temperature.
        fpsView.setTranslationX(compactFps ? 0f : (fpsLabelW + dpScaled(3)) / 2f);

        int tempTextW = (int) Math.ceil(
                temperatureView.getPaint().measureText("88.8°C")) + dpScaled(2);
        int tempRowW = dpScaled(11) + dpScaled(1) + tempTextW;

        int ramW = (int) Math.ceil(ramView.getPaint().measureText("RAM 100%"))
                + dpScaled(2);
        int slot = (int) Math.ceil(Math.max(
                cpuView.getPaint().measureText("CPU 100%"),
                gpuView.getPaint().measureText("GPU 100%"))) + dpScaled(2);
        int sepMargin = dpScaled(5);
        int sepW = (int) Math.ceil(cpuView.getPaint().measureText("|"))
                + sepMargin * 2;
        int cpuGpuRowW = (showCpu ? slot : 0) + (showGpu ? slot : 0)
                + (showCpu && showGpu ? sepW : 0);

        int leftW = Math.max(
                Math.max(showFps ? fpsRowW : 0, showTemperature ? tempRowW : 0),
                showRam ? ramW : 0);
        int rightW = showGraph
                ? Math.max(dpScaled(100), showCpuGpu ? cpuGpuRowW : 0)
                : slot;

        // ---------- heights ----------
        int fpsH = dpScaled(compactFps ? 24 : 38);
        int graphH = dpScaled(36);
        int tempH = dpScaled(16);
        int ramH = dpScaled(16);
        int cpuRowH = dpScaled(16);

        int lineH = dpScaled(1);
        int lineMargin = dpScaled(1);
        int stackH = (showCpu ? dpScaled(18) : 0)
                + (showGpu ? dpScaled(18) : 0)
                + (showCpu && showGpu ? lineH + lineMargin * 2 : 0);

        // Items with a partner in their column share rows; lone items center
        boolean leftTwo = showFps && showTemperature;
        boolean rightTwo = showGraph && showCpuGpu;

        int topH = Math.max(leftTwo ? fpsH : 0, rightTwo ? graphH : 0);
        int botH = Math.max(leftTwo ? tempH : 0, rightTwo ? cpuRowH : 0);

        int leftSingleH = showFps ? fpsH : (showTemperature ? tempH : 0);
        int rightSingleH = showGraph ? graphH : (showCpuGpu ? stackH : 0);

        boolean leftFpsRamPair = showFps && showRam
                && !showTemperature && showGraph && showCpuGpu;
        int leftStackH = leftFpsRamPair
                ? topH + botH
                : (showFps ? fpsH : 0)
                + (showRam ? ramH : 0)
                + (showTemperature ? tempH : 0);
        int contentH = showRam
                ? Math.max(leftStackH, rightTwo ? topH + botH : rightSingleH)
                : Math.max(topH + botH, Math.max(
                leftTwo ? 0 : leftSingleH,
                rightTwo ? 0 : rightSingleH));

        // ---------- left column: FPS over temp ----------
        LinearLayout fpsRow = new LinearLayout(this);
        fpsRow.setOrientation(LinearLayout.HORIZONTAL);
        fpsRow.setGravity(Gravity.CENTER);
        fpsRow.setClipChildren(false);
        fpsRow.setClipToPadding(false);
        if (!compactFps) {
            fpsRow.addView(fpsView, new LinearLayout.LayoutParams(
                    fpsNumW, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            labelParams.leftMargin = dpScaled(3);
            fpsRow.addView(fpsLabel, labelParams);
        }

        LinearLayout tempRow = new LinearLayout(this);
        tempRow.setOrientation(LinearLayout.HORIZONTAL);
        tempRow.setGravity(Gravity.CENTER_VERTICAL);
        tempRow.setClipChildren(false);
        tempRow.setClipToPadding(false);
        tempRow.addView(batteryIconView, new LinearLayout.LayoutParams(
                dpScaled(11), dpScaled(10)));
        LinearLayout.LayoutParams tempTextParams = new LinearLayout.LayoutParams(
                tempTextW, ViewGroup.LayoutParams.WRAP_CONTENT);
        tempTextParams.leftMargin = dpScaled(1);
        tempRow.addView(temperatureView, tempTextParams);

        leftColumn = makeColumn(leftW, contentH);

        if (showRam) {
            if (leftFpsRamPair) {
                // Mirror the original FPS / battery-temperature pairing:
                // RAM takes the battery-temperature row when temperature is off.
                View fpsContent = compactFps ? fpsView : fpsRow;
                leftColumn.addView(cellWithBottomLine(
                        fpsContent, topH, Gravity.CENTER));
                leftColumn.addView(cell(ramView, botH));
            } else {
                int remaining = (showFps ? 1 : 0) + 1
                        + (showTemperature ? 1 : 0);
                if (showFps) {
                    remaining--;
                    View fpsContent = compactFps ? fpsView : fpsRow;
                    leftColumn.addView(remaining > 0
                            ? cellWithBottomLine(fpsContent, fpsH, Gravity.CENTER)
                            : cell(fpsContent, fpsH));
                }
                if (showTemperature) {
                    remaining--;
                    leftColumn.addView(remaining > 0
                            ? cellWithBottomLine(tempRow, tempH, Gravity.CENTER)
                            : cell(tempRow, tempH));
                }
                remaining--;
                leftColumn.addView(cell(ramView, ramH));
            }
        } else if (leftTwo) {
            leftColumn.addView(cellWithBottomLine(fpsRow, topH, Gravity.CENTER));
            leftColumn.addView(cell(tempRow, botH));
        } else if (showFps) {
            leftColumn.addView(cell(fpsRow, contentH));      // alone: centered
        } else if (showTemperature) {
            leftColumn.addView(cell(tempRow, contentH));     // alone: centered
        }

        // ---------- right column: graph over CPU|GPU, or a centered stack ----------
        rightColumn = makeColumn(rightW, contentH);

        FrameLayout graphBlock = null;

        if (showGraph) {
            graphBlock = new FrameLayout(this);
            graphBlock.setClipChildren(false);
            graphBlock.addView(frameTimeGraph,
                    new FrameLayout.LayoutParams(rightW, graphH));
            graphBlock.addView(frameTimeView,
                    new FrameLayout.LayoutParams(
                            rightW, dpScaled(14), Gravity.TOP));
        }

        if (showCpuGpu) {
            cpuGpuRow = new LinearLayout(this);
            cpuGpuRow.setClipChildren(false);
            cpuGpuRow.setClipToPadding(false);

            if (showGraph) {
                // CPU | GPU under the graph
                cpuGpuRow.setOrientation(LinearLayout.HORIZONTAL);
                cpuGpuRow.setGravity(Gravity.CENTER_VERTICAL);

                if (showCpu) {
                    cpuGpuRow.addView(cpuView, new LinearLayout.LayoutParams(
                            slot, ViewGroup.LayoutParams.WRAP_CONTENT));
                }

                if (showCpu && showGpu) {
                    TextView separator = createMetricView("|");
                    separator.setGravity(Gravity.CENTER);
                    LinearLayout.LayoutParams sepParams =
                            new LinearLayout.LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT);
                    sepParams.leftMargin = sepMargin;
                    sepParams.rightMargin = sepMargin;
                    cpuGpuRow.addView(separator, sepParams);
                }

                if (showGpu) {
                    cpuGpuRow.addView(gpuView, new LinearLayout.LayoutParams(
                            slot, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
            } else {
                // stacked CPU / line / GPU
                cpuGpuRow.setOrientation(LinearLayout.VERTICAL);
                cpuGpuRow.setGravity(Gravity.CENTER_HORIZONTAL);

                if (showCpu) {
                    cpuGpuRow.addView(cpuView, new LinearLayout.LayoutParams(
                            slot, dpScaled(18)));
                }

                if (showCpu && showGpu) {
                    View line = new View(this);
                    line.setBackgroundColor(DIVIDER);
                    LinearLayout.LayoutParams lineParams =
                            new LinearLayout.LayoutParams(slot, lineH);
                    lineParams.topMargin = lineMargin;
                    lineParams.bottomMargin = lineMargin;
                    cpuGpuRow.addView(line, lineParams);
                }

                if (showGpu) {
                    cpuGpuRow.addView(gpuView, new LinearLayout.LayoutParams(
                            slot, dpScaled(18)));
                }
            }
        }

        if (rightTwo) {
            rightColumn.addView(cellWithBottomLine(
                    graphBlock, topH, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
            rightColumn.addView(cell(cpuGpuRow, botH));
        } else if (showGraph) {
            rightColumn.addView(cell(graphBlock, contentH));   // alone: centered
        } else if (showCpuGpu) {
            rightColumn.addView(cell(cpuGpuRow, contentH));    // stack: centered
        }

        // ---------- assemble ----------
        overlayContent = new LinearLayout(this);
        overlayContent.setOrientation(LinearLayout.HORIZONTAL);
        overlayContent.setGravity(Gravity.CENTER_VERTICAL);
        overlayContent.setClipChildren(false);
        overlayContent.setClipToPadding(false);

        boolean hasLeft = showFps || showTemperature || showRam;
        boolean hasRight = showGraph || showCpuGpu;

        if (hasLeft) {
            overlayContent.addView(leftColumn);
        }

        if (hasLeft && hasRight) {
            FrameLayout divider = new FrameLayout(this);
            View vLine = new View(this);
            vLine.setBackgroundColor(DIVIDER);
            divider.addView(vLine, new FrameLayout.LayoutParams(
                    Math.max(1, dpScaled(1)),
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER_HORIZONTAL));
            overlayContent.addView(divider,
                    new LinearLayout.LayoutParams(dpScaled(12), contentH));
        }

        if (hasRight) {
            overlayContent.addView(rightColumn);
        }

        currentLayoutMode = showGraph ? 1 : ((showCpuGpu || showRam) ? 2 : 3);

        overlay.addView(overlayContent);
        overlay.setPadding(dpScaled(7), dpScaled(6), dpScaled(7), dpScaled(6));

        int opacity = overlayPrefs.getInt("overlay_opacity", 85);

        overlayBackgroundColor = overlayPrefs.getInt(
                "overlay_background_color", Color.rgb(20, 25, 32));

        overlayBackground = background(
                Color.argb(
                        (int) (255 * opacity / 100f),
                        Color.red(overlayBackgroundColor),
                        Color.green(overlayBackgroundColor),
                        Color.blue(overlayBackgroundColor)));

        overlay.setBackground(overlayBackground);
    }

    private GradientDrawable background(int color) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(6));
        bg.setStroke(dp(1), Color.argb(100, 255, 255, 255));
        return bg;
    }

    private final Runnable reattachOverlayRunnable = () -> {
        if (serviceDestroyed || overlayRoot == null
                || windowManager == null
                || !overlayPrefs.getBoolean("overlay_enabled", false)) {
            return;
        }

        try {
            // The refresh loop survives a re-add on its own; only restart it
            // when it had stopped, or each re-add stacks another loop.
            boolean refreshLoopStopped = !attached;
            if (attached) {
                windowManager.removeViewImmediate(overlayRoot);
                attached = false;
            }
            windowManager.addView(overlayRoot, overlayParams);
            attached = true;
            if (refreshLoopStopped) {
                handler.removeCallbacks(updater);
                handler.post(updater);
            }
        } catch (Exception e) {
            // Keep the service alive; a later foreground handoff can retry.
            android.util.Log.w("FloatingFpsService",
                    "Unable to reattach overlay after app handoff", e);
            if (!serviceDestroyed
                    && overlayPrefs.getBoolean("overlay_enabled", false)) {
                handler.postDelayed(this::retryOverlayReattach, 1000);
            }
        }
    };

    private void retryOverlayReattach() {
        handler.post(reattachOverlayRunnable);
    }

    private void attachOverlay() {
        if (overlayPrefs == null
                || !overlayPrefs.getBoolean("overlay_enabled", false)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && !android.provider.Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }

        overlay = new LinearLayout(this);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setGravity(Gravity.TOP | Gravity.START);
        overlay.setClipChildren(false);
        overlay.setClipToPadding(false);

        boolean showFps = overlayPrefs.getBoolean("show_fps", true);
        boolean showTemperature =
                overlayPrefs.getBoolean("show_temperature", true);
        boolean showGraph =
                overlayPrefs.getBoolean("show_frame_time_graph", true);
        boolean showCpu = overlayPrefs.getBoolean("show_cpu", true);
        boolean showGpu = overlayPrefs.getBoolean("show_gpu", true);
        boolean showRam = overlayPrefs.getBoolean("show_ram", true);

        buildOverlayLayout(
                showFps,
                showTemperature,
                showGraph,
                showCpu,
                showGpu,
                showRam);

        overlayParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        overlayParams.gravity = Gravity.TOP | Gravity.START;
        overlayParams.x = dp(12);
        overlayParams.y = dp(140);

        x = overlayParams.x;
        y = overlayParams.y;

        overlay.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downRawX = event.getRawX();
                    downRawY = event.getRawY();
                    downX = x;
                    downY = y;
                    moved = false;
                    return true;

                case MotionEvent.ACTION_MOVE:
                    int dx = (int) (event.getRawX() - downRawX);
                    int dy = (int) (event.getRawY() - downRawY);

                    if (Math.abs(dx) > dp(4) || Math.abs(dy) > dp(4)) {
                        moved = true;
                    }

                    if (moved) {
                        x = downX + dx;
                        y = downY + dy;
                        clampOverlayPosition();

                        overlayParams.x = x;
                        overlayParams.y = y;
                        windowManager.updateViewLayout(overlayRoot, overlayParams);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!moved) {
                        toggleRecording();
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    return true;
            }

            return true;
        });

        try {
            overlayRoot = new ScaleFrame(this);
            overlayRoot.setClipChildren(false);
            overlayRoot.addView(overlay, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            overlay.setPivotX(0f);
            overlay.setPivotY(0f);
            applyScale(overlayPrefs.getInt("overlay_scale", 100));

            windowManager.addView(overlayRoot, overlayParams);
            attached = true;
            overlay.post(() -> {
                if (!attached) return;

                baseOverlayWidth = overlay.getWidth();
                baseOverlayHeight = overlay.getHeight();
            });
            handler.post(updater);
        } catch (Exception e) {
            stopSelf();
        }
    }

    private void toggleRecording() {
        if (overlayPrefs.getBoolean("overlay_engine_active", false)) {
            Intent intent = new Intent(this, FloatingFpsService.class);
            intent.setAction(ACTION_TOGGLE);
            startService(intent);
        } else {
            Intent intent = new Intent(ACTION_TOGGLE);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        }
    }

    private String[] readOverlayValues() {
        SharedPreferences livePrefs =
                getSharedPreferences("overlay_live", MODE_PRIVATE);

        String fps = livePrefs.getString("fps", "--");
        String temperature =
                livePrefs.getString("temperature", "--°C");
        String frameTime =
                livePrefs.getString("frame_time", "--.- ms");
        String cpu =
                livePrefs.getString("cpu_load", "--%");
        String gpu =
                livePrefs.getString("gpu_load", "--%");
        String ram =
                livePrefs.getString("ram_usage", "--%");
        String fpsSource = livePrefs.getString("fps_source", "");
        String thermalStatus = String.valueOf(livePrefs.getInt(
                "thermal_status", ThermalStatusReader.UNAVAILABLE));
        String cpuCoolingState = String.valueOf(livePrefs.getInt(
                "cpu_cooling_state", ThermalCoolingReader.UNAVAILABLE));
        String cpuCoolingMaxState = String.valueOf(livePrefs.getInt(
                "cpu_cooling_max_state", ThermalCoolingReader.UNAVAILABLE));
        String gpuCoolingState = String.valueOf(livePrefs.getInt(
                "gpu_cooling_state", ThermalCoolingReader.UNAVAILABLE));
        String gpuCoolingMaxState = String.valueOf(livePrefs.getInt(
                "gpu_cooling_max_state", ThermalCoolingReader.UNAVAILABLE));

        FpsDetector.Result live = backgroundSamplingEnabled && monitoringEngine != null
                ? monitoringEngine.getLatestFps() : null;
        if (live != null) {
            fps = String.valueOf(Math.round(live.fps));
            fpsSource = live.source;
            if (live.averageFrameTimeMs > 0) {
                frameTime = String.format(java.util.Locale.US,
                        "%.1f ms", live.averageFrameTimeMs);
            }
        }

        return new String[]{
                fps,
                temperature,
                frameTime,
                cpu,
                gpu,
                ram,
                thermalStatus,
                cpuCoolingState,
                cpuCoolingMaxState,
                gpuCoolingState,
                gpuCoolingMaxState,
                fpsSource
        };
    }

    private class FrameTimeGraphView extends View {

        private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        FrameTimeGraphView(android.content.Context context) {
            super(context);

            graphPaint.setStyle(Paint.Style.STROKE);
            graphPaint.setStrokeWidth(dpScaled(1.6f));
            graphPaint.setStrokeCap(Paint.Cap.ROUND);
            graphPaint.setStrokeJoin(Paint.Join.ROUND);

            fillPaint.setStyle(Paint.Style.FILL);
        }

        void addFrameTime(float frameTime, float fps, boolean recording) {
            if (frameTime >= 0) {
                frameHistory.add(frameTime);
                if (frameHistory.size() > 60) {
                    frameHistory.remove(0);
                }
            }
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            float width = getWidth();
            float height = getHeight();
            int n = frameHistory.size();

            if (width <= 0 || height <= 0 || n < 2) return;

            float maxFrameTime = currentStyle == STYLE_STRIP ? 20f : 50f;
            float[] px = new float[n];
            float[] py = new float[n];
            float minY = height;

            for (int i = 0; i < n; i++) {
                px[i] = (i / 59f) * width;
                float value = Math.min(frameHistory.get(i), maxFrameTime);
                py[i] = height - (value / maxFrameTime) * height;
                minY = Math.min(minY, py[i]);
            }

            // Smooth line (quadratic through midpoints)
            Path line = new Path();
            line.moveTo(px[0], py[0]);
            for (int i = 1; i < n; i++) {
                float midX = (px[i - 1] + px[i]) / 2f;
                float midY = (py[i - 1] + py[i]) / 2f;
                line.quadTo(px[i - 1], py[i - 1], midX, midY);
            }
            line.lineTo(px[n - 1], py[n - 1]);

            // Filled area under the line
            Path fill = new Path(line);
            fill.lineTo(px[n - 1], height);
            fill.lineTo(px[0], height);
            fill.close();

            int r = Color.red(overlayAccentColor);
            int g = Color.green(overlayAccentColor);
            int b = Color.blue(overlayAccentColor);

            // Stronger glow for strip mode
            float glowH = currentStyle == STYLE_STRIP
                    ? dpScaled(16)
                    : (height - minY);

            float glowEnd = Math.min(height, minY + glowH);

            fillPaint.setShader(new LinearGradient(
                    0, minY, 0, glowEnd,
                    Color.argb(
                            currentStyle == STYLE_STRIP ? 175 : 90,
                            r, g, b),
                    Color.argb(0, r, g, b),
                    Shader.TileMode.CLAMP));

            graphPaint.setColor(overlayAccentColor);

            canvas.drawPath(fill, fillPaint);
            canvas.drawPath(line, graphPaint);
        }
    }

    // Window root that reports a scaled size while its child keeps its natural size
    private static class ScaleFrame extends FrameLayout {
        private float contentScale = 1f;

        ScaleFrame(android.content.Context context) {
            super(context);
        }

        void setContentScale(float scale) {
            contentScale = scale;
            requestLayout();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int unspecified = View.MeasureSpec.makeMeasureSpec(
                    0, View.MeasureSpec.UNSPECIFIED);
            measureChildren(unspecified, unspecified);

            View child = getChildAt(0);
            int w = child != null ? child.getMeasuredWidth() : 0;
            int h = child != null ? child.getMeasuredHeight() : 0;

            setMeasuredDimension(
                    Math.round(w * contentScale),
                    Math.round(h * contentScale));
        }
    }

    private void clampOverlayPosition() {
        if (overlay == null) return;
        overlay.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int naturalW = overlay.getMeasuredWidth();
        int naturalH = overlay.getMeasuredHeight();
        if (naturalW <= 0 || naturalH <= 0) return;

        float scale = currentScale > 0 ? currentScale / 100f : 1f;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        int scaledW = Math.round(naturalW * scale);
        int scaledH = Math.round(naturalH * scale);
        x = Math.max(0, Math.min(x, Math.max(0, screenW - scaledW)));
        y = Math.max(0, Math.min(y, Math.max(0, screenH - scaledH)));
    }

    private void applyScale(int percent) {
        currentScale = percent;
        float s = percent / 100f;

        // Shrink further if the overlay is wider than the screen
        overlay.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int naturalW = overlay.getMeasuredWidth();
        int naturalH = overlay.getMeasuredHeight();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        if (naturalW > 0) {
            float maxScale = Math.max(0.1f, (screenW - dp(8)) / (float) naturalW);
            s = Math.min(s, maxScale);
        }

        overlay.setScaleX(s);
        overlay.setScaleY(s);
        overlayRoot.setContentScale(s);

        // Keep the complete scaled overlay inside the display bounds.
        if (naturalW > 0 && naturalH > 0) {
            int screenH = getResources().getDisplayMetrics().heightPixels;
            int scaledW = Math.round(naturalW * s);
            int scaledH = Math.round(naturalH * s);
            x = Math.max(0, Math.min(x, screenW - scaledW));
            y = Math.max(0, Math.min(y, screenH - scaledH));
            overlayParams.x = x;
            overlayParams.y = y;
        }

        if (attached) {
            windowManager.updateViewLayout(overlayRoot, overlayParams);
        }
    }

    private String compactFpsSourceLabel(String source) {
        if (source == null || source.isEmpty() || "none".equalsIgnoreCase(source)) {
            return "FPS";
        }
        if ("BLAST SF latency".equals(source)) return "FPS · BLAST";
        if ("SF latency".equals(source)) return "FPS · SF LAT";
        if ("SF Counter".equals(source)) return "FPS · SF COUNT";
        if ("TimeStats".equals(source)) return "FPS · TIMESTATS";
        return "FPS · " + source;
    }

    private float parseReading(String value) {
        try {
            return Float.parseFloat(
                    value.replaceAll("[^0-9.\\-]", ""));
        } catch (Exception e) {
            return -1f;
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int dpScaled(float value) {
        return (int) (value * overlayContentScale
                * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_ENGINE_HANDOFF.equals(action)
                || (intent == null && overlayPrefs.getBoolean(
                "overlay_enabled", false))) {
            startBackgroundSampling();
            // A fullscreen game may take the foreground while the existing
            // application-overlay window is still below its surface, so the
            // Activity asks for the window to be re-added once it has really
            // left the screen. Re-adding makes the overlay blink, so a plain
            // sampling handoff (dialogs, shade, permission prompts) never
            // does it, and requests are rate-limited.
            boolean wantsReattach = intent == null
                    || intent.getBooleanExtra(EXTRA_REATTACH, false);
            long nowMs = android.os.SystemClock.elapsedRealtime();
            if (wantsReattach && nowMs - lastReattachRequestMs > 3000) {
                lastReattachRequestMs = nowMs;
                handler.removeCallbacks(reattachOverlayRunnable);
                handler.postDelayed(reattachOverlayRunnable, 350);
            }
        } else if (ACTION_ENGINE_STOP.equals(action)) {
            stopBackgroundSampling();
        } else if (ACTION_TOGGLE.equals(action)) {
            toggleBackgroundRecording();
        } else if (ACTION_START_RECORDING.equals(action)) {
            setBackgroundRecording(true);
        } else if (ACTION_STOP_RECORDING.equals(action)) {
            setBackgroundRecording(false);
        }
        return START_STICKY;
    }

    private void toggleBackgroundRecording() {
        boolean currentlyRecording =
                sessionPrefs.getBoolean("recording", false);
        String owner = sessionPrefs.getString(
                "owner", SESSION_OWNER_NONE);

        // An app-owned session must be controlled by MainActivity.
        if (currentlyRecording && SESSION_OWNER_APP.equals(owner)) {
            Intent intent = new Intent(ACTION_TOGGLE);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            return;
        }

        setBackgroundRecording(!currentlyRecording);
    }

    private void setBackgroundRecording(boolean start) {
        boolean currentlyRecording =
                sessionPrefs.getBoolean("recording", false);
        String owner = sessionPrefs.getString(
                "owner", SESSION_OWNER_NONE);

        if (start && currentlyRecording) return;
        if (!start && (!currentlyRecording
                || !SESSION_OWNER_OVERLAY.equals(owner))) return;

        // Publish the shared state immediately. Recorder work is serialized
        // behind any sample already running on samplingExecutor.
        sessionPrefs.edit()
                .putBoolean("recording", start)
                .putString("owner", start
                        ? SESSION_OWNER_OVERLAY : SESSION_OWNER_NONE)
                .apply();

        try {
            samplingExecutor.execute(() -> {
                if (serviceDestroyed) return;

                if (start) {
                    // Match the dashboard path: CPU load must only be computed
                    // from samples taken inside the session, so discard any
                    // baseline carried over from before record was pressed.
                    monitoringEngine.resetCpuBaseline();
                    if (SessionDataPreferences.framePacingEnabled(this)) {
                        monitoringEngine.beginFrameCapture();
                    }
                    if (sessionRecorder != null) sessionRecorder.start();
                    if (sessionRecorder == null || !sessionRecorder.isRecording()) {
                        sessionPrefs.edit()
                                .putBoolean("recording", false)
                                .putString("owner", SESSION_OWNER_NONE)
                                .apply();
                    }
                } else {
                    monitoringEngine.endFrameCapture();
                    if (sessionRecorder != null) sessionRecorder.finish();
                }

                sendRecordingStateChanged();
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // The service is shutting down; no further recording work can run.
        }

        handler.removeCallbacks(updater);
        if (attached) handler.post(updater);
    }

    private void sendRecordingStateChanged() {
        Intent intent = new Intent(ACTION_RECORDING_STATE_CHANGED);
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    private void startBackgroundSampling() {
        if (!overlayPrefs.getBoolean("overlay_enabled", false)) return;
        backgroundSamplingEnabled = true;
        overlayPrefs.edit().putBoolean("overlay_engine_active", true).apply();
        handler.removeCallbacks(backgroundSampleTick);
        handler.postDelayed(backgroundSampleTick, 500);
    }

    private void stopBackgroundSampling() {
        backgroundSamplingEnabled = false;
        overlayPrefs.edit().putBoolean("overlay_engine_active", false).apply();
        handler.removeCallbacks(backgroundSampleTick);
        monitoringEngine.resetBaselines();
        monitoringEngine.stopFpsLoop();
    }

    private void notifyShizukuDisconnected() {
        handler.post(() -> {
            if (shizukuDisconnectToastShown) return;
            shizukuDisconnectToastShown = true;
            Toast.makeText(
                    getApplicationContext(),
                    "Shizuku connection lost. Overlay remains active with limited metrics.",
                    Toast.LENGTH_LONG).show();
        });
    }

    private void ensureBackgroundShizukuBinding() {
        if (monitoringEngine == null) return;

        boolean shizukuAvailable = Shizuku.pingBinder()
                && Shizuku.checkSelfPermission()
                == PackageManager.PERMISSION_GRANTED;
        if (!shizukuAvailable) {
            if (backgroundShizukuBound || backgroundShizukuBindRequested) {
                monitoringEngine.setShizukuService(null);
                backgroundShizukuBindRequested = false;
                backgroundShizukuBound = false;
                notifyShizukuDisconnected();
            }
            return;
        }

        if (backgroundShizukuBindRequested || backgroundShizukuBound) return;

        try {
            if (backgroundShizukuArgs == null) {
                backgroundShizukuArgs = new Shizuku.UserServiceArgs(
                        new ComponentName(getPackageName(),
                                ShizukuShellService.class.getName()))
                        .daemon(false)
                        .processNameSuffix("onyx-overlay")
                        .debuggable(false)
                        .version(1);
            }
            backgroundShizukuBindRequested = true;
            Shizuku.bindUserService(
                    backgroundShizukuArgs, backgroundShizukuConnection);
        } catch (Exception ignored) {
            backgroundShizukuBindRequested = false;
        }
    }

    @Override
    public void onDestroy() {
        // Stop recording immediately, then queue finalization behind any
        // in-flight sample. The recorder's synchronized finish() waits for
        // append() to complete without blocking the service's main thread.
        boolean finalizeSession = sessionPrefs != null && SESSION_OWNER_OVERLAY.equals(
                sessionPrefs.getString("owner", SESSION_OWNER_NONE));
        if (finalizeSession) {
            sessionPrefs.edit()
                    .putBoolean("recording", false)
                    .putString("owner", SESSION_OWNER_NONE)
                    .apply();
            if (sessionRecorder != null) {
                try {
                    samplingExecutor.execute(sessionRecorder::finish);
                } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    // The executor is already stopping; avoid blocking teardown.
                }
            }
        }

        serviceDestroyed = true;
        attached = false;
        backgroundSamplingEnabled = false;
        handler.removeCallbacksAndMessages(null);
        overlayExecutor.shutdownNow();
        // Drain queued recorder finalization and any in-flight sample safely.
        samplingExecutor.shutdown();
        if (monitoringEngine != null) {
            final MonitoringEngine engine = monitoringEngine;
            engine.stopFpsLoop();
            Thread cleanup = new Thread(engine::closeRootShell,
                    "OnyxRootShellCleanup");
            cleanup.setDaemon(true);
            cleanup.start();
        }
        Shizuku.removeBinderDeadListener(backgroundBinderDeadListener);
        if ((backgroundShizukuBindRequested || backgroundShizukuBound)
                && backgroundShizukuArgs != null) {
            try {
                Shizuku.unbindUserService(
                        backgroundShizukuArgs, backgroundShizukuConnection, true);
            } catch (Exception ignored) {
            }
        }

        if (overlayPrefs != null) {
            overlayPrefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }
        if (sessionPrefs != null) {
            sessionPrefs.unregisterOnSharedPreferenceChangeListener(prefListener);
        }

        if (overlayRoot != null && windowManager != null) {
            try {
                windowManager.removeView(overlayRoot);
            } catch (Exception ignored) {
            }
            overlayRoot = null;
        }
        overlay = null;

        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (overlayPrefs != null
                && overlayPrefs.getBoolean("overlay_enabled", false)) {
            startBackgroundSampling();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}