package com.example.dsbalance;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Holds the API key on this device only, in the app's own private storage.
 * It is never written into the APK and never sent anywhere except api.deepseek.com.
 *
 * Stored as plain text: readable by anyone who roots the phone or extracts an app
 * backup. Accepted deliberately for a single-user tool — encrypting it would need
 * the Android Keystore and buy little here.
 */
final class ApiKeyStore {

    private static final String FILE = "dsbalance";
    private static final String KEY_API = "api_key";

    private final SharedPreferences sp;

    ApiKeyStore(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String getKey() {
        return sp.getString(KEY_API, "");
    }

    void setKey(String key) {
        sp.edit().putString(KEY_API, key).apply();
    }
}
