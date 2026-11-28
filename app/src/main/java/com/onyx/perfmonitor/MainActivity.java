/*
 * Copyright (C) 2026 Klod9s
 *
 * This file is part of ONYX Performance Monitor.
 *
 * ONYX Performance Monitor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * ONYX Performance Monitor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with ONYX Performance Monitor. If not, see <https://www.gnu.org/licenses/>.
 */

package com.onyx.perfmonitor;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import rikka.shizuku.Shizuku;

public class MainActivity extends Activity {
    public MainActivity() {
    }
    IOnyxUserService shizukuService;
    boolean shizukuBindRequested = false;

    private static final String PREFS = "overlay_settings";

    Button overlayButton;
    android.graphics.drawable.GradientDrawable customColorButtonBg;
    TextView customColorCheck;
    final ArrayList<Runnable> aboutRefreshers = new ArrayList<>();

    // TODO: put your real links here
    static final String URL_COFFEE = "https://buymeacoffee.com/klod9s";
    static final String URL_GITHUB = "https://github.com/Klod9s/ONYX";
    static final String URL_XDA = "https://xdaforums.com/t/app-root-shizuku-onyx-real-time-fps-frame-pacing-power-monitor.4810169/";


    View statusDot;
    private View headerLine;
    private View navDivider;
    android.graphics.drawable.GradientDrawable statusDotBg;
    TextView status, fps, power, current, voltage, battery;
    OnyxLogoView brand;

    // BOTTOM NAVIGATION
    android.widget.FrameLayout pageContainer;
    private ScrollView monitorPage, settingsPage, aboutPage;
    private View sessionsPage;
    RecyclerView sessionsList;
    SessionsAdapter sessionsAdapter;
    TextView sessionsEmpty;
    TextView sessionsCount;
    String pinnedFile = null;          // not saved anywhere, on purpose
    LinearLayout pinBanner;
    TextView pinBannerText, pinBannerCancel;
    android.graphics.drawable.GradientDrawable pinBannerBg;
    private LinearLayout bottomNavigation;
    int selectedTab = 0;
    TextView[] navLabels = new TextView[4];
    final ArrayList<TextView> appAccentTextViews =
            new ArrayList<>();

    final ArrayList<android.widget.RadioButton> appAccentRadioButtons =
            new ArrayList<>();

    final ArrayList<CheckBox> appAccentCheckBoxes =
            new ArrayList<>();

    final ArrayList<Button> appAccentButtons =
            new ArrayList<>();

    final ArrayList<android.widget.SeekBar> appAccentSeekBars =
            new ArrayList<>();
    private final NavigationItemBuilder navigationItemBuilder =
            new NavigationItemBuilder(this);
    private final AppAccentManager appAccentManager =
            new AppAccentManager(this);
    private final CardStyleManager cardStyleManager =
            new CardStyleManager(this);
    private final DialogStyleManager dialogStyleManager =
            new DialogStyleManager(this);
    private final OverlayStylePickerBuilder overlayStylePickerBuilder =
            new OverlayStylePickerBuilder(this);

    private volatile long lastEnsureMs = 0;

    android.widget.ImageView[] navIcons =
            new android.widget.ImageView[4];

    View[] navIndicators = new View[4];

    OverlayMetricsStore overlayMetricsStore;
    MonitoringEngine monitoringEngine;

    private ShizukuController shizukuController;

    private final Shizuku.OnBinderReceivedListener binderReceivedListener = () -> {
        cachedMonitoringMode = "";
        if (monitoringEngine != null) {
            monitoringEngine.resetMonitoringModeCheck();
        }

        runOnUiThread(() -> {
            shizukuBindRequested = false;
            startShizukuService();
        });
    };

    private final StatusPillManager statusPillManager = new StatusPillManager(this);

    void setStatus(String text, boolean error) {
        statusPillManager.setStatus(text, error);
    }

    void refreshStatusPill() {
        statusPillManager.refresh();
    }

    private final Shizuku.OnBinderDeadListener binderDeadListener = () -> {
        shizukuService = null;
        if (monitoringEngine != null) {
            monitoringEngine.setShizukuService(null);
        }
        shizukuBindRequested = false;

        runOnUiThread(() -> {
            setStatus("SHIZUKU · DISCONNECTED", true);
        });
    };
    TextView temperature, summary;
    private TextView packageName;
    private TextView powerState;
    TextView cpuTile, gpuTile;
    private String pendingExportFile;
    private boolean compareMode = false;
    private final java.util.ArrayList<String> selectedForCompare =
            new java.util.ArrayList<>();
    LinearLayout sessionsActions;

