package com.example.dsbalance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/**
 * Hosts one scrolling page made of two independent blocks:
 *
 *   BalanceSection — official public API + API key. Always works.
 *   UsageSection   — dashboard's private endpoints + an embedded browser session.
 *                    May break without warning; when it does, only it breaks.
 *
 * What is left here is only what belongs to the whole screen: theme, edge-to-edge
 * insets, status bar icon colour.
 */
public class MainActivity extends Activity {

    private ThemePrefs themePrefs;
    private BalanceSection balanceSection;
    private UsageSection usageSection;

    @Override
    protected void attachBaseContext(Context base) {
        themePrefs = new ThemePrefs(base);
        super.attachBaseContext(ThemePrefs.wrap(base, themePrefs.getMode()));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        applyEdgeToEdgeInsets();
        applyStatusBarIcons();

        findViewById(R.id.theme_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showThemeDialog();
            }
        });

        balanceSection = new BalanceSection(this);
        usageSection = new UsageSection(this);

        // Opens the real DeepSeek site full-screen; tapping again puts it away.
        findViewById(R.id.browser_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                usageSection.toggleBrowser();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        balanceSection.shutdown();
        usageSection.destroy();
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
}
