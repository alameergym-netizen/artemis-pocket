package com.limelight.pocket.cloud;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.pocket.ConsoleActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A cloud gaming site, full screen, played with the handheld's controller: every button goes to
 * the game (B never leaves). The device's Back button, the Home / Guide button or holding
 * Select opens a small menu (back to Pocket Nexus, reload, desktop mode, the official app,
 * sign out, close). Each service runs in a process and a task of its own, so it keeps going
 * in the background and keeps its own sign-in.
 */
public abstract class CloudPlayerActivity extends Activity {

    /** Xbox Cloud Gaming. */
    public static final class Xcloud extends CloudPlayerActivity {
        @Override
        protected CloudService service() {
            return CloudService.XCLOUD;
        }
    }

    /** GeForce NOW. */
    public static final class Gfn extends CloudPlayerActivity {
        @Override
        protected CloudService service() {
            return CloudService.GFN;
        }
    }

    /** Boosteroid. */
    public static final class Boosteroid extends CloudPlayerActivity {
        @Override
        protected CloudService service() {
            return CloudService.BOOSTEROID;
        }
    }

    protected abstract CloudService service();

    private static final long HOLD_FOR_MENU_MS = 900;

    private WebView web;
    private AlertDialog menu;
    private boolean closing;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean selectHeld;
    private final Runnable selectHold = () -> {
        selectHeld = true;
        showMenu();
    };

    private SharedPreferences prefs() {
        return getSharedPreferences("pocket_cloud_" + service().id, MODE_PRIVATE);
    }