    LinearLayout overlaySettingsCard;
    TextView sessionsCancel;
    private Button floatingCompareButton;
    private final TextView[] cpuViews = new TextView[8];

    CheckBox showFps, showTemperature;
    Button startButton;
    private Button stopButton;

    final Handler handler = new Handler(Looper.getMainLooper());
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService fileWorker = Executors.newSingleThreadExecutor();
    private int historyGeneration = 0;
    final ArrayList<Sample> samples = new ArrayList<>();

    final StringBuilder csv = new StringBuilder();

    final java.util.ArrayList<LinearLayout> appColorOptions =
            new java.util.ArrayList<>();

    final java.util.ArrayList<TextView> appColorChecks =
            new java.util.ArrayList<>();

    final java.util.ArrayList<android.graphics.drawable.GradientDrawable>
            appColorPillBackgrounds = new java.util.ArrayList<>();

    android.graphics.drawable.Drawable customRainbowDrawable;

    boolean running = false;
    boolean overlaySessionUi = false;
    int sessionId = 0;
    boolean isAppForeground = false;
    String cachedMonitoringMode = "";


    private final BroadcastReceiver overlayToggleReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (FloatingFpsService.ACTION_TOGGLE.equals(intent.getAction())) {
                        if (isOverlaySession()) {
                            Intent stop = new Intent(
                                    MainActivity.this, FloatingFpsService.class);
                            stop.setAction(FloatingFpsService.ACTION_STOP_RECORDING);
                            startService(stop);
                        } else if (running) {
                            stopSession();
                            if (!isAppForeground
                                    && getPrefs().getBoolean("overlay_enabled", false)) {
                                handoffOverlaySampling();
                            }
                        } else {
                            stopOverlaySampling();
                            startSession();
                            wakeSampler();
                        }
                    } else if (FloatingFpsService.ACTION_RECORDING_STATE_CHANGED
                            .equals(intent.getAction())) {
                        sessionController.syncOverlaySessionState();
                    }
                }
            };


    OnyxHeroBannerView heroBanner;


    private final SamplingController samplingController =
            new SamplingController(this);

    private final OverlayController overlayController =
            new OverlayController(this);

    // Set once the service has been told to take over sampling, so leaving the
    // app (onPause then onDestroy) does not send the handoff twice.
    private boolean overlayHandedOff = false;

    private void handoffOverlaySampling() {
        handoffOverlaySampling(false);
    }

    private void handoffOverlaySampling(boolean reattachWindow) {
        overlayHandedOff = true;
        overlayController.handoffSampling(reattachWindow);
    }

    private void stopOverlaySampling() {
        overlayHandedOff = false;
        overlayController.stopSampling();
    }

    void scheduleSampler() {
        samplingController.schedule();
    }

    void wakeSampler() {
        samplingController.wake();
    }

    void startShizukuService() {
        shizukuController.startService();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1002);
        }
    }

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        shizukuController = new ShizukuController(this);
        sessionDeleteController = new SessionDeleteController(this);
        overlayMetricsStore = new OverlayMetricsStore(this);
        monitoringEngine = new MonitoringEngine(getApplicationContext());
        shizukuController.registerListeners();
        buildUi();
        setAnimationsEnabled(getSharedPreferences("app_settings", MODE_PRIVATE)
                .getBoolean("allow_animations", true));

        android.content.SharedPreferences sessionState =
                getSharedPreferences("session_state", MODE_PRIVATE);
        boolean recording = sessionState.getBoolean("recording", false);
        String owner = sessionState.getString(
                "owner", FloatingFpsService.SESSION_OWNER_NONE);
        if (recording
                && FloatingFpsService.SESSION_OWNER_OVERLAY.equals(owner)) {
            sessionController.restoreOverlaySession();
        } else if (recording) {
            // An app-owned session cannot be resumed after its in-memory samples
            // have been lost with the Activity process.
            sessionState.edit()
                    .putBoolean("recording", false)
                    .putString("owner", FloatingFpsService.SESSION_OWNER_NONE)
                    .apply();
        }

        IntentFilter filter =
                new IntentFilter(FloatingFpsService.ACTION_TOGGLE);
        filter.addAction(FloatingFpsService.ACTION_RECORDING_STATE_CHANGED);

        androidx.core.content.ContextCompat.registerReceiver(
                this,
                overlayToggleReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        );

        updateOverlayButton();

        refreshStatusPill();

        loadHistory();
        stopOverlaySampling();
        samplingController.wake();
        requestNotificationPermissionIfNeeded();
    }





    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(13, 18, 26));

        brand = new OnyxLogoView(this);

        pageContainer = new android.widget.FrameLayout(this);

        bottomNavigation = new LinearLayout(this);
        bottomNavigation.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavigation.setGravity(Gravity.CENTER_VERTICAL);
        bottomNavigation.setBackgroundColor(Color.rgb(19, 26, 36));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER);
        header.setPadding(0, dp(8), 0, dp(8));

