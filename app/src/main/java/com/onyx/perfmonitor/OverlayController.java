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

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

final class OverlayController {
    private static final String PREFS = "overlay_settings";

    private final MainActivity activity;

    OverlayController(MainActivity activity) {
        this.activity = activity;
    }

    void handoffSampling(boolean reattachWindow) {
        if (!getPrefs().getBoolean("overlay_enabled", false)) return;
        getPrefs().edit().putBoolean("overlay_engine_active", true).apply();

        Intent intent = new Intent(activity, FloatingFpsService.class);
        intent.setAction(FloatingFpsService.ACTION_ENGINE_HANDOFF);
        intent.putExtra(FloatingFpsService.EXTRA_REATTACH, reattachWindow);
        try {
            activity.startService(intent);
        } catch (Exception ignored) {
        }
    }

    /** Asks the service to re-add the overlay window above the new foreground app. */
    void requestReattach() {
        handoffSampling(true);
    }

    void stopSampling() {
        if (!getPrefs().getBoolean("overlay_enabled", false)) return;
        getPrefs().edit().putBoolean("overlay_engine_active", false).apply();

        Intent intent = new Intent(activity, FloatingFpsService.class);
        intent.setAction(FloatingFpsService.ACTION_ENGINE_STOP);
        try {
            activity.startService(intent);
        } catch (Exception ignored) {
        }
    }

    boolean isServiceRunning() {
        android.app.ActivityManager manager =
                (android.app.ActivityManager) activity.getSystemService(
                        Context.ACTIVITY_SERVICE);

        for (android.app.ActivityManager.RunningServiceInfo service
                : manager.getRunningServices(Integer.MAX_VALUE)) {
            if (FloatingFpsService.class.getName()
                    .equals(service.service.getClassName())) {
                return true;
            }
        }

        return false;
    }

    void updateButton() {
        boolean enabled = isServiceRunning();
        getPrefs().edit().putBoolean("overlay_enabled", enabled).apply();

        if (activity.overlayButton != null) {
            activity.overlayButton.setText(enabled
                    ? "TURN OFF OVERLAY"
                    : "TURN ON OVERLAY");
        }
    }

    void toggle() {
        boolean enabled = isServiceRunning();

        if (enabled) {
            activity.stopService(
                    new Intent(activity, FloatingFpsService.class));
            getPrefs().edit()
                    .putBoolean("overlay_enabled", false)
                    .putBoolean("overlay_engine_active", false)
                    .apply();

            if (activity.overlayButton != null) {
                activity.overlayButton.setText("TURN ON OVERLAY");
            }
            Toast.makeText(activity, "Floating overlay disabled",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        if (!Settings.canDrawOverlays(activity)) {
            Intent intent = new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
            return;
        }

        Intent serviceIntent =
                new Intent(activity, FloatingFpsService.class);

        // Persist user intent before service creation so onCreate() can safely
        // decide whether the overlay window should be attached.
        getPrefs().edit().putBoolean("overlay_enabled", true).apply();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            activity.startForegroundService(serviceIntent);
        } else {
            activity.startService(serviceIntent);
        }
        if (activity.overlayButton != null) {
            activity.overlayButton.setText("TURN OFF OVERLAY");
        }
        activity.wakeSampler();

        Toast.makeText(activity, "Floating overlay enabled",
                Toast.LENGTH_SHORT).show();
    }

    private android.content.SharedPreferences getPrefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
