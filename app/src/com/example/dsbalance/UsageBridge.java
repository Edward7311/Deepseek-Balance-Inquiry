package com.example.dsbalance;

import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;

/**
 * The only door between the WebView's JavaScript and Java.
 *
 * Anything exposed here is reachable by whatever page that WebView happens to be
 * showing, so UsageSession keeps navigation locked to deepseek.com and turns off
 * file access. Keep this surface as small as it is — no token ever crosses back.
 *
 * All methods are called on the WebView's JavaScript thread, so every callback is
 * bounced onto the main thread before touching the UI.
 */
final class UsageBridge {

    interface Listener {
        void onLoggedIn();

        void onNoToken();

        void onResult(String url, int status, String body);

        void onError(String url, String message);

        void onFinished(int count);

        /** One-shot report from the login-state diagnostic. */
        void onDiag(String text);
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;

    UsageBridge(Listener listener) {
        this.listener = listener;
    }

    @JavascriptInterface
    public void loggedIn() {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onLoggedIn();
            }
        });
    }

    @JavascriptInterface
    public void noToken() {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onNoToken();
            }
        });
    }

    @JavascriptInterface
    public void result(final String url, final int status, final String body) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onResult(url, status, body);
            }
        });
    }

    @JavascriptInterface
    public void error(final String url, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onError(url, message);
            }
        });
    }

    @JavascriptInterface
    public void finished(final int count) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onFinished(count);
            }
        });
    }

    @JavascriptInterface
    public void diag(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onDiag(text);
            }
        });
    }
}
