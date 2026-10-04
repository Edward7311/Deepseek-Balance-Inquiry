package com.example.dsbalance;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * Owns the WebView that holds a logged-in DeepSeek session and asks the dashboard's
 * own private endpoints for usage data.
 *
 * Why the request is made from inside the page rather than from Java: the browser
 * then supplies User-Agent, Origin, Referer and cookies by itself, the call is
 * same-origin so CORS never applies, the WAF sees a real session, and the session
 * token never has to leave the page.
 *
 * The WebView is deliberately kept alive after login, shrunk to 1dp, so the session
 * survives and later fetches do not need a fresh page load.
 */
final class UsageSession {

    private static final String START_URL = "https://platform.deepseek.com/usage";
    private static final String COOKIE_DOMAIN = "deepseek.com";

    private final Activity activity;
    private final FrameLayout holder;
    private final UsageBridge.Listener listener;

    private WebView webView;
    private boolean loggedIn;
    private boolean browserVisible;
    /**
     * Set when the browser was opened by someone who was not logged in yet. Only
     * then does a successful login close the browser automatically — if the user
     * was already logged in they just wanted to look at the site, and yanking it
     * away from them was a bug: the watcher re-armed on open would immediately see
     * the existing token and collapse the view about a second later.
     */
    private boolean autoHideOnLogin;

    /**
     * How long the page may take to write its token before we call it logged out.
     * Long for an explicit login (the user is typing a code), shorter for the quiet
     * check on launch.
     */
    private int watchSeconds = RESTORE_WATCH_SECONDS;

    private static final int RESTORE_WATCH_SECONDS = 90;
    private static final int LOGIN_WATCH_SECONDS = 300;

    /**
     * Every /api/ URL the page asks for, captured natively instead of guessed.
     * shouldInterceptRequest sees XHR and fetch traffic too, which is how we find
     * out what the dashboard itself calls for the daily chart.
     */
    private final java.util.List<String> captured =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    UsageSession(Activity activity, FrameLayout holder, UsageBridge.Listener listener) {
        this.activity = activity;
        this.holder = holder;
        this.listener = listener;
    }

    boolean isLoggedIn() {
        return loggedIn;
    }

    /**
     * Brings the WebView up without showing it, so a session that is still valid
     * is discovered on launch and the usage block can report "已登录" immediately
     * instead of pretending the user is logged out until they tap something.
     */
    void restore() {
        ensureWebView();
        watchSeconds = RESTORE_WATCH_SECONDS;
        resizeHolder(dp(1), dp(1));
        if (webView.getUrl() == null) {
            webView.loadUrl(START_URL);
        }
    }

    /** Opens the real site full-screen. Also the way to log in — it is the same act. */
    void showBrowser() {
        ensureWebView();
        watchSeconds = LOGIN_WATCH_SECONDS;
        browserVisible = true;
        autoHideOnLogin = !loggedIn;
        resizeHolder(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (webView.getUrl() == null) {
            webView.loadUrl(START_URL);
        } else {
            // The page may already be loaded (from the quiet launch check), so
            // onPageFinished will not fire again. Re-arm the watcher here, otherwise
            // a login done in the browser would never be noticed.
            webView.evaluateJavascript(watchScript(watchSeconds), null);
        }
    }

    /** Shrinks the site out of the way but keeps it — and the session — alive. */
    void hideBrowser() {
        browserVisible = false;
        autoHideOnLogin = false;
        resizeHolder(dp(1), dp(1));
    }

    boolean isBrowserVisible() {
        return browserVisible;
    }

    /**
     * Runs the probe in the page. Blocking. Called from the main thread; the
     * callbacks arrive back on the main thread via UsageBridge.
     */
    void probe() {
        if (webView == null) {
            listener.onNoToken();
            return;
        }
        webView.evaluateJavascript(PROBE_JS, null);
    }

    /**
     * Fetches the three endpoints the charts are built from. Bodies come back whole
     * (unlike the probe, which truncates for display) so they can be parsed and cached.
     */
    void fetchUsage() {
        if (webView == null) {
            listener.onNoToken();
            return;
        }
        webView.evaluateJavascript(FETCH_JS, null);
    }

    /**
     * Reports why login detection is or isn't working: the page URL, what is actually
     * in localStorage, and — the important one — whether the usage endpoint answers
     * with cookies alone, no Authorization header at all.
     */
    void diagnose() {
        if (webView == null) {
            listener.onDiag("WebView 还没创建，先点「打开登录页面」。");
            return;
        }
        webView.evaluateJavascript(DIAG_JS, null);
    }

    void logout() {
        loggedIn = false;
        if (webView != null) {
            CookieManager.getInstance().removeAllCookies(null);
            CookieManager.getInstance().flush();
            WebStorage.getInstance().deleteAllData();
            webView.clearCache(true);
            webView.loadUrl(START_URL);
        }
    }

    // ------------------------------------------------------- request capturing

    void clearCaptured() {
        captured.clear();
    }

    java.util.List<String> capturedUrls() {
        synchronized (captured) {
            return new java.util.ArrayList<String>(captured);
        }
    }

    /** Reloads the dashboard so its own data requests run again, and get captured. */
    void reload() {
        if (webView != null) {
            webView.loadUrl(START_URL);
        }
    }

    void destroy() {
        if (webView != null) {
            holder.removeView(webView);
            webView.destroy();
            webView = null;
        }
    }

    // ------------------------------------------------------------------ webview

    @SuppressLint("SetJavaScriptEnabled")
    private void ensureWebView() {
        if (webView != null) {
            return;
        }
        webView = new WebView(activity);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);   // localStorage 是存放会话令牌的地方
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);
        settings.setSaveFormData(false);

