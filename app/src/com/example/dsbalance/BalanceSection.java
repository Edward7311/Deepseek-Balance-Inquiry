package com.example.dsbalance;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The balance block of the scrolling page.
 *
 * This was lifted out of MainActivity unchanged when the usage block was added —
 * it reads the one documented public endpoint with an API key, and nothing here
 * depends on the WebView or on the private usage endpoints.
 */
final class BalanceSection {

    private final Activity activity;
    private final ApiKeyStore keyStore;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final LinearLayout inputPanel;
    private final LinearLayout dataPanel;
    private final EditText inputKey;
    private final Button refreshButton;
    private final TextView updatedView;
    private final TextView errorView;
    private final TextView statusText;
    private final TextView totalAmount;
    private final TextView toppedUpAmount;
    private final TextView currencyView;
    private final View statusDot;

    /** True once real numbers are on screen, so a failure can avoid blanking them. */
    private boolean hasData;

    BalanceSection(Activity activity) {
        this.activity = activity;
        this.keyStore = new ApiKeyStore(activity);

        inputPanel = activity.findViewById(R.id.input_panel);
        dataPanel = activity.findViewById(R.id.data_panel);
        inputKey = activity.findViewById(R.id.input_key);
        refreshButton = activity.findViewById(R.id.refresh);
        updatedView = activity.findViewById(R.id.updated);
        errorView = activity.findViewById(R.id.error);
        statusText = activity.findViewById(R.id.status_text);
        totalAmount = activity.findViewById(R.id.total_amount);
        toppedUpAmount = activity.findViewById(R.id.topped_up_amount);
        currencyView = activity.findViewById(R.id.currency);
        statusDot = activity.findViewById(R.id.status_dot);

        activity.findViewById(R.id.change_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Prefilled so the stored key can be checked or edited in place.
                inputKey.setText(keyStore.getKey());
                showInputPanel();
            }
        });

        activity.findViewById(R.id.save_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = inputKey.getText().toString().trim();
                if (key.isEmpty()) {
                    Toast.makeText(activity, R.string.key_empty_toast, Toast.LENGTH_SHORT).show();
                    return;
                }
                keyStore.setKey(key);
                showDataPanel();
                refresh();
            }
        });

        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });

        if (keyStore.getKey().isEmpty()) {
            showInputPanel();
        } else {
            showDataPanel();
            BalanceApi.Result cached = BalanceCache.load(activity);
            if (cached != null) {
                render(cached);
            }
            refresh();
        }
    }

    void shutdown() {
        executor.shutdownNow();
    }

    // ---------------------------------------------------------------- fetching

    private void refresh() {
        final String key = keyStore.getKey();
        if (key.isEmpty()) {
            showInputPanel();
            return;
        }
        setBusy(true);
        errorView.setVisibility(View.GONE);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                final BalanceApi.Result result = BalanceApi.fetch(key);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        onFetched(result);
                    }
                });
            }
        });
    }

    private void onFetched(BalanceApi.Result result) {
        // A theme change recreates the activity while a request may still be in flight.
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        setBusy(false);

        if (result.isOk()) {
            BalanceCache.save(activity, result);
            render(result);
            errorView.setVisibility(View.GONE);
            return;
        }

        String message = messageFor(result);
        if (hasData) {
            message = activity.getString(R.string.error_showing_cache, message);
        }
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private String messageFor(BalanceApi.Result result) {
        switch (result.code) {
            case BalanceApi.ERR_UNAUTHORIZED:
                return activity.getString(R.string.error_invalid_key);
            case BalanceApi.ERR_TIMEOUT:
                return activity.getString(R.string.error_timeout);
            case BalanceApi.ERR_SERVER:
                return activity.getString(R.string.error_server, result.httpStatus);
            case BalanceApi.ERR_PARSE:
                return activity.getString(R.string.error_parse);
            case BalanceApi.ERR_NO_BALANCE:
                return activity.getString(R.string.error_no_balance);
            default:
                return activity.getString(R.string.error_network);
        }
    }

    private void render(BalanceApi.Result r) {
        totalAmount.setText(activity.getString(R.string.amount_format, r.total));
        toppedUpAmount.setText(activity.getString(R.string.amount_format, r.toppedUp));
        if (r.currency != null && !r.currency.isEmpty()) {
            currencyView.setText(r.currency);
        }
        statusDot.setBackgroundResource(r.available ? R.drawable.dot_ok : R.drawable.dot_bad);
        statusText.setText(r.available ? R.string.status_ok : R.string.status_bad);
        updatedView.setText(activity.getString(R.string.updated_format, timeOf(r.fetchedAt)));
        hasData = true;
    }

    private void setBusy(boolean busy) {
        refreshButton.setEnabled(!busy);
        refreshButton.setAlpha(busy ? 0.5f : 1.0f);
        refreshButton.setText(busy ? R.string.refreshing : R.string.refresh);
    }

    private String timeOf(long millis) {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(millis));
    }

    // ------------------------------------------------------------------- chrome

    private void showInputPanel() {
        inputPanel.setVisibility(View.VISIBLE);
        dataPanel.setVisibility(View.GONE);
    }

    private void showDataPanel() {
        inputPanel.setVisibility(View.GONE);
        dataPanel.setVisibility(View.VISIBLE);
    }
}
