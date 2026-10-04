package com.example.dsbalance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The usage block of the scrolling page.
 *
 * Everything here is independent of BalanceSection: this block depends on a
 * browser session and on private, undocumented endpoints, so it is the part that
 * can break without warning. When it does, the balance above keeps working.
 */
final class UsageSection implements UsageBridge.Listener {

    private final Activity activity;
    private final UsageSession session;

    private final View dot;
    private final TextView statusView;
    private final TextView rawView;
    private final TextView refreshView;
    private final TextView logoutView;
    private final TextView loginHint;
    private final TextView errorView;
    private final View summaryCard;
    private final View chartCard;
    private final View modelsCard;
    private final TextView costValue;
    private final TextView requestsValue;
    private final TextView tokensValue;
    /**
     * The 累计消费 card lives in the balance block, but its number only exists on a
     * private endpoint behind the browser session — so this section owns it, not
     * BalanceSection. When there is nothing to show it says why, and tapping it
     * does whatever the situation calls for.
     */
    private final TextView accumulatedValue;
    private final View accumulatedCard;
    private final BarChartView chart;
    private final TextView chartMax;
    private final TextView chartStart;
    private final TextView chartEnd;
    private final LinearLayout modelList;
    private final View rawCard;

    private final StringBuilder log = new StringBuilder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** True while the three chart endpoints are in flight. */
    private boolean fetching;
    private String amountBody;
    private String costBody;
    private String summaryBody;
    private String fetchError;

    private static final int ACC_LOADING = 0;
    private static final int ACC_OK = 1;
    private static final int ACC_NEED_LOGIN = 2;
    private static final int ACC_RELOGIN = 3;
    private static final int ACC_UNAVAILABLE = 4;

    private int accumulatedState = ACC_LOADING;
    private String accumulatedDetail;

    private boolean capturing;