// slightly lighter at the top (matches the bottom nav), fading into the page color
        header.setBackground(new android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{Color.rgb(21, 29, 41), Color.rgb(14, 20, 29)}));

// soft shadow over the content below it
        header.setElevation(dp(6));

        header.addView(brand, new LinearLayout.LayoutParams(
                dp(190),
                LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

// glowing accent line under the header
        headerLine = new View(this);
        updateHeaderLine();
        root.addView(headerLine, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)));

        root.addView(pageContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        navDivider = new View(this);
        updateHeaderLine();

        root.addView(navDivider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
        ));

        root.addView(bottomNavigation, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(64)
        ));

        setContentView(root);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(8), dp(18), dp(28));
        scroll.setBackground(GlassStyle.pageBackground(this));
        scroll.addView(page);

        monitorPage = scroll;
        pageContainer.addView(monitorPage, new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
        ));


        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            androidx.core.graphics.Insets bars = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.systemBars()
            );
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        page.setClipChildren(false);
        page.setClipToPadding(false);

new MonitorPageBuilder(this).build(page);
        // CREATE OTHER PAGES
        sessionsPage = buildSessionsPage();
        settingsPage = createPlaceholderPage("SETTINGS");
        buildSettingsContent();
        aboutPage = createPlaceholderPage("ABOUT");
        buildAboutContent();

// BOTTOM NAVIGATION ITEMS
        navigationItemBuilder.addNavigationItem(bottomNavigation, "Monitor", 0);
        navigationItemBuilder.addNavigationItem(bottomNavigation, "Sessions", 1);
        navigationItemBuilder.addNavigationItem(bottomNavigation, "Settings", 2);
        navigationItemBuilder.addNavigationItem(bottomNavigation, "About", 3);

