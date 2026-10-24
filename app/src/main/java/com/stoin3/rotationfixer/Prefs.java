package com.stoin3.rotationfixer;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

/** Small wrapper around the app's SharedPreferences plus permission checks. */
final class Prefs {
    static final String KEY_ENABLED = "enabled";
    static final String KEY_ALLOW_UPSIDE_DOWN = "allow_upside_down";
    static final String KEY_AUTO_HIDE = "auto_hide";
    static final String KEY_POSITION = "position";

    static final int POS_BOTTOM_RIGHT = 0;
    static final int POS_BOTTOM_LEFT = 1;
    static final int POS_TOP_RIGHT = 2;
    static final int POS_TOP_LEFT = 3;

    private Prefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static boolean isEnabled(Context context) {
        return get(context).getBoolean(KEY_ENABLED, true);
    }

    static boolean allowUpsideDown(Context context) {
        return get(context).getBoolean(KEY_ALLOW_UPSIDE_DOWN, false);
    }

    static boolean autoHide(Context context) {
        return get(context).getBoolean(KEY_AUTO_HIDE, false);
    }

    static int position(Context context) {
        return get(context).getInt(KEY_POSITION, POS_BOTTOM_RIGHT);
    }

    static boolean canDrawOverlays(Context context) {
        return Settings.canDrawOverlays(context);
    }

    static boolean canWriteSettings(Context context) {
        return Settings.System.canWrite(context);
    }

    static boolean hasRequiredPermissions(Context context) {
        return canDrawOverlays(context) && canWriteSettings(context);
    }

    static boolean needsNotificationPermission() {
        return Build.VERSION.SDK_INT >= 33;
    }
}
