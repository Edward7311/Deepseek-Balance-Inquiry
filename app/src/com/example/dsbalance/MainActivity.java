package com.example.dsbalance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
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

public class MainActivity extends Activity {

    private ThemePrefs themePrefs;
    private ApiKeyStore keyStore;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LinearLayout inputPanel;
    private LinearLayout dataPanel;
    private EditText inputKey;
    private Button refreshButton;
    private TextView updatedView;
    private TextView errorView;
    private TextView statusText;
    private TextView totalAmount;
    private TextView toppedUpAmount;
    private TextView grantedAmount;
    private TextView currencyView;
    private View statusDot;

    /** True once real numbers are on screen, so a failure can avoid blanking them. */
    private boolean hasData;

    @Override
    protected void attachBaseContext(Context base) {
        themePrefs = new ThemePrefs(base);
        super.attachBaseContext(ThemePrefs.wrap(base, themePrefs.getMode()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        keyStore = new ApiKeyStore(this);

        inputPanel = (LinearLayout) findViewById(R.id.input_panel);
        dataPanel = (LinearLayout) findViewById(R.id.data_panel);
        inputKey = (EditText) findViewById(R.id.input_key);
        refreshButton = (Button) findViewById(R.id.refresh);
        updatedView = (TextView) findViewById(R.id.updated);
        errorView = (TextView) findViewById(R.id.error);
        statusText = (TextView) findViewById(R.id.status_text);
        totalAmount = (TextView) findViewById(R.id.total_amount);
        toppedUpAmount = (TextView) findViewById(R.id.topped_up_amount);
        grantedAmount = (TextView) findViewById(R.id.granted_amount);
        currencyView = (TextView) findViewById(R.id.currency);
        statusDot = findViewById(R.id.status_dot);

        applyEdgeToEdgeInsets();
        applyStatusBarIcons();

        findViewById(R.id.theme_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemeDialog();
            }
        });

        findViewById(R.id.change_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Prefilled so the stored key can be checked or edited in place.
                inputKey.setText(keyStore.getKey());
                showInputPanel();
            }
        });

        findViewById(R.id.save_key).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String key = inputKey.getText().toString().trim();
                if (key.isEmpty()) {
                    Toast.makeText(MainActivity.this, R.string.key_empty_toast,
                            Toast.LENGTH_SHORT).show();
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
            BalanceApi.Result cached = BalanceCache.load(this);
            if (cached != null) {
                render(cached);
            }
            refresh();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
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
        if (isFinishing() || isDestroyed()) {
            return;
        }
        setBusy(false);

        if (result.isOk()) {
            BalanceCache.save(this, result);
            render(result);
            errorView.setVisibility(View.GONE);
            return;
        }

        String message = messageFor(result);
        if (hasData) {
            message = getString(R.string.error_showing_cache, message);
        }
        errorView.setText(message);
        errorView.setVisibility(View.VISIBLE);
    }

    private String messageFor(BalanceApi.Result result) {
        switch (result.code) {
            case BalanceApi.ERR_UNAUTHORIZED:
                return getString(R.string.error_invalid_key);
            case BalanceApi.ERR_TIMEOUT:
                return getString(R.string.error_timeout);
            case BalanceApi.ERR_SERVER:
                return getString(R.string.error_server, result.httpStatus);
            case BalanceApi.ERR_PARSE:
                return getString(R.string.error_parse);
            case BalanceApi.ERR_NO_BALANCE:
                return getString(R.string.error_no_balance);
            default:
                return getString(R.string.error_network);
        }
    }

    private void render(BalanceApi.Result r) {
        totalAmount.setText(getString(R.string.amount_format, r.total));
        toppedUpAmount.setText(getString(R.string.amount_format, r.toppedUp));
        grantedAmount.setText(getString(R.string.amount_format, r.granted));
        if (r.currency != null && !r.currency.isEmpty()) {
            currencyView.setText(r.currency);
        }
        statusDot.setBackgroundResource(r.available ? R.drawable.dot_ok : R.drawable.dot_bad);
        statusText.setText(r.available ? R.string.status_ok : R.string.status_bad);
        updatedView.setText(getString(R.string.updated_format, timeOf(r.fetchedAt)));
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

    /**
     * Edge-to-edge is forced on Android 15+ for apps targeting SDK 35, so the
     * content would otherwise sit under the status and navigation bars.
     */
    private void applyEdgeToEdgeInsets() {
        final View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                int left, top, right, bottom;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                    left = bars.left;
                    top = bars.top;
                    right = bars.right;
                    bottom = bars.bottom;
                } else {
                    left = insets.getSystemWindowInsetLeft();
                    top = insets.getSystemWindowInsetTop();
                    right = insets.getSystemWindowInsetRight();
                    bottom = insets.getSystemWindowInsetBottom();
                }
                v.setPadding(left, top, right, bottom);
                return insets;
            }
        });
    }

    /** Status bar icons must flip to white on a dark background, and vice versa. */
    private void applyStatusBarIcons() {
        boolean night = ThemePrefs.isNight(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                int mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS;
                controller.setSystemBarsAppearance(night ? 0 : mask, mask);
            }
        } else {
            View decor = getWindow().getDecorView();
            int flags = decor.getSystemUiVisibility();
            if (night) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            } else {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            }
            decor.setSystemUiVisibility(flags);
        }
    }

    private void showThemeDialog() {
        final String[] items = {
                getString(R.string.theme_system),
                getString(R.string.theme_light),
                getString(R.string.theme_dark),
        };
        new AlertDialog.Builder(this)
                .setTitle(R.string.theme_title)
                .setSingleChoiceItems(items, themePrefs.getMode(), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                        if (which != themePrefs.getMode()) {
                            themePrefs.setMode(which);
                            recreate();
                        }
                    }
                })
                .show();
    }

    private void showInputPanel() {
        inputPanel.setVisibility(View.VISIBLE);
        dataPanel.setVisibility(View.GONE);
    }

    private void showDataPanel() {
        inputPanel.setVisibility(View.GONE);
        dataPanel.setVisibility(View.VISIBLE);
    }
}