// SHOW MONITOR BY DEFAULT
        showTab(0);
    }

    void updateHeaderLine() {
        int a = getAppAccentColor();
        int r = Color.red(a), g = Color.green(a), b = Color.blue(a);

        // Reuse the same soft accent fade above and below the content.
        android.graphics.drawable.GradientDrawable divider =
                new android.graphics.drawable.GradientDrawable(
                        android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{
                                Color.argb(0, r, g, b),
                                Color.argb(210, r, g, b),
                                Color.argb(0, r, g, b)});

        if (headerLine != null) headerLine.setBackground(divider);
        if (navDivider != null) {
            navDivider.setBackground(new android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                    new int[]{
                            Color.argb(0, r, g, b),
                            Color.argb(210, r, g, b),
                            Color.argb(0, r, g, b)}));
        }
    }



    private ScrollView createPlaceholderPage(String title) {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(13, 18, 26));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(8), dp(18), dp(28));

        TextView heading = text(title, 20, true, Color.WHITE);
        add(content, heading, 0, 8);

        scroll.addView(content);
        return scroll;
    }


    private View buildSessionsPage() {
        return new SessionsPageBuilder(this).build();
    }


    private void buildSettingsContent() {
        new SettingsContentBuilder(this).build(settingsPage);
    }

    private void buildAboutContent() {
        new AboutContentBuilder(this).build(aboutPage);
    }











    void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "No app available to open this link",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private TextView pillLabel(LinearLayout pill) {
        for (int c = 0; c < pill.getChildCount(); c++) {
            View child = pill.getChildAt(c);
            if (child instanceof TextView) return (TextView) child;
        }
        return null;
    }


    final ArrayList<Runnable> overlayColorRefreshers = new ArrayList<>();

    void refreshOverlayColorRows() {
        for (Runnable r : overlayColorRefreshers) r.run();
    }

    void addOverlayDivider(LinearLayout card) {
        View d = new View(this);
        d.setBackgroundColor(Color.rgb(43, 52, 65));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        p.setMargins(0, dp(2), 0, dp(10));
        card.addView(d, p);
    }

    private final OverlayColorRowBuilder overlayColorRowBuilder =
            new OverlayColorRowBuilder(this);

    void addOverlayColorRow(LinearLayout card, String prefKey,
                            int onyxColor, String dialogTitle,
                            int bottomMargin) {
        overlayColorRowBuilder.add(
                card, prefKey, onyxColor, dialogTitle, bottomMargin);
    }

    ItemTouchHelper.Callback createSessionsSwipeCallback() {
        return new SessionsSwipeCallback(this);
    }



    void updateAppColorSelection() {
        int currentColor = getAppAccentColor();

        int[] presetColors = {
                Color.rgb(69, 168, 245),
                Color.rgb(101, 214, 165),
                Color.rgb(167, 139, 250),
                Color.rgb(241, 107, 117)
        };

        boolean isPreset = false;

        for (int i = 0; i < appColorOptions.size(); i++) {
            boolean selected = currentColor == presetColors[i];
            if (selected) isPreset = true;

            appColorPillBackgrounds.get(i).setStroke(
                    dp(selected ? 2 : 1),
                    selected ? currentColor : Color.rgb(55, 65, 80));

            appColorChecks.get(i).setVisibility(
                    selected ? View.VISIBLE : View.GONE);
        }

        if (customColorButtonBg != null) {
            customColorButtonBg.setStroke(
                    dp(!isPreset ? 2 : 1),
                    !isPreset ? currentColor : Color.rgb(55, 65, 80));
        }

        if (customColorCheck != null) {
            customColorCheck.setVisibility(!isPreset ? View.VISIBLE : View.GONE);
        }
    }


    void registerNavigationViews(int tab, android.widget.ImageView icon,
                                 TextView label, View indicator) {
        navIcons[tab] = icon;
        navLabels[tab] = label;
        navIndicators[tab] = indicator;
    }

    void showTab(int tab) {
        finalizePendingDeletes();
        pinnedFile = null;
        updatePinUi(false);
        int previousTab = selectedTab;
        selectedTab = tab;
        if (previousTab != tab && samplingController != null) {
            samplingController.onMonitorTabChanged();
        }

        View selectedPage;

        switch (tab) {
            case 1:
                selectedPage = sessionsPage;
                break;
            case 2:
                selectedPage = settingsPage;
                break;
            case 3:
                selectedPage = aboutPage;
                break;
            default:
                selectedPage = monitorPage;
                break;
        }

        pageContainer.removeAllViews();

        pageContainer.addView(selectedPage,
                new android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                ));

        int accentColor = getAppAccentColor();

        for (int i = 0; i < navLabels.length; i++) {
            int color = i == tab ? accentColor : Color.rgb(145, 158, 175);
            navLabels[i].setTextColor(color);
            navIcons[i].setColorFilter(color);
            navIndicators[i].setBackgroundColor(i == tab ? color : Color.TRANSPARENT);
        }