    private boolean desktopMode() {
        return prefs().getBoolean("desktop", true);
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                // A browser profile of its own (its own sign-in), as Android requires per process
                WebView.setDataDirectorySuffix(service().id);
            } catch (IllegalStateException ignored) {
            }
        }
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setTitle(service().name);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        try {
            web = new WebView(this);
        } catch (RuntimeException e) {
            // No (or a broken) Android System WebView
            Toast.makeText(this, R.string.pocket_cloud_no_webview, Toast.LENGTH_LONG).show();
            openOfficialOrStore();
            finish();
            return;
        }
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        configure();
        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(service().url);
        }
        web.requestFocus();
        Toast.makeText(this, R.string.pocket_cloud_menu_tip, Toast.LENGTH_LONG).show();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);
        s.setUserAgentString(userAgent(desktopMode()));
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(web, true);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    String host = request.getOrigin() != null ? request.getOrigin().getHost() : null;
                    if (host == null || !trusted(host)) {
                        request.deny();
                        return;
                    }
                    List<String> grant = new ArrayList<>();
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID.equals(r)
                                || PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) {
                            grant.add(r);
                        }
                    }
                    if (grant.isEmpty()) {
                        request.deny();
                    }
                    else {
                        request.grant(grant.toArray(new String[0]));
                    }
                });
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String scheme = u.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                // intent:, market: ... (e.g. "get the app") open outside
                try {
                    Intent i = "intent".equalsIgnoreCase(scheme) ? Intent.parseUri(u.toString(), Intent.URI_INTENT_SCHEME)
                            : new Intent(Intent.ACTION_VIEW, u);
                    i.addCategory(Intent.CATEGORY_BROWSABLE);
                    i.setComponent(null);
                    i.setSelector(null);
                    startActivity(i);
                } catch (Exception ignored) {
                }
                return true;
            }
        });
    }

    /** The service's own sites (and their sign-in), for the microphone and protected media. */
    private boolean trusted(String host) {
        String h = host.toLowerCase(java.util.Locale.ROOT);
        String own = Uri.parse(service().url).getHost();
        String base = own != null ? own.replaceFirst("^(www|play|cloud)\\.", "") : "";
        return h.equals(own) || (!base.isEmpty() && (h.equals(base) || h.endsWith("." + base)));
    }

    /** Chrome's own user agent (desktop: Edge on Windows, which these services stream best to). */
    private String userAgent(boolean desktop) {
        String def;
        try {
            def = WebSettings.getDefaultUserAgent(this);
        } catch (RuntimeException e) {
            def = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36";
        }
        Matcher m = Pattern.compile("Chrome/(\\d+)").matcher(def);
        String major = m.find() ? m.group(1) : "130";
        if (desktop) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + major
                    + ".0.0.0 Safari/537.36 Edg/" + major + ".0.0.0";
        }
        return def.replace("; wv", "").replaceAll("Version/\\d+(\\.\\d+)* ", "");
    }

    @Override
    protected void onResume() {
        super.onResume();
        ConsoleActivity.hideSystemBars(this);
        if (web != null) {
            web.requestFocus();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            ConsoleActivity.hideSystemBars(this);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) {
            web.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (menu != null) {
            menu.dismiss();
        }
        if (web != null) {
            ViewGroup parent = (ViewGroup) web.getParent();
            if (parent != null) {
                parent.removeView(web);
            }
            web.destroy();
            web = null;
        }
        super.onDestroy();
        if (closing) {
            // Nothing of the stream stays behind
            android.os.Process.killProcess(android.os.Process.myPid());
        }
    }

    // ---- The controller -------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int k = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        boolean up = event.getAction() == KeyEvent.ACTION_UP;
        if (web == null) {
            return super.dispatchKeyEvent(event);
        }
        if (k == KeyEvent.KEYCODE_BACK || k == KeyEvent.KEYCODE_BUTTON_MODE || k == KeyEvent.KEYCODE_HOME) {
            if (k == KeyEvent.KEYCODE_HOME) {
                return super.dispatchKeyEvent(event);
            }
            if (up) {
                showMenu();
            }
            return true;
        }
        if (k == KeyEvent.KEYCODE_BUTTON_SELECT) {
            // Held: the menu; a press goes to the game (it's the View button)
            if (down && event.getRepeatCount() == 0) {
                selectHeld = false;
                main.postDelayed(selectHold, HOLD_FOR_MENU_MS);
                web.dispatchKeyEvent(event);
            }
            else if (up) {
                main.removeCallbacks(selectHold);
                // The game saw the press: it sees the release too (even after the menu opened)
                web.dispatchKeyEvent(event);
            }
            return true;
        }
        if (KeyEvent.isGamepadButton(k) || isDpad(k)) {
            // Every button is the game's (an unused B must not become Back)
            web.dispatchKeyEvent(event);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private static boolean isDpad(int k) {
        return k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN || k == KeyEvent.KEYCODE_DPAD_LEFT
                || k == KeyEvent.KEYCODE_DPAD_RIGHT || k == KeyEvent.KEYCODE_DPAD_CENTER;
    }

    // ---- The menu -------------------------------------------------------------------------------

    private void showMenu() {
        if (menu != null && menu.isShowing()) {
            return;
        }
        final CloudService s = service();
        final String official = s.installedOfficial(this);
        final List<String> labels = new ArrayList<>();
        final List<Runnable> actions = new ArrayList<>();
        labels.add(getString(R.string.pocket_cloud_back_home));
        actions.add(this::backToNexus);
        labels.add(getString(R.string.pocket_cloud_reload));
        actions.add(() -> web.reload());
        labels.add(getString(R.string.pocket_cloud_desktop) + ": "
                + getString(desktopMode() ? R.string.pocket_on : R.string.pocket_off));
        actions.add(() -> {
            boolean now = !desktopMode();
            prefs().edit().putBoolean("desktop", now).apply();
            web.getSettings().setUserAgentString(userAgent(now));
            web.reload();
        });
        labels.add(getString(official != null ? R.string.pocket_cloud_open_official : R.string.pocket_cloud_get_official));
        actions.add(this::openOfficialOrStore);
        labels.add(getString(R.string.pocket_cloud_sign_out));
        actions.add(() -> {
            CookieManager.getInstance().removeAllCookies(null);
            WebStorage.getInstance().deleteAllData();
            web.clearCache(true);
            web.loadUrl(s.url);
        });
        labels.add(getString(R.string.pocket_cloud_close, getString(s.name)));
        actions.add(() -> {
            closing = true;
            finishAndRemoveTask();
        });
        menu = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(s.name)
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setOnDismissListener(d -> {
                    ConsoleActivity.hideSystemBars(this);
                    if (web != null) {
                        web.requestFocus();
                    }
                })
                .create();
        menu.show();
        View list = menu.getListView();
        if (list != null) {
            list.requestFocus();
        }
    }

    private void backToNexus() {
        Intent home = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (home != null) {
            home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(home);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        moveTaskToBack(true);
    }

    private void openOfficialOrStore() {
        CloudService s = service();
        String official = s.installedOfficial(this);
        try {
            if (official != null) {
                Intent i = getPackageManager().getLaunchIntentForPackage(official);
                if (i != null) {
                    startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    return;
                }
            }
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + s.officialPackages[0]))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Toast.makeText(this, s.officialPackages[0], Toast.LENGTH_LONG).show();
        }
    }
}
