package com.example.dsbalance;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Remembers which theme the user picked and turns that choice into a Context
 * whose resources resolve the matching day/night values.
 *
 * Modes are stored per app, not per activity, so the choice survives restarts.
 */
final class ThemePrefs {

    static final int MODE_SYSTEM = 0;
    static final int MODE_LIGHT = 1;
    static final int MODE_DARK = 2;

    private static final String FILE = "dsbalance";
    private static final String KEY_THEME = "theme_mode";

    private final SharedPreferences sp;

    ThemePrefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    int getMode() {
        return sp.getInt(KEY_THEME, MODE_SYSTEM);
    }

    void setMode(int mode) {
        sp.edit().putInt(KEY_THEME, mode).apply();
    }

    /**
     * Returns a context that resolves day/night resources for the chosen mode.
     * MODE_SYSTEM returns the base context untouched, so the system setting wins.
     */
    static Context wrap(Context base, int mode) {
        if (mode == MODE_SYSTEM) {
            return base;
        }
        int value = (mode == MODE_DARK)
                ? Configuration.UI_MODE_NIGHT_YES
                : Configuration.UI_MODE_NIGHT_NO;
        Configuration config = new Configuration(base.getResources().getConfiguration());
        int mask = Configuration.UI_MODE_NIGHT_MASK;
        config.uiMode = (config.uiMode & ~mask) | value;
        return base.createConfigurationContext(config);
    }

    /** True when the context currently resolves the night resources. */
    static boolean isNight(Context context) {
        int mode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }
}
