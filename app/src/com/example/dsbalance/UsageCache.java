package com.example.dsbalance;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Keeps the last successful usage payload as raw JSON.
 *
 * Caching the bodies rather than the parsed numbers means a failure never blanks
 * the charts, and that an improvement to the parser also applies to data that was
 * fetched by an older build.
 */
final class UsageCache {

    private static final String FILE = "dsbalance";
    private static final String K_AMOUNT = "usage_amount";
    private static final String K_COST = "usage_cost";
    private static final String K_SUMMARY = "usage_summary";

    private UsageCache() {
    }

    static void save(Context context, String amount, String cost, String summary) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putString(K_AMOUNT, amount)
                .putString(K_COST, cost)
                .putString(K_SUMMARY, summary)
                .apply();
    }

    /** Returns {amount, cost, summary}, or null when nothing was ever stored. */
    static String[] load(Context context) {
        SharedPreferences sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        String amount = sp.getString(K_AMOUNT, null);
        String cost = sp.getString(K_COST, null);
        String summary = sp.getString(K_SUMMARY, null);
        if (amount == null || cost == null || summary == null) {
            return null;
        }
        return new String[]{amount, cost, summary};
    }

    static void clear(Context context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .remove(K_AMOUNT)
                .remove(K_COST)
                .remove(K_SUMMARY)
                .apply();
    }
}