    UsageSection(Activity activity) {
        this.activity = activity;
        this.session = new UsageSession(activity,
                (FrameLayout) activity.findViewById(R.id.webview_holder), this);

        dot = activity.findViewById(R.id.usage_dot);
        statusView = activity.findViewById(R.id.usage_status);
        rawView = activity.findViewById(R.id.usage_raw);
        refreshView = activity.findViewById(R.id.usage_refresh);
        logoutView = activity.findViewById(R.id.usage_logout);
        loginHint = activity.findViewById(R.id.usage_login_hint);
        errorView = activity.findViewById(R.id.usage_error);
        summaryCard = activity.findViewById(R.id.usage_summary);
        chartCard = activity.findViewById(R.id.usage_chart_card);
        modelsCard = activity.findViewById(R.id.usage_models_card);
        costValue = activity.findViewById(R.id.usage_cost_value);
        requestsValue = activity.findViewById(R.id.usage_requests_value);
        tokensValue = activity.findViewById(R.id.usage_tokens_value);
        accumulatedValue = activity.findViewById(R.id.balance_accumulated_value);
        accumulatedCard = activity.findViewById(R.id.balance_accumulated_card);
        chart = activity.findViewById(R.id.usage_chart);
        chartMax = activity.findViewById(R.id.usage_chart_max);
        chartStart = activity.findViewById(R.id.usage_chart_start);
        chartEnd = activity.findViewById(R.id.usage_chart_end);
        modelList = activity.findViewById(R.id.usage_models_list);
        rawCard = activity.findViewById(R.id.usage_raw_card);

        refreshView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startFetch();
            }
        });

        // Login is no longer its own button — the header globe opens the site, and
        // logging in there is the same act. The card just says where to look.
        logoutView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmLogout();
            }
        });

        activity.findViewById(R.id.usage_raw_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                rawCard.setVisibility(View.GONE);
            }
        });

        accumulatedCard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAccumulatedTap();
            }
        });

        // Escape hatch: put the login page away even if our token watcher missed it.
        activity.findViewById(R.id.usage_login_done).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                session.hideBrowser();
                updateLoginUi();
            }
        });

        activity.findViewById(R.id.usage_capture).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startCapture();
            }
        });

        activity.findViewById(R.id.usage_probe).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startProbe();
            }
        });

        activity.findViewById(R.id.usage_diag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDiagnose();
            }
        });

        updateLoginUi();

        // Show cached numbers immediately, then quietly check whether the session
        // from a previous run is still good.
        renderCache();
        session.restore();
    }

    void destroy() {
        handler.removeCallbacksAndMessages(null);
        session.destroy();
    }

    // ------------------------------------------------------------------ actions

    private void startFetch() {
        if (fetching || capturing) {
            return;
        }
        fetching = true;
        fetchError = null;
        amountBody = null;
        costBody = null;
        summaryBody = null;
        refreshView.setText(R.string.usage_fetching);
        session.fetchUsage();
    }

    /**
     * Reloads the dashboard and records every /api/ request it makes. The daily
     * chart's endpoint is whatever shows up here — no guessing at parameters.
     */
    private void startCapture() {
        if (capturing) {
            return;
        }
        capturing = true;
        session.clearCaptured();
        log.setLength(0);
        log.append(activity.getString(R.string.usage_capturing)).append("\n");
        rawView.setTextColor(activity.getColor(R.color.text_secondary));
        rawCard.setVisibility(View.VISIBLE);
        rawView.setText(log.toString());
        session.reload();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                finishCapture();
            }
        }, 10000);
    }

    private void finishCapture() {
        capturing = false;
        List<String> urls = session.capturedUrls();
        log.setLength(0);
        if (urls.isEmpty()) {
            log.append(activity.getString(R.string.usage_captured_none)).append("\n");
        } else {
            log.append(activity.getString(R.string.usage_captured, urls.size())).append("\n\n");
            for (String url : urls) {
                log.append(url).append("\n\n");
            }
        }
        rawView.setText(log.toString());
        rawView.setTextColor(activity.getColor(R.color.text_primary));
    }

    private void startProbe() {
        if (fetching || capturing) {
            return;
        }
        log.setLength(0);
        log.append(activity.getString(R.string.usage_probing)).append("\n\n");
        rawView.setTextColor(activity.getColor(R.color.text_secondary));
        rawCard.setVisibility(View.VISIBLE);
        rawView.setText(log.toString());
        session.probe();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.usage_logout_confirm_title)
                .setMessage(R.string.usage_logout_confirm)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.usage_logout_confirm_ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                session.logout();
                                // The cached payload belongs to that session; keeping
                                // it would leave stale numbers looking current.
                                UsageCache.clear(activity);
                                clearRendered();
                                setAccumulatedState(ACC_NEED_LOGIN, null);
                                updateLoginUi();
                            }
                        })
                .show();
    }

    // ------------------------------------------------- accumulated cost card

    /**
     * Puts a short status word where the number would go. Deliberately a word in the
     * value slot rather than an extra hint line: the two balance cards sit side by
     * side, and a second line would make this one taller than its neighbour.
     */
    private void setAccumulatedState(int state, String detail) {
        accumulatedState = state;
        accumulatedDetail = detail;
        switch (state) {
            case ACC_OK:
                accumulatedValue.setTextColor(activity.getColor(R.color.text_primary));
                break;
            case ACC_LOADING:
                accumulatedValue.setText(R.string.balance_loading);
                accumulatedValue.setTextColor(activity.getColor(R.color.text_secondary));
                break;
            case ACC_NEED_LOGIN:
                accumulatedValue.setText(R.string.balance_need_login);
                accumulatedValue.setTextColor(activity.getColor(R.color.accent));
                break;
            case ACC_RELOGIN:
                accumulatedValue.setText(R.string.balance_relogin);
                accumulatedValue.setTextColor(activity.getColor(R.color.accent));
                break;
            default:
                accumulatedValue.setText(R.string.balance_unavailable);
                accumulatedValue.setTextColor(activity.getColor(R.color.error_text));
                break;
        }
    }

    private void onAccumulatedTap() {
        switch (accumulatedState) {
            case ACC_NEED_LOGIN:
            case ACC_RELOGIN:
                // Go straight to the place where logging in happens, rather than
                // scrolling the page and making the user find it themselves.
                openBrowser();
                break;
            case ACC_UNAVAILABLE:
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.balance_accumulated_detail)
                        .setMessage(accumulatedDetail == null
                                ? activity.getString(R.string.usage_error_unknown)
                                : accumulatedDetail)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                break;
            default:
                break;
        }
    }

    /** Used by the header globe: open the site, or put it away if it is already up. */
    void toggleBrowser() {
        if (session.isBrowserVisible()) {
            session.hideBrowser();
        } else {
            openBrowser();
        }
        updateLoginUi();
    }

    private void openBrowser() {
        session.showBrowser();
        updateLoginUi();
    }

    // ---------------------------------------------------------------- callbacks

    @Override
    public void onLoggedIn() {
        // Whether to close the browser is decided in UsageSession: only a login that
        // just happened closes it. Doing it here unconditionally is what made the
        // globe button snap shut about a second after opening.
        updateLoginUi();
        renderCache();
        startFetch();
    }

    private void startDiagnose() {
        if (fetching || capturing) {
            return;
        }
        log.setLength(0);
        log.append(activity.getString(R.string.usage_diagnosing)).append("\n");
        rawView.setTextColor(activity.getColor(R.color.text_secondary));
        rawCard.setVisibility(View.VISIBLE);
        rawView.setText(log.toString());
        session.diagnose();
    }

    @Override
    public void onDiag(String text) {
        append("\n" + text + "\n");
        rawView.setTextColor(activity.getColor(R.color.text_primary));
    }

    @Override
    public void onNoToken() {
        fetching = false;
        refreshView.setText(R.string.usage_refresh);
        updateLoginUi();
        // Authentication problems are always surfaced, even if a stale number is
        // still on screen — it cannot be refreshed, and saying so is more honest.
        boolean fetchedBefore = UsageCache.load(activity) != null;
        setAccumulatedState(fetchedBefore ? ACC_RELOGIN : ACC_NEED_LOGIN, null);
        if (log.length() == 0) {
            errorView.setText(R.string.usage_error_login);
            errorView.setVisibility(View.VISIBLE);
        } else {
            append("\n" + activity.getString(R.string.usage_no_token) + "\n");
        }
    }

    @Override
    public void onResult(String url, int status, String body) {
        if (fetching) {
            if (url.contains("by_api_key/amount")) {
                amountBody = body;
            } else if (url.contains("by_api_key/cost")) {
                costBody = body;
            } else if (url.contains("get_user_summary")) {
                summaryBody = body;
            }
            return;
        }
        append("\n── GET " + url + "\nHTTP " + status + "\n" + body + "\n");
    }

    @Override
    public void onError(String url, String message) {
        if (fetching) {
            fetchError = message;
            return;
        }
        append("\n── GET " + url + "\n请求失败：" + message + "\n");
    }

    @Override
    public void onFinished(int count) {
        if (fetching) {
            finishFetch();
            return;
        }
        append("\n" + activity.getString(R.string.usage_probe_done, count) + "\n");
        rawView.setTextColor(activity.getColor(R.color.text_primary));
    }

    // ---------------------------------------------------------------- rendering

    private void finishFetch() {
        fetching = false;
        refreshView.setText(R.string.usage_refresh);

        // A transient failure leaves any number already on screen alone — same rule
        // the balance block follows. The status word is only for having nothing.
        if (fetchError != null) {
            errorView.setText(activity.getString(R.string.usage_error_network)
                    + "\n" + fetchError);
            errorView.setVisibility(View.VISIBLE);
            if (accumulatedState != ACC_OK) {
                setAccumulatedState(ACC_UNAVAILABLE, fetchError);
            }
            return;
        }
        if (amountBody == null || costBody == null || summaryBody == null) {
            errorView.setText(R.string.usage_error_unknown);
            errorView.setVisibility(View.VISIBLE);
            if (accumulatedState != ACC_OK) {
                setAccumulatedState(ACC_UNAVAILABLE, null);
            }
            return;
        }

        try {
            UsageModel model = UsageModel.parse(amountBody, costBody, summaryBody);
            UsageCache.save(activity, amountBody, costBody, summaryBody);
            render(model);
            errorView.setVisibility(View.GONE);
        } catch (JSONException e) {
            // The shape changed. Say so instead of drawing a plausible-looking zero.
            String detail = String.valueOf(e.getMessage());
            errorView.setText(activity.getString(R.string.usage_error_format, detail));
            errorView.setVisibility(View.VISIBLE);
            if (accumulatedState != ACC_OK) {
                setAccumulatedState(ACC_UNAVAILABLE, detail);
            }
        }
    }

    private void renderCache() {
        String[] cached = UsageCache.load(activity);
        if (cached == null) {
            return;
        }
        try {
            render(UsageModel.parse(cached[0], cached[1], cached[2]));
        } catch (JSONException ignored) {
            // Cached payload no longer parses; the next fetch will replace it.
        }
    }

    private void render(UsageModel model) {
        summaryCard.setVisibility(View.VISIBLE);
        costValue.setText(UsageModel.money(model.totalCost));
        requestsValue.setText(UsageModel.count(model.totalRequests));
        tokensValue.setText(UsageModel.count(model.totalTokens));
        accumulatedValue.setText(UsageModel.money(model.accumulatedCost));
        setAccumulatedState(ACC_OK, null);

        if (model.days.isEmpty() || model.totalCost == 0) {
            chartCard.setVisibility(View.GONE);
        } else {
            chartCard.setVisibility(View.VISIBLE);
            List<BarChartView.Bar> bars = new ArrayList<BarChartView.Bar>();
            for (UsageModel.Day day : model.days) {
                bars.add(new BarChartView.Bar(
                        UsageModel.dayLabel(day.time),
                        day.cost,
                        UsageModel.money(day.cost)));
            }
            chart.setBars(bars);
            chartMax.setText(activity.getString(R.string.usage_max_format,
                    UsageModel.money(chart.maxValue())));
            chartStart.setText(UsageModel.dayLabel(model.days.get(0).time));
            chartEnd.setText(UsageModel.dayLabel(model.days.get(model.days.size() - 1).time));
        }

        renderModels(model);
    }

    private void renderModels(UsageModel model) {
        // Models the account has never used come back as all-zero rows; showing them
        // is noise, so only models with actual activity are listed.
        List<UsageModel.Model> used = new ArrayList<UsageModel.Model>();
        for (UsageModel.Model entry : model.models) {
            if (entry.tokens > 0 || entry.cost > 0 || entry.requests > 0) {
                used.add(entry);
            }
        }
        Collections.sort(used, new Comparator<UsageModel.Model>() {
            @Override
            public int compare(UsageModel.Model a, UsageModel.Model b) {
                return Long.compare(b.tokens, a.tokens);
            }
        });

        modelList.removeAllViews();
        if (used.isEmpty()) {
            modelsCard.setVisibility(View.GONE);
            return;
        }
        modelsCard.setVisibility(View.VISIBLE);

        LayoutInflater inflater = LayoutInflater.from(activity);
        for (UsageModel.Model entry : used) {
            View row = inflater.inflate(R.layout.item_model_usage, modelList, false);
            ((TextView) row.findViewById(R.id.model_name)).setText(entry.name);
            ((TextView) row.findViewById(R.id.model_detail)).setText(
                    activity.getString(R.string.usage_model_detail,
                            UsageModel.money(entry.cost),
                            UsageModel.count(entry.requests),
                            UsageModel.compact(entry.tokens)));
            modelList.addView(row);
        }
    }

    private void clearRendered() {
        summaryCard.setVisibility(View.GONE);
        chartCard.setVisibility(View.GONE);
        modelsCard.setVisibility(View.GONE);
        errorView.setVisibility(View.GONE);
        rawCard.setVisibility(View.GONE);
        log.setLength(0);
        rawView.setText("");
    }

    // ------------------------------------------------------------------ helpers

    private void append(String text) {
        log.append(text);
        rawCard.setVisibility(View.VISIBLE);
        rawView.setText(log.toString());
    }

    private void updateLoginUi() {
        boolean loggedIn = session.isLoggedIn();
        dot.setBackgroundResource(loggedIn ? R.drawable.dot_ok : R.drawable.dot_bad);
        statusView.setText(loggedIn ? R.string.usage_logged_in : R.string.usage_not_logged_in);
        loginHint.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        logoutView.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
    }
}
