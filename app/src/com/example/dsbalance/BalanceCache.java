package com.example.dsbalance;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Keeps the last successful balance so the screen has something to show the moment
 * the app opens, and so a failed refresh never blanks out what the user was reading.
 */
final class BalanceCache {

    private static final String FILE = "dsbalance";
    private static final String K_CURRENCY = "cache_currency";
    private static final String K_TOTAL = "cache_total";
    private static final String K_GRANTED = "cache_granted";
    private static final String K_TOPPED_UP = "cache_topped_up";
    private static final String K_AVAILABLE = "cache_available";
    private static final String K_FETCHED_AT = "cache_fetched_at";

    private BalanceCache() {
    }

    static void save(Context context, BalanceApi.Result r) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(K_CURRENCY, r.currency)
                .putString(K_TOTAL, r.total)
                .putString(K_GRANTED, r.granted)
                .putString(K_TOPPED_UP, r.toppedUp)
                .putBoolean(K_AVAILABLE, r.available)
                .putLong(K_FETCHED_AT, r.fetchedAt)
                .apply();
    }

    /** Null when nothing has been fetched successfully on this device yet. */
    static BalanceApi.Result load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!sp.contains(K_FETCHED_AT)) {
            return null;
        }
        return new BalanceApi.Result(
                BalanceApi.OK,
                200,
                sp.getString(K_CURRENCY, ""),
                sp.getString(K_TOTAL, "0"),
                sp.getString(K_GRANTED, "0"),
                sp.getString(K_TOPPED_UP, "0"),
                sp.getBoolean(K_AVAILABLE, false),
                sp.getLong(K_FETCHED_AT, 0L));
    }

    static void clear(Context context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .remove(K_CURRENCY)
                .remove(K_TOTAL)
                .remove(K_GRANTED)
                .remove(K_TOPPED_UP)
                .remove(K_AVAILABLE)
                .remove(K_FETCHED_AT)
                .apply();
    }
}