// Rebuild the cards so they pick up the current accent
        if (tab == 1 && sessionsAdapter != null) {
            loadHistory(true);
        }
    }

    LinearLayout tabHeader(int iconRes, String titleText) {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(44));

        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(iconRes);
        icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(22), dp(22));
        ip.rightMargin = dp(10);
        header.addView(icon, ip);

        TextView title = text(titleText, 20, true, Color.WHITE);
        title.setSingleLine(true);
        title.setTranslationY(dp(-1));
        header.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        return header;
    }


    int getAppAccentColor() {
        return AppColors.accent(this);
    }

    void updateAppAccentColor() {
        appAccentManager.update();
    }

    void styleCheckBox(CheckBox box) {
        box.setTextColor(Color.WHITE);
        box.setButtonTintList(
                android.content.res.ColorStateList.valueOf(
                        getAppAccentColor()));

        appAccentCheckBoxes.add(box);
    }

    void addStylePicker(LinearLayout card) {
        overlayStylePickerBuilder.build(card);
    }

    void showCustomColorPicker(String title, int currentColor,
                                       java.util.function.IntConsumer onApply) {
        new CustomColorPickerDialog(this).show(title, currentColor, onApply);
    }

    android.graphics.drawable.GradientDrawable cardBg(int radiusDp) {
        return cardStyleManager.createCardBackground(radiusDp);
    }

    GlassStyle.GlassDrawable glassBg(int radiusDp) {
        return cardStyleManager.createGlassBackground(radiusDp);
    }

    GlassStyle.GlassDrawable flatBg(int radiusDp) {
        return cardStyleManager.createFlatBackground(radiusDp);
    }

    void refreshCardStrokes() {
        cardStyleManager.refreshStrokes();
    }

    void showCustomAppColorPicker() {
        showCustomColorPicker("Custom App Color", getAppAccentColor(), color -> {
            getSharedPreferences("app_settings", MODE_PRIVATE)
                    .edit().putInt("app_accent_color", color).apply();
            updateAppAccentColor();
            updateAppColorSelection();
        });
    }


    SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    /** True while the floating overlay is switched on and needs live readings. */
    boolean isOverlayEnabled() {
        return getPrefs().getBoolean("overlay_enabled", false);
    }

    @Override
    protected void onResume() {
        super.onResume();

        isAppForeground = true;
        if (brand != null) {
            brand.startShine();
        }

        monitoringEngine.resetMonitoringModeCheck();
        updateOverlayButton();
        if (isOverlaySession()) {
            handoffOverlaySampling();
        } else {
            stopOverlaySampling();
        }
        wakeSampler();
    }

    @Override
    protected void onPause() {
        super.onPause();

        isAppForeground = false;
        if (!running || isOverlaySession()) handoffOverlaySampling();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // The Activity has really left the screen (not just a dialog or the
        // notification shade), so refresh the overlay window's z-order once.
        if (overlayHandedOff) overlayController.requestReattach();
        if (brand != null) {
            brand.stopShine();
        }
        finalizePendingDeletes();
    }

    boolean isOverlaySession() {
        android.content.SharedPreferences state =
                getSharedPreferences("session_state", MODE_PRIVATE);
        return state.getBoolean("recording", false)
                && FloatingFpsService.SESSION_OWNER_OVERLAY.equals(
                        state.getString("owner",
                                FloatingFpsService.SESSION_OWNER_NONE));
    }

    private void updateOverlayButton() {
        overlayController.updateButton();
    }

    android.app.AlertDialog.Builder darkDialogBuilder() {
        return new android.app.AlertDialog.Builder(
                this,
                android.app.AlertDialog.THEME_DEVICE_DEFAULT_DARK);
    }

    void showDarkDialog(android.app.AlertDialog.Builder builder,
                         boolean destructive) {
        dialogStyleManager.show(builder, destructive);
    }

    void toggleOverlay() {
        overlayController.toggle();
    }

    TextView section(String value) {
        TextView heading = text(value, 13, true, getAppAccentColor());
        heading.setCompoundDrawablesRelativeWithIntrinsicBounds(
                R.drawable.ic_section_mark, 0, 0, 0);
        heading.setCompoundDrawablePadding(dp(8));
        heading.setCompoundDrawableTintList(
                android.content.res.ColorStateList.valueOf(getAppAccentColor()));
        appAccentTextViews.add(heading);
        return heading;
    }

    void compactButton(Button b) {
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(6), 0, dp(6), 0);
        b.setSingleLine(true);
        b.setEllipsize(null);
    }

    TextView text(String value, int size, boolean bold, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);

        if (bold) {
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        }

        return view;
    }

    void add(LinearLayout parent, View view, int width, int bottom) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        width == 0 ? -1 : width, -2);

        params.bottomMargin = dp(bottom);
        parent.addView(view, params);
    }

    int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    void setPowerStateView(TextView view) {
        powerState = view;
    }

    void refreshPowerStateBadge() {
        PowerStateBadgeStyle.refresh(powerState, getAppAccentColor());
    }

    private final SessionController sessionController =
            new SessionController(this);

    void toggleSessionFromMonitor() {
        sessionController.toggle();
    }

    private void startSession() {
        sessionController.startSession();
    }

    private void stopSession() {
        sessionController.stopSession();
    }

    private String buildCsv(String result) {
        return SessionCsvFormatter.buildSessionCsv(
                csv, result, cachedMonitoringMode);
    }

    void render(Sample s) {
        if (selectedTab != 0) return;
        MonitorSampleRenderer.render(
                s, fps, cpuTile, gpuTile, power, powerState,
                current, voltage, battery, temperature, getAppAccentColor());
    }

    void setAnimationsEnabled(boolean enabled) {
        if (brand != null) {
            brand.setAnimationsEnabled(enabled);
            if (enabled && isAppForeground) brand.startShine();
        }
        TextView[] tiles = {fps, cpuTile, gpuTile, current, voltage, battery, temperature};
        for (TextView tile : tiles) {
            if (tile == null || !(tile.getTag() instanceof GlassStyle.TileTag)) continue;
            GlassStyle.TileTag tag = (GlassStyle.TileTag) tile.getTag();
            if (tag.matrix != null) tag.matrix.setAnimationsEnabled(enabled);
            if (tag.graph != null) tag.graph.setAnimationsEnabled(enabled);
        }
    }


    String makeSummary() {
        return SessionSummaryFormatter.build(samples, this);
    }

    void checkDeviceCapabilities(
            java.util.function.Consumer<java.util.List<DeviceCapabilityCheck.Row>> done) {
        // Stop the regular sampler so the check does not race with it.
        samplingController.stop();

        worker.execute(() -> {
            java.util.List<Sample> samples = new java.util.ArrayList<>();

            // Several readings: some metrics need the FPS loop to warm up, and
            // a single miss should not mark a metric as unsupported.
            for (int i = 0; i < 4; i++) {
                try {
                    Sample sample = monitoringEngine.sample(false);
                    if (sample != null) samples.add(sample);
                } catch (Exception ignored) {
                }
                if (i < 3) {
                    try {
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            java.util.List<DeviceCapabilityCheck.Row> rows =
                    DeviceCapabilityCheck.evaluate(samples);

            runOnUiThread(() -> {
                done.accept(rows);
                samplingController.wake();
            });
        });
    }

    void saveSession(String result) {
        final String sessionCsv = buildCsv(result);

        fileWorker.execute(() -> {
            File sessionFile = null;
            boolean saved = false;
            try {
                String stamp = new SimpleDateFormat(
                        "yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
                String baseName = "session_" + stamp;
                sessionFile = new File(getFilesDir(), baseName + ".csv");

                // Reserve a unique filename so a second session in the same
                // second cannot overwrite an existing recording.
                int suffix = 1;
                while (!sessionFile.createNewFile()) {
                    sessionFile = new File(
                            getFilesDir(), baseName + "-" + suffix++ + ".csv");
                }

                try (FileOutputStream out = new FileOutputStream(sessionFile)) {
                    out.write(sessionCsv.getBytes(StandardCharsets.UTF_8));
                }
                saved = true;
            } catch (Exception e) {
                if (sessionFile != null) {
                    sessionFile.delete();
                }
            }

            final boolean saveSucceeded = saved;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (saveSucceeded) {
                    loadHistory();
                    Toast.makeText(this, "Session saved on this device",
                            Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Could not save session",
                            Toast.LENGTH_LONG).show();
                }
            });
        });
    }



    void executeFileTask(Runnable task) {
        fileWorker.execute(task);
    }

    void loadHistory() {
        loadHistory(false);
    }

    void loadHistory(boolean scrollToTop) {
        if (sessionsAdapter == null) return;
        final File folder = getFilesDir();
        if (folder == null) return;

        // Snapshot on the main thread; pending deletes are only mutated there.
        final java.util.HashSet<String> hidden =
                new java.util.HashSet<>(sessionDeleteController.getPendingDeletes());
        final int generation = ++historyGeneration;

        fileWorker.execute(() -> {
            java.util.List<SessionItem> items =
                    SessionFileManager.loadHistoryItems(this, folder, hidden);

            runOnUiThread(() -> {
                if (generation != historyGeneration) return;
                if (sessionsAdapter == null) return;
                sessionsAdapter.submit(items);
                if (scrollToTop && sessionsList != null) {
                    sessionsList.scrollToPosition(0);
                }
                if (sessionsCount != null) {
                    sessionsCount.setText(items.size() + " SAVED");
                }
                boolean empty = items.isEmpty();
                sessionsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                sessionsList.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    void importSession() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, 44);
    }

    private void importFromUri(android.net.Uri uri) {
        Toast.makeText(this, "Importing…", Toast.LENGTH_SHORT).show();

        fileWorker.execute(() -> {
            SessionFileManager.ImportResult result =
                    SessionFileManager.importSession(
                            getContentResolver(),
                            uri,
                            getFilesDir(),
                            getSharedPreferences("session_meta", MODE_PRIVATE));

            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (result.success) loadHistory();
                Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
            });
        });
    }

    private void exportPastSession(String filename) {
        pendingExportFile = filename;

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/csv");
        intent.putExtra(Intent.EXTRA_TITLE, filename);

        startActivityForResult(intent, 43);
    }

    // ---------- Pending deletes (undo) ----------

    private SessionDeleteController sessionDeleteController;

    private void queueDelete(java.util.Collection<String> names, boolean alsoHistoryFile) {
        sessionDeleteController.queueDelete(names, alsoHistoryFile);
    }

    void deleteSessionWithUndo(String fileName) {
        queueDelete(java.util.Collections.singletonList(fileName), false);
    }

    void showSessionMenu(View anchor, String fileName) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(35, 44, 59));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.rgb(47, 59, 78));
        menu.setBackground(bg);

        final android.widget.PopupWindow popup =
                new android.widget.PopupWindow(menu, dp(180),
                        LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));

        menu.addView(menuRow("Export CSV", Color.WHITE, () -> {
            popup.dismiss();
            exportPastSession(fileName);
        }));
        if (pinnedFile == null) {
            menu.addView(menuRow("Compare with…", Color.WHITE, () -> {
                popup.dismiss();
                setPinned(fileName);
            }));
        }
        menu.addView(menuRow("Delete", Color.rgb(240, 104, 122), () -> {
            popup.dismiss();
            deleteSessionWithUndo(fileName);
        }));

        popup.showAsDropDown(anchor, 0, 0, Gravity.END);
    }

    void onSessionTapped(String name) {
        if (pinnedFile == null) {
            Intent intent = new Intent(this, SessionAnalysisActivity.class);
            intent.putExtra("session_filename", name);
            startActivity(intent);
        } else if (pinnedFile.equals(name)) {
            pinnedFile = null;            // tapping the pinned card unpins it
            updatePinUi(true);
        } else {
            launchPinnedCompare(name);
        }
    }

    void setPinned(String name) {
        if (visibleSessionNames().size() < 2) {
            Toast.makeText(this, "Need at least 2 saved sessions to compare",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        pinnedFile = name;
        updatePinUi(true);
    }

    void launchPinnedCompare(String second) {
        Intent i = new Intent(this, SessionCompareActivity.class);
        i.putExtra("a", pinnedFile);
        i.putExtra("b", second);
        pinnedFile = null;
        updatePinUi(true);
        startActivity(i);
    }

    /** Shows or hides the banner. rebind = redraw the cards (glow / badge). */
    void updatePinUi(boolean rebind) {
        if (pinBanner == null) return;

        int accent = getAppAccentColor();
        pinBannerBg.setColor(Color.argb(30,
                Color.red(accent), Color.green(accent), Color.blue(accent)));
        pinBannerBg.setStroke(dp(1), Color.argb(100,
                Color.red(accent), Color.green(accent), Color.blue(accent)));
        pinBannerText.setTextColor(accent);
        pinBanner.setVisibility(pinnedFile != null ? View.VISIBLE : View.GONE);

        if (rebind && sessionsAdapter != null) {
            sessionsAdapter.notifyDataSetChanged();
        }
    }

    android.widget.ImageButton headerIcon(int res, String description,
                                                  View.OnClickListener click) {
        android.widget.ImageButton b = new android.widget.ImageButton(this);
        b.setImageResource(res);
        b.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        b.setContentDescription(description);
        b.setBackgroundColor(Color.TRANSPARENT);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        b.setOnClickListener(click);
        b.setTooltipText(description);
        return b;
    }


    void showTopMenu(View anchor) {
        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6), dp(6), dp(6), dp(6));

        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.rgb(35, 44, 59));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.rgb(47, 59, 78));
        menu.setBackground(bg);

        final android.widget.PopupWindow popup =
                new android.widget.PopupWindow(menu, dp(190),
                        LinearLayout.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));

        // Temporary: goes away when swipe-to-compare arrives
        menu.addView(menuRow("Export all", Color.WHITE, () -> {
            popup.dismiss();
            exportAllSessions();
        }));
        menu.addView(menuRow("Delete all", Color.rgb(240, 104, 122), () -> {
            popup.dismiss();
            clearSessionHistory();
        }));

        popup.showAsDropDown(anchor, 0, 0, Gravity.END);
    }

    private void exportAllSessions() {
        if (visibleSessionNames().isEmpty()) {
            Toast.makeText(this, "No sessions to export", Toast.LENGTH_SHORT).show();
            return;
        }

        String stamp = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        intent.putExtra(Intent.EXTRA_TITLE, "onyx-sessions-" + stamp + ".zip");
        startActivityForResult(intent, 45);
    }

    private void writeAllSessionsZip(Uri uri) {
        final java.util.List<String> names = visibleSessionNames();

        fileWorker.execute(() -> {
            try {
                int exported = SessionFileManager.writeSessionsZip(
                        getContentResolver(), uri, getFilesDir(), names);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Exported " + exported + " sessions",
                            Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, "Export failed: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private TextView menuRow(String label, int color, Runnable action) {
        TextView row = text(label, 14, false, color);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), 0, dp(12), 0);
        row.setMinHeight(dp(44));
        row.setOnClickListener(v -> action.run());
        return row;
    }

    java.util.List<String> visibleSessionNames() {
        // The adapter is populated by the background history loader. Reuse
        // that in-memory snapshot instead of listing files on the UI thread.
        return sessionsAdapter != null
                ? sessionsAdapter.fileNames()
                : new java.util.ArrayList<>();
    }

    void finalizePendingDeletes() {
        sessionDeleteController.finalizePendingDeletes();
    }

    private void clearSessionHistory() {
        java.util.List<String> names = visibleSessionNames();

        if (names.isEmpty()) {
            Toast.makeText(this, "No sessions to clear", Toast.LENGTH_SHORT).show();
            return;
        }

        showDarkDialog(
                darkDialogBuilder()
                        .setTitle("Delete all " + names.size() + " sessions?")
                        .setMessage("They'll be removed from this device. You can undo for a few seconds.")
                        .setNegativeButton("CANCEL", null)
                        .setPositiveButton("DELETE ALL",
                                (dialog, which) -> queueDelete(names, true)),
                true);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 45 && result == RESULT_OK
                && data != null && data.getData() != null) {
            writeAllSessionsZip(data.getData());
            return;
        }

        if (request == 44 && result == RESULT_OK
                && data != null && data.getData() != null) {
            importFromUri(data.getData());
            return;
        }

        if (request == 43 && result == RESULT_OK
                && data != null && data.getData() != null) {
            final Uri destination = data.getData();
            final String filename = pendingExportFile;
            pendingExportFile = null;

            fileWorker.execute(() -> {
                String message;
                boolean success;
                try {
                    SessionFileManager.copySessionToUri(
                            getContentResolver(),
                            destination,
                            getFilesDir(),
                            filename);
                    message = "Session exported successfully!";
                    success = true;
                } catch (Exception e) {
                    message = "Export failed: " + e.getMessage();
                    success = false;
                }

                final String resultMessage = message;
                final boolean exportSucceeded = success;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, resultMessage,
                            exportSucceeded ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG)
                            .show();
                });
            });
            return;
        }

        if (request == 42 && result == RESULT_OK && data != null
                && data.getData() != null) {
            final Uri destination = data.getData();
            final String exportCsv = buildCsv(makeSummary());

            fileWorker.execute(() -> {
                boolean exported = false;
                try (OutputStream output =
                             getContentResolver().openOutputStream(destination)) {
                    if (output == null) {
                        throw new java.io.IOException("Could not open destination.");
                    }
                    output.write(exportCsv.getBytes(StandardCharsets.UTF_8));
                    exported = true;
                } catch (Exception ignored) {
                    exported = false;
                }

                final boolean exportSucceeded = exported;
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    Toast.makeText(this,
                            exportSucceeded ? "CSV exported" : "Export failed",
                            exportSucceeded ? Toast.LENGTH_SHORT : Toast.LENGTH_LONG)
                            .show();
                });
            });
        }
    }

    @Override
    protected void onDestroy() {
        shizukuController.unbindService();
        shizukuController.unregisterListeners();

        running = false;
        samplingController.stop();
        if (!overlayHandedOff) handoffOverlaySampling(true);
        worker.shutdownNow();
        // Drain queued session saves/deletes/exports instead of cancelling file work.
        fileWorker.shutdown();
        if (monitoringEngine != null) {
            final MonitoringEngine engine = monitoringEngine;
            engine.stopFpsLoop();
            Thread cleanup = new Thread(engine::closeRootShell,
                    "OnyxRootShellCleanup");
            cleanup.setDaemon(true);
            cleanup.start();
        }
        try {
            unregisterReceiver(overlayToggleReceiver);
        } catch (IllegalArgumentException ignored) {
        }

        super.onDestroy();
    }
}