        webView.setBackgroundColor(Color.TRANSPARENT);
        webView.addJavascriptInterface(new UsageBridge(new UsageBridge.Listener() {
            @Override
            public void onLoggedIn() {
                if (loggedIn) {
                    // Already known; nothing to react to.
                    return;
                }
                loggedIn = true;
                if (autoHideOnLogin) {
                    hideBrowser();
                }
                listener.onLoggedIn();
            }

            @Override
            public void onNoToken() {
                listener.onNoToken();
            }

            @Override
            public void onResult(String url, int status, String body) {
                listener.onResult(url, status, body);
            }

            @Override
            public void onError(String url, String message) {
                listener.onError(url, message);
            }

            @Override
            public void onFinished(int count) {
                listener.onFinished(count);
            }

            @Override
            public void onDiag(String text) {
                listener.onDiag(text);
            }
        }), "AndroidBridge");
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
                String host = request.getUrl().getHost();
                // Only deepseek.com and its subdomains may load in this WebView,
                // because the JavaScript bridge is exposed to whatever loads here.
                return host == null
                        || !(host.equals(COOKIE_DOMAIN) || host.endsWith("." + COOKIE_DOMAIN));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                // The dashboard is a single-page app: it writes the token into
                // localStorage without reloading, so onPageFinished fires only once.
                // A watcher in the page is what notices the login completing.
                view.evaluateJavascript(watchScript(watchSeconds), null);
            }

            /**
             * Called for every resource the page loads, XHR and fetch included, so
             * this records the dashboard's real API calls without having to guess
             * at URLs or parameters. Returning null means "load it normally".
             */
            @Override
            public android.webkit.WebResourceResponse shouldInterceptRequest(
                    WebView view, android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.contains("/api/") && captured.size() < 60 && !captured.contains(url)) {
                    captured.add(url);
                }
                return null;
            }
        });

        // Index 0: the "完成" escape hatch declared in the layout must draw on top
        // of the page, so the WebView goes underneath it.
        holder.addView(webView, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private void resizeHolder(int width, int height) {
        ViewGroup.LayoutParams params = holder.getLayoutParams();
        params.width = width;
        params.height = height;
        holder.setLayoutParams(params);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    // ----------------------------------------------------------------------- js

    /**
     * Polls localStorage until the login token appears, then tells Java once.
     *
     * The wait is generous on purpose. Diagnostics on a real device showed the key
     * name, the value shape and the token request are all correct — the only thing
     * that ever went wrong was giving up too early: the dashboard page carries a
     * banner, images and dialogs, and on a cold load it can take well over ten
     * seconds to write the token. An earlier 8-second cap reported "not logged in"
     * for a session that was perfectly valid.
     *
     * It still reports noToken as soon as the URL looks like a sign-in page, so a
     * user who never logs in is not left waiting for the full window.
     */
    private static String watchScript(int seconds) {
        int maxTries = seconds * 1000 / POLL_INTERVAL_MS;
        return "(function(){"
                + "if (window.__dsbWatch) { clearInterval(window.__dsbWatch); }"
                + "var tries = 0;"
                + "window.__dsbWatch = setInterval(function(){"
                + "  tries++;"
                + "  var v = null;"
                + "  try { v = JSON.parse(localStorage.getItem('userToken')).value; } catch (e) {}"
                + "  if (v) {"
                + "    clearInterval(window.__dsbWatch); window.__dsbWatch = null;"
                + "    AndroidBridge.loggedIn();"
                + "    return;"
                + "  }"
                + "  if (/sign[_\\-]?in|sign[_\\-]?up|login/i.test(location.href)) {"
                + "    clearInterval(window.__dsbWatch); window.__dsbWatch = null;"
                + "    AndroidBridge.noToken();"
                + "    return;"
                + "  }"
                + "  if (tries >= " + maxTries + ") {"
                + "    clearInterval(window.__dsbWatch); window.__dsbWatch = null;"
                + "    AndroidBridge.noToken();"
                + "  }"
                + "}, " + POLL_INTERVAL_MS + ");"
                + "})()";
    }

    private static final int POLL_INTERVAL_MS = 800;

    /**
     * Answers "why does the app think I am logged out when the page clearly is not?"
     *
     * It reports the page URL, the names of the localStorage keys actually present,
     * the shape of userToken, and then calls the usage endpoint twice — once with
     * cookies only, once with the bearer token. If the cookie-only call succeeds,
     * the whole localStorage dependency can be dropped, which would make login
     * detection immune to the site renaming its keys.
     *
     * Only the first few characters of the token are echoed, never the whole value.
     */
    private static final String DIAG_JS =
            "(function(){"
                    + "var L = [];"
                    + "try { L.push('页面地址: ' + location.href); } catch (e) {}"
                    + "var keys = [];"
                    + "try { for (var i = 0; i < localStorage.length; i++) keys.push(localStorage.key(i)); } catch (e) {}"
                    + "L.push('localStorage 共 ' + keys.length + ' 个键: ' + (keys.length ? keys.join(', ') : '(空)'));"
                    + "var raw = null;"
                    + "try { raw = localStorage.getItem('userToken'); } catch (e) {}"
                    + "L.push('userToken: ' + (raw === null ? '不存在' : ('存在，长度 ' + raw.length + '，开头 ' + raw.substring(0, 24))));"
                    + "var token = null;"
                    + "if (raw) {"
                    + "  try { token = JSON.parse(raw).value; L.push('解析: 是 JSON，.value 长度 ' + (token ? String(token).length : 0)); }"
                    + "  catch (e) { token = raw; L.push('解析: 不是 JSON（' + e + '），按纯字符串使用'); }"
                    + "}"
                    + "var emitted = false, pending = 0;"
                    + "function emit(){ if (emitted) return; emitted = true; AndroidBridge.diag(L.join('\\n')); }"
                    + "function test(label, headers){"
                    + "  pending++;"
                    + "  var u = '/api/v0/users/get_user_summary';"
                    + "  fetch(u, {credentials:'include', headers: headers})"
                    + "    .then(function(r){ return r.text().then(function(b){"
                    + "        L.push(''); L.push('【' + label + '】HTTP ' + r.status); L.push(b.substring(0, 220));"
                    + "        pending--; if (pending === 0) emit();"
                    + "    }); })"
                    + "    .catch(function(e){ L.push(''); L.push('【' + label + '】请求失败: ' + String(e));"
                    + "        pending--; if (pending === 0) emit(); });"
                    + "}"
                    + "test('只靠 cookie（不带任何认证头）', {'Accept':'application/json','x-client-platform':'web'});"
                    + "if (token) {"
                    + "  test('带 Authorization 令牌', {'Accept':'application/json','x-client-platform':'web','Authorization':'Bearer ' + token});"
                    + "} else {"
                    + "  L.push(''); L.push('没有令牌，跳过带令牌的测试');"
                    + "}"
                    + "setTimeout(emit, 12000);"
                    + "})()";

    /**
     * The three calls behind the charts. Same day-aligned window the dashboard uses.
     * No truncation here — the bodies are parsed and cached verbatim.
     */
    private static final String FETCH_JS =
            "(function(){"
                    + "var t = null;"
                    + "try { t = JSON.parse(localStorage.getItem('userToken')).value; } catch (e) {}"
                    + "if (!t) { AndroidBridge.noToken(); return; }"
                    + "var d = new Date();"
                    + "var tz = -d.getTimezoneOffset() * 60;"
                    + "var now = Math.floor(Date.now()/1000);"
                    + "var dayStart = Math.floor((now + tz) / 86400) * 86400 - tz;"
                    + "var end = dayStart + 86400;"
                    + "var start = end - 30 * 86400;"
                    + "var urls = ["
                    + "  '/api/v0/usage/by_api_key/amount?start=' + start + '&end=' + end + '&tz=' + tz,"
                    + "  '/api/v0/usage/by_api_key/cost?start=' + start + '&end=' + end + '&tz=' + tz,"
                    + "  '/api/v0/users/get_user_summary'"
                    + "];"
                    + "var n = 0;"
                    + "function next(i){"
                    + "  if (i >= urls.length) { AndroidBridge.finished(n); return; }"
                    + "  var u = urls[i];"
                    + "  fetch(u, {credentials:'include', headers:{"
                    + "      'Authorization': 'Bearer ' + t,"
                    + "      'Accept': 'application/json',"
                    + "      'x-client-platform': 'web'"
                    + "  }})"
                    + "  .then(function(r){ return r.text().then(function(b){"
                    + "      n++; AndroidBridge.result(u, r.status, b); }); })"
                    + "  .catch(function(e){ n++; AndroidBridge.error(u, String(e)); })"
                    + "  .then(function(){ next(i+1); });"
                    + "}"
                    + "next(0);"
                    + "})()";

    /**
     * Tries each candidate endpoint in turn and hands the raw body back to Java.
     * Nothing is parsed here — this build exists to find out what the shape is.
     *
     * The start/end values MUST be aligned to local midnight. The dashboard sends
     * 2026-09-04T00:00+08:00 and 30 days later; sending "now minus 30 days" instead
     * gets INVALID_PARAM even though the path and parameter names are correct.
     */
    private static final String PROBE_JS =
            "(function(){"
                    + "var t = null;"
                    + "try { t = JSON.parse(localStorage.getItem('userToken')).value; } catch (e) {}"
                    + "if (!t) { AndroidBridge.noToken(); return; }"
                    + "var d = new Date();"
                    + "var tz = -d.getTimezoneOffset() * 60;"
                    + "var now = Math.floor(Date.now()/1000);"
                    + "var dayStart = Math.floor((now + tz) / 86400) * 86400 - tz;"
                    + "var end = dayStart + 86400;"
                    + "var start = end - 30 * 86400;"
                    + "var urls = ["
                    + "  '/api/v0/usage/by_api_key/amount?start=' + start + '&end=' + end + '&tz=' + tz,"
                    + "  '/api/v0/usage/by_api_key/cost?start=' + start + '&end=' + end + '&tz=' + tz,"
                    + "  '/api/v0/usage/amount?month=' + (d.getMonth()+1) + '&year=' + d.getFullYear(),"
                    + "  '/api/v0/usage/cost?month=' + (d.getMonth()+1) + '&year=' + d.getFullYear(),"
                    + "  '/api/v0/users/get_user_summary',"
                    + "  '/api/v0/users/get_api_keys'"
                    + "];"
                    + "var n = 0;"
                    + "function next(i){"
                    + "  if (i >= urls.length) { AndroidBridge.finished(n); return; }"
                    + "  var u = urls[i];"
                    + "  fetch(u, {credentials:'include', headers:{"
                    + "      'Authorization': 'Bearer ' + t,"
                    + "      'Accept': 'application/json',"
                    + "      'x-client-platform': 'web'"
                    + "  }})"
                    + "  .then(function(r){ return r.text().then(function(b){"
                    + "      n++; AndroidBridge.result(u, r.status, b.substring(0, 1500)); }); })"
                    + "  .catch(function(e){ n++; AndroidBridge.error(u, String(e)); })"
                    + "  .then(function(){ next(i+1); });"
                    + "}"
                    + "next(0);"
                    + "})()";
}
