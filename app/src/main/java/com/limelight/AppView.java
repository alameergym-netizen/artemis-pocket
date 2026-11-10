package com.limelight;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.AppGridAdapter;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.UsbLink;
import com.limelight.pocket.ui.CarouselAdapter;
import com.limelight.pocket.ui.PocketUi;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import com.limelight.utils.CacheHelper;
import com.limelight.utils.Dialog;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.SpinnerDialog;
import com.limelight.utils.UiHelper;

import android.app.Activity;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.KeyEvent;
import android.view.ViewGroup;
import androidx.core.content.ContextCompat;

import org.xmlpull.v1.XmlPullParserException;

public class AppView extends ConsoleActivity implements CarouselAdapter.Listener {
    private AppGridAdapter appGridAdapter;
    private RecyclerView appCarousel;
    private CarouselAdapter carouselAdapter;
    private int contextPosition = -1;
    private View contextView;
    private boolean heroArtOnA = true;
    private String heroTitleForLaunch;

    private String formatPlaytime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        if (hours > 0) {
            return getString(R.string.pocket_playtime_hm, hours, minutes);
        }
        return getString(R.string.pocket_playtime_m, Math.max(1, minutes));
    }
    private Bitmap heroBitmap;
    private boolean initialFocusDone;
    private boolean carouselAnimated;
    private ObjectAnimator kenBurns;
    private String uuidString;
    private ShortcutHelper shortcutHelper;

    private ComputerDetails computer;
    private ComputerManagerService.ApplistPoller poller;
    private SpinnerDialog blockingLoadSpinner;
    private String lastRawApplist;
    private int lastRunningAppId;
    private boolean suspendGridUpdates;
    private boolean inForeground;
    private boolean showHiddenApps;
    private HashSet<Integer> hiddenAppIds = new HashSet<>();

    private PreferenceConfiguration prefConfig;

    private final static int START_OR_RESUME_ID = 1;
    private final static int QUIT_ID = 2;
    private final static int START_WITH_QUIT = 4;
    private final static int VIEW_DETAILS_ID = 5;
    private final static int CREATE_SHORTCUT_ID = 6;
    private final static int EXPORT_LAUNCHER_FILE_ID = 7;
    private final static int HIDE_APP_ID = 8;
    private final static int START_WITH_VDISPLAY = 20;
    private final static int START_WITH_QUIT_VDISPLAY = 21;

    public final static String HIDDEN_APPS_PREF_FILENAME = "HiddenApps";

    public final static String NAME_EXTRA = "Name";
    public final static String UUID_EXTRA = "UUID";
    public final static String NEW_PAIR_EXTRA = "NewPair";
    public final static String SHOW_HIDDEN_APPS_EXTRA = "ShowHiddenApps";

    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        public void onServiceConnected(ComponentName className, IBinder binder) {
            final ComputerManagerService.ComputerManagerBinder localBinder =
                    ((ComputerManagerService.ComputerManagerBinder)binder);

            // Wait in a separate thread to avoid stalling the UI
            new Thread() {
                @Override
                public void run() {
                    // Wait for the binder to be ready
                    localBinder.waitForReady();

                    // Get the computer object
                    computer = localBinder.getComputer(uuidString);
                    if (computer == null) {
                        finish();
                        return;
                    }

                    // Add a launcher shortcut for this PC (forced, since this is user interaction)
                    shortcutHelper.createAppViewShortcut(computer, true, getIntent().getBooleanExtra(NEW_PAIR_EXTRA, false));
                    shortcutHelper.reportComputerShortcutUsed(computer);

                    try {
                        appGridAdapter = new AppGridAdapter(AppView.this,
                                PreferenceConfiguration.readPreferences(AppView.this),
                                computer, localBinder.getUniqueId(),
                                showHiddenApps);
                    } catch (Exception e) {
                        e.printStackTrace();
                        finish();
                        return;
                    }

                    appGridAdapter.updateHiddenApps(hiddenAppIds, true);

                    // Now make the binder visible. We must do this after appGridAdapter
                    // is set to prevent us from reaching updateUiWithServerinfo() and
                    // touching the appGridAdapter prior to initialization.
                    managerBinder = localBinder;

                    // Load the app grid with cached data (if possible).
                    // This must be done _before_ startComputerUpdates()
                    // so the initial serverinfo response can update the running
                    // icon.
                    populateAppGridWithCache();

                    // Start updates
                    startComputerUpdates();

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || isChangingConfigurations()) {
                                return;
                            }

                            setupCarousel();
                        }
                    });
                }
            }.start();
        }

        public void onServiceDisconnected(ComponentName className) {
            managerBinder = null;
        }
    };

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // If appGridAdapter is initialized, let it know about the configuration change.
        // If not, it will pick it up when it initializes.
        if (appGridAdapter != null) {
            // Update the app grid adapter to create grid items with the correct layout
            appGridAdapter.updateLayoutWithPreferences(this, this.prefConfig);

            setupCarousel();
        }
    }

    private void startComputerUpdates() {
        // Don't start polling if we're not bound or in the foreground
        if (managerBinder == null || !inForeground) {
            return;
        }

        managerBinder.startPolling(new ComputerManagerListener() {
            @Override
            public void notifyComputerUpdated(final ComputerDetails details) {
                // Do nothing if updates are suspended
                if (suspendGridUpdates) {
                    return;
                }

                // Don't care about other computers
                if (!details.uuid.equalsIgnoreCase(uuidString)) {
                    return;
                }

                if (details.state == ComputerDetails.State.OFFLINE) {
                    // The PC is unreachable now
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.lost_connection, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // Close immediately if the PC is no longer paired
                if (details.state == ComputerDetails.State.ONLINE && details.pairState != PairingManager.PairState.PAIRED) {
                    AppView.this.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Disable shortcuts referencing this PC for now
                            shortcutHelper.disableComputerShortcut(details,
                                    getResources().getString(R.string.scut_not_paired));

                            // Display a toast to the user and quit the activity
                            Toast.makeText(AppView.this, R.string.scut_not_paired, Toast.LENGTH_SHORT).show();
                            finish();
                        }
                    });

                    return;
                }

                // App list is the same or empty
                if (details.rawAppList == null || details.rawAppList.equals(lastRawApplist)) {

                    // Let's check if the running app ID changed
                    if (details.runningGameId != lastRunningAppId) {
                        // Update the currently running game using the app ID
                        lastRunningAppId = details.runningGameId;
                        updateUiWithServerinfo(details);
                    }

                    return;
                }

                lastRunningAppId = details.runningGameId;
                lastRawApplist = details.rawAppList;

                try {
                    updateUiWithAppList(NvHTTP.getAppListByReader(new StringReader(details.rawAppList)));
                    updateUiWithServerinfo(details);

                    if (blockingLoadSpinner != null) {
                        blockingLoadSpinner.dismiss();
                        blockingLoadSpinner = null;
                    }
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                }
            }
        });

        if (poller == null) {
            poller = managerBinder.createAppListPoller(computer);
        }
        poller.start();
    }

    private void stopComputerUpdates() {
        if (poller != null) {
            poller.stop();
        }

        if (managerBinder != null) {
            managerBinder.stopPolling();
        }

        if (appGridAdapter != null) {
            appGridAdapter.cancelQueuedOperations();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        setContentView(R.layout.activity_app_view);

        // Allow floating expanded PiP overlays while browsing apps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        UiHelper.notifyNewRootView(this);

        // Setup the profiles button
        findViewById(R.id.profilesButton)
            .setOnClickListener(v -> startActivity(new Intent(this, ProfilesActivity.class)));

        showHiddenApps = getIntent().getBooleanExtra(SHOW_HIDDEN_APPS_EXTRA, false);
        uuidString = getIntent().getStringExtra(UUID_EXTRA);

        SharedPreferences hiddenAppsPrefs = getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE);
        for (String hiddenAppIdStr : hiddenAppsPrefs.getStringSet(uuidString, new HashSet<String>())) {
            hiddenAppIds.add(Integer.parseInt(hiddenAppIdStr));
        }

        String computerName = getIntent().getStringExtra(NAME_EXTRA);

        TextView label = findViewById(R.id.appListText);
        setTitle(computerName);
        label.setText(computerName);

        this.prefConfig = PreferenceConfiguration.readPreferences(this);

        // Bind to the computer manager service
        bindService(new Intent(this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);
    }

    private void updateHiddenApps(boolean hideImmediately) {
        HashSet<String> hiddenAppIdStringSet = new HashSet<>();

        for (Integer hiddenAppId : hiddenAppIds) {
            hiddenAppIdStringSet.add(hiddenAppId.toString());
        }

        getSharedPreferences(HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .putStringSet(uuidString, hiddenAppIdStringSet)
                .apply();

        appGridAdapter.updateHiddenApps(hiddenAppIds, hideImmediately);
    }

    private void populateAppGridWithCache() {
        try {
            // Try to load from cache
            lastRawApplist = CacheHelper.readInputStreamToString(CacheHelper.openCacheFileForInput(getCacheDir(), "applist", uuidString));
            List<NvApp> applist = NvHTTP.getAppListByReader(new StringReader(lastRawApplist));
            updateUiWithAppList(applist);
            LimeLog.info("Loaded applist from cache");
        } catch (IOException | XmlPullParserException e) {
            if (lastRawApplist != null) {
                LimeLog.warning("Saved applist corrupted: "+lastRawApplist);
                e.printStackTrace();
            }
            LimeLog.info("Loading applist from the network");
            // We'll need to load from the network
            loadAppsBlocking();
        }
    }

    private void loadAppsBlocking() {
        blockingLoadSpinner = SpinnerDialog.displayDialog(this, getResources().getString(R.string.applist_refresh_title),
                getResources().getString(R.string.applist_refresh_msg), true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (kenBurns != null) {
            kenBurns.cancel();
        }

        SpinnerDialog.closeDialogs(this);
        Dialog.closeDialogs();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        inForeground = true;
        startComputerUpdates();

        // A round button like the others; a pill with the name when a profile is active
        styleProfileButton(findViewById(R.id.profilesButton), ProfilesManager.getInstance().getActiveName());

        // Art chosen in the art picker (or fetched meanwhile): reload it
        if (pocketArtVersion != -1 && pocketArtVersion != com.limelight.pocket.PocketArt.version()) {
            pocketArtChanged();
        }
        pocketArtVersion = com.limelight.pocket.PocketArt.version();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == ShortcutHelper.REQUEST_CODE_EXPORT_ART_FILE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
                Uri uri = data.getData();
                ShortcutHelper.writeArtFileToUri(this, uri);
            } else {
                // Clear the content if the user cancelled or if there was an error before this point
                ShortcutHelper.artFileContentToExport = null;
                // Show "File export cancelled." toast only if the user explicitly cancelled.
                if (resultCode == Activity.RESULT_CANCELED) {
                    Toast.makeText(this, R.string.file_export_cancelled, Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);

        int position = contextPosition;
        View targetView = contextView;
        if (menuInfo instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) menuInfo).position;
            targetView = ((AdapterContextMenuInfo) menuInfo).targetView;
        }
        if (appGridAdapter == null || position < 0 || position >= appGridAdapter.getCount()) {
            return;
        }
        contextPosition = position;
        contextView = targetView;
        AppObject selectedApp = (AppObject) appGridAdapter.getItem(position);

        menu.setHeaderTitle(selectedApp.app.getAppName());

        if (lastRunningAppId == 0) {
            if (prefConfig.useVirtualDisplay) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_start_primarydisplay));
            } else {
                menu.add(Menu.NONE, START_WITH_VDISPLAY, 1, getResources().getString(R.string.applist_menu_start_vdisplay));
            }
        } else {
            if (lastRunningAppId == selectedApp.app.getAppId()) {
                menu.add(Menu.NONE, START_OR_RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }
            else {
                if (prefConfig.useVirtualDisplay) {
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT, 2, getResources().getString(R.string.applist_menu_quit_and_start_primarydisplay));
                } else{
                    menu.add(Menu.NONE, START_WITH_QUIT, 1, getResources().getString(R.string.applist_menu_quit_and_start));
                    menu.add(Menu.NONE, START_WITH_QUIT_VDISPLAY, 2, getResources().getString(R.string.applist_menu_quit_and_start_vdisplay));
                }
            }
        }

        // Only show the hide checkbox if this is not the currently running app or it's already hidden
        if (lastRunningAppId != selectedApp.app.getAppId() || selectedApp.isHidden) {
            MenuItem hideAppItem = menu.add(Menu.NONE, HIDE_APP_ID, 3, getResources().getString(R.string.applist_menu_hide_app));
            hideAppItem.setCheckable(true);
            hideAppItem.setChecked(selectedApp.isHidden);
        }

        menu.add(Menu.NONE, VIEW_DETAILS_ID, 4, getResources().getString(R.string.applist_menu_details));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Only add an option to create shortcut if box art is loaded
            // and when we're in grid-mode (not list-mode).
            ImageView appImageView = targetView != null ? targetView.findViewById(R.id.grid_image) : null;
            if (appImageView != null && appImageView.getDrawable() instanceof BitmapDrawable) {
                // We have a grid ImageView, so we must be in grid-mode
                BitmapDrawable drawable = (BitmapDrawable)appImageView.getDrawable();
                if (drawable != null && drawable.getBitmap() != null) {
                    // We have a bitmap loaded too
                    menu.add(Menu.NONE, CREATE_SHORTCUT_ID, 5, getResources().getString(R.string.applist_menu_scut));
                }
            }
        }

        menu.add(Menu.NONE, EXPORT_LAUNCHER_FILE_ID, 6, getResources().getString(R.string.applist_menu_export_launcher));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position = contextPosition;
        View targetView = contextView;
        if (item.getMenuInfo() instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) item.getMenuInfo()).position;
            targetView = ((AdapterContextMenuInfo) item.getMenuInfo()).targetView;
        }
        if (appGridAdapter == null || position < 0 || position >= appGridAdapter.getCount()) {
            return super.onContextItemSelected(item);
        }
        final AppObject app = (AppObject) appGridAdapter.getItem(position);
        int itemId = item.getItemId();
        switch (itemId) {
            case START_WITH_QUIT:
            case START_WITH_QUIT_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_QUIT_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                            @Override
                            public void run() {
                                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true);
                            }
                        }, null),
                        null
                    );
                } else {
                    // Display a confirmation dialog first
                    UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                        @Override
                        public void run() {
                            ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                        }
                    }, null);
                }
                return true;
            }

            case START_OR_RESUME_ID:
            case START_WITH_VDISPLAY: {
                boolean withVDiaplay = itemId == START_WITH_VDISPLAY;
                if (withVDiaplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                    UiHelper.displayVdisplayConfirmationDialog(
                            AppView.this,
                            computer,
                            () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                            null
                    );
                } else {
                    // Resume is the same as start for us
                    ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, withVDiaplay);
                }
                return true;
            }

            case QUIT_ID: {
                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        suspendGridUpdates = true;
                        ServerHelper.doQuit(AppView.this, computer,
                                app.app, managerBinder, new Runnable() {
                                    @Override
                                    public void run() {
                                        // Trigger a poll immediately
                                        suspendGridUpdates = false;
                                        if (poller != null) {
                                            poller.pollNow();
                                        }
                                    }
                                });
                    }
                }, null);
                return true;
            }

            case VIEW_DETAILS_ID: {
                Dialog.displayDialog(AppView.this, getResources().getString(R.string.title_details), app.app.toString(), false);
                return true;
            }

            case HIDE_APP_ID: {
                if (item.isChecked()) {
                    // Transitioning hidden to shown
                    hiddenAppIds.remove(app.app.getAppId());
                } else {
                    // Transitioning shown to hidden
                    hiddenAppIds.add(app.app.getAppId());
                }
                updateHiddenApps(false);
                return true;
            }

            case CREATE_SHORTCUT_ID: {
                ImageView appImageView = targetView != null ? targetView.findViewById(R.id.grid_image) : null;
                if (appImageView == null || !(appImageView.getDrawable() instanceof BitmapDrawable)) {
                    return true;
                }
                Bitmap appBits = ((BitmapDrawable) appImageView.getDrawable()).getBitmap();
                if (!shortcutHelper.createPinnedGameShortcut(computer, app.app, appBits)) {
                    Toast.makeText(AppView.this, getResources().getString(R.string.unable_to_pin_shortcut), Toast.LENGTH_LONG).show();
                }
                return true;
            }

            case EXPORT_LAUNCHER_FILE_ID: {
                if (app.app.getAppUUID() == null || (app.app.getAppUUID() != null && app.app.getAppUUID().isEmpty())) {
                    UiHelper.displayConfirmationDialog(
                            AppView.this,
                            getResources().getString(R.string.title_export_sunshine_launcher_file),
                            getResources().getString(R.string.message_export_sunshine_launcher_file),
                            getResources().getString(R.string.proceed),
                            getResources().getString(R.string.cancel),
                            () -> shortcutHelper.exportLauncherFile(computer, app.app),
                            null
                    );
                } else {
                    shortcutHelper.exportLauncherFile(computer, app.app);
                }
                return true;
            }

            default: {
                return super.onContextItemSelected(item);
            }
        }
    }

    private void updateUiWithServerinfo(final ComputerDetails details) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                    // Look through our current app list to tag the running app
                for (int i = 0; i < appGridAdapter.getCount(); i++) {
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // There can only be one or zero apps running.
                    if (existingApp.isRunning &&
                            existingApp.app.getAppId() == details.runningGameId) {
                        // This app was running and still is, so we're done now
                        return;
                    }
                    else if (existingApp.app.getAppId() == details.runningGameId) {
                        // This app wasn't running but now is
                        existingApp.isRunning = true;
                        updated = true;
                    }
                    else if (existingApp.isRunning) {
                        // This app was running but now isn't
                        existingApp.isRunning = false;
                        updated = true;
                    }
                    else {
                        // This app wasn't running and still isn't
                    }
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                    updateHero(carouselAdapter != null ? carouselAdapter.getFocusedPosition() : -1, null);
                    updateHints();
                }
            }
        });
    }

    private void updateUiWithAppList(final List<NvApp> appList) {
        AppView.this.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                boolean updated = false;

                // First handle app updates and additions
                for (NvApp app : appList) {
                    boolean foundExistingApp = false;

                    // Try to update an existing app in the list first
                    for (int i = 0; i < appGridAdapter.getCount(); i++) {
                        AppObject existingApp = (AppObject) appGridAdapter.getItem(i);
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            // Found the app; update its properties
                            if (!existingApp.app.getAppName().equals(app.getAppName())) {
                                existingApp.app.setAppName(app.getAppName());
                                updated = true;
                            }

                            foundExistingApp = true;
                            break;
                        }
                    }

                    if (!foundExistingApp) {
                        // This app must be new
                        appGridAdapter.addApp(new AppObject(app));

                        // We could have a leftover shortcut from last time this PC was paired
                        // or if this app was removed then added again. Enable those shortcuts
                        // again if present.
                        shortcutHelper.enableAppShortcut(computer, app);

                        updated = true;
                    }
                }

                // Next handle app removals
                int i = 0;
                while (i < appGridAdapter.getCount()) {
                    boolean foundExistingApp = false;
                    AppObject existingApp = (AppObject) appGridAdapter.getItem(i);

                    // Check if this app is in the latest list
                    for (NvApp app : appList) {
                        if (existingApp.app.getAppId() == app.getAppId()) {
                            foundExistingApp = true;
                            break;
                        }
                    }

                    // This app was removed in the latest app list
                    if (!foundExistingApp) {
                        shortcutHelper.disableAppShortcut(computer, existingApp.app, getString(R.string.app_removed_from_pc));
                        appGridAdapter.removeApp(existingApp);
                        updated = true;

                        // Check this same index again because the item at i+1 is now at i after
                        // the removal
                        continue;
                    }

                    // Move on to the next item
                    i++;
                }

                if (updated) {
                    appGridAdapter.notifyDataSetChanged();
                    updateSectionTitle();
                    if (appCarousel != null) {
                        appCarousel.post(AppView.this::focusInitialGame);
                    }
                }
            }
        });
    }

    // ---- Console carousel ------------------------------------------------------------

    private void setupCarousel() {
        if (appGridAdapter == null) {
            return;
        }
        appCarousel = findViewById(R.id.pocketAppCarousel);
        if (appCarousel == null) {
            return;
        }
        appCarousel.setLayoutManager(new com.limelight.pocket.ui.AnchoredLayoutManager(this));
        appCarousel.setItemAnimator(null);
        carouselAdapter = new CarouselAdapter(appGridAdapter, this, 14);
        appCarousel.setAdapter(carouselAdapter);
        updateSectionTitle();
        updateHints();
        appCarousel.post(this::focusInitialGame);
    }

    /** Lands on the running game, else the last one played, else the first. */
    private void focusInitialGame() {
        if (initialFocusDone || appCarousel == null || appGridAdapter == null || appGridAdapter.getCount() == 0) {
            return;
        }
        int target = 0;
        int lastAppId = computer != null ? PocketPrefs.getLastAppId(this, computer.uuid) : 0;
        int lastIndex = -1;
        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            AppObject obj = (AppObject) appGridAdapter.getItem(i);
            if (obj.isRunning) {
                target = i;
                lastIndex = -2;
                break;
            }
            if (obj.app.getAppId() == lastAppId) {
                lastIndex = i;
            }
        }
        if (lastIndex >= 0) {
            target = lastIndex;
        }
        final int position = target;
        appCarousel.scrollToPosition(position);
        appCarousel.post(() -> {
            RecyclerView.ViewHolder holder = appCarousel.findViewHolderForAdapterPosition(position);
            if (holder != null) {
                initialFocusDone = true;
                holder.itemView.requestFocus();
                updateHero(position, holder.itemView instanceof ViewGroup && ((ViewGroup) holder.itemView).getChildCount() > 0 ?
                        ((ViewGroup) holder.itemView).getChildAt(0) : null);
            }
        });
    }

    @Override
    public void onItemClicked(int pos, View itemView) {
        if (appGridAdapter == null || pos < 0 || pos >= appGridAdapter.getCount()) {
            return;
        }
        AppObject app = (AppObject) appGridAdapter.getItem(pos);

        // Only open the context menu if something is running, otherwise start it
        if (lastRunningAppId != 0) {
            if (prefConfig.resumeWithoutConfirm && lastRunningAppId == app.app.getAppId()) {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            } else {
                onItemOptions(pos, itemView);
            }
        } else {
            if (prefConfig.useVirtualDisplay && !(computer.vDisplaySupported && computer.vDisplayDriverReady)) {
                UiHelper.displayVdisplayConfirmationDialog(
                        AppView.this,
                        computer,
                        () -> ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, true),
                        null
                );
            } else {
                ServerHelper.doStart(AppView.this, app.app, computer, managerBinder, prefConfig.useVirtualDisplay);
            }
        }
    }

    @Override
    public void onItemOptions(int pos, View itemView) {
        if (appGridAdapter == null || pos < 0 || pos >= appGridAdapter.getCount() || itemView == null) {
            return;
        }
        contextPosition = pos;
        contextView = itemView;
        // Artemis Pocket: options as a console panel, with this game's own settings
        final AppObject obj = (AppObject) appGridAdapter.getItem(pos);
        final int appId = obj.app.getAppId();
        final String gameName = obj.app.getAppName();
        List<com.limelight.pocket.ui.StreamMenuView.Item> extra = new ArrayList<>();
        if (computer != null) {
            final String title = getString(R.string.pocket_gs_title);
            String summary = com.limelight.pocket.GameProfile.summary(this, computer.uuid, appId);
            extra.add(com.limelight.pocket.ui.StreamMenuView.Item.submenu(title,
                    () -> pushConsolePage(title, pocketGameSettings(appId, gameName)))
                    .withValue(summary.isEmpty() ? null : getString(R.string.pocket_gs_custom)));
            // Cover, background and logo from SteamGridDB
            final String uuid = computer.uuid;
            extra.add(com.limelight.pocket.ui.StreamMenuView.Item.action(getString(R.string.pocket_art_change), () -> {
                hideConsolePanel();
                com.limelight.pocket.ArtPickerActivity.open(this, uuid, appId, gameName);
            }));
            // Favourites lead the row
            final boolean favorite = PocketPrefs.isFavorite(this, uuid, appId);
            extra.add(com.limelight.pocket.ui.StreamMenuView.Item.action(
                    getString(favorite ? R.string.pocket_fav_remove : R.string.pocket_fav_add), () -> {
                        hideConsolePanel();
                        PocketPrefs.setFavorite(this, uuid, appId, !favorite);
                        com.limelight.pocket.PocketSounds.play(this, favorite
                                ? com.limelight.pocket.PocketSounds.TOGGLE_OFF : com.limelight.pocket.PocketSounds.TOGGLE_ON);
                        if (appGridAdapter != null) {
                            appGridAdapter.resort();
                        }
                        pocketFocusApp(appId);
                    }));
            // Library order, changed right here with left / right
            final String[] orderLabels = {getString(R.string.pocket_sort_recent), getString(R.string.pocket_sort_az),
                    getString(R.string.pocket_sort_pc)};
            extra.add(com.limelight.pocket.ui.StreamMenuView.Item.value(getString(R.string.pocket_set_sort),
                    orderLabels[PocketPrefs.sortModeIndex(this)], direction -> {
                        int next = (PocketPrefs.sortModeIndex(this) + direction + orderLabels.length) % orderLabels.length;
                        PocketPrefs.prefs(this).edit().putString(PocketPrefs.KEY_SORT_MODE, PocketPrefs.SORT_MODES[next]).apply();
                        if (appGridAdapter != null) {
                            appGridAdapter.resort();
                        }
                        pocketFocusApp(appId);
                        return orderLabels[next];
                    }));
            if (com.limelight.pocket.PocketArt.hasKey(this)) {
                extra.add(com.limelight.pocket.ui.StreamMenuView.Item.action(getString(R.string.pocket_art_fetch_all), () -> {
                    hideConsolePanel();
                    pocketFetchAllArt();
                }));
            }
        }
        showOptionsFor(itemView, pocketHeroDetails(appId), extra);
    }

    // ---- Artemis Pocket: per-game settings ----------------------------------------------------

    private String pocketHeroDetails(int appId) {
        if (computer == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        long played = PocketPrefs.getPlaytime(this, computer.uuid, appId);
        if (played >= 60) {
            sb.append(getString(R.string.pocket_playtime, formatPlaytime(played)));
        }
        long last = PocketPrefs.getLastPlayed(this, computer.uuid, appId);
        if (last > 0) {
            if (sb.length() > 0) {
                sb.append("  \u00b7  ");
            }
            sb.append(android.text.format.DateUtils.getRelativeTimeSpanString(last, System.currentTimeMillis(),
                    android.text.format.DateUtils.MINUTE_IN_MILLIS));
        }
        String custom = com.limelight.pocket.GameProfile.summary(this, computer.uuid, appId);
        if (!custom.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("  \u00b7  ");
            }
            sb.append(getString(R.string.pocket_gs_custom_line, custom));
        }
        return sb.toString();
    }

    private String pocketDefaultLabel() {
        return getString(R.string.pocket_gs_default);
    }

    private List<com.limelight.pocket.ui.StreamMenuView.Item> pocketGameSettings(final int appId, final String gameName) {
        final String uuid = computer.uuid;
        final android.content.Context ctx = this;
        List<com.limelight.pocket.ui.StreamMenuView.Item> page = new ArrayList<>();

        page.add(com.limelight.pocket.ui.StreamMenuView.Item.value(getString(R.string.pocket_sm_bitrate),
                pocketBitrateLabel(com.limelight.pocket.GameProfile.bitrateKbps(ctx, uuid, appId)), direction -> {
                    int[] values = com.limelight.pocket.GameProfile.BITRATES_MBPS;
                    int i = com.limelight.pocket.GameProfile.indexOf(values,
                            com.limelight.pocket.GameProfile.bitrateKbps(ctx, uuid, appId) / 1000);
                    i = Math.max(0, Math.min(values.length - 1, i + direction));
                    com.limelight.pocket.GameProfile.setBitrateKbps(ctx, uuid, appId, values[i] * 1000);
                    return pocketBitrateLabel(values[i] * 1000);
                }));

        page.add(com.limelight.pocket.ui.StreamMenuView.Item.value(getString(R.string.pocket_sm_fps),
                pocketFpsLabel(com.limelight.pocket.GameProfile.fps(ctx, uuid, appId)), direction -> {
                    int[] values = com.limelight.pocket.GameProfile.FPS;
                    int i = com.limelight.pocket.GameProfile.indexOf(values, com.limelight.pocket.GameProfile.fps(ctx, uuid, appId));
                    i = Math.max(0, Math.min(values.length - 1, i + direction));
                    com.limelight.pocket.GameProfile.setFps(ctx, uuid, appId, values[i]);
                    return pocketFpsLabel(values[i]);
                }));

        page.add(com.limelight.pocket.ui.StreamMenuView.Item.value(getString(R.string.pocket_gs_resolution),
                pocketResLabel(com.limelight.pocket.GameProfile.resolution(ctx, uuid, appId)), direction -> {
                    String[] values = com.limelight.pocket.GameProfile.RESOLUTIONS;
                    int i = com.limelight.pocket.GameProfile.indexOf(values,
                            com.limelight.pocket.GameProfile.resolution(ctx, uuid, appId));
                    i = Math.max(0, Math.min(values.length - 1, i + direction));
                    com.limelight.pocket.GameProfile.setResolution(ctx, uuid, appId, values[i]);
                    return pocketResLabel(values[i]);
                }));

        page.add(com.limelight.pocket.ui.StreamMenuView.Item.value(getString(R.string.pocket_sm_framegen),
                pocketFgLabel(com.limelight.pocket.GameProfile.frameGen(ctx, uuid, appId)), direction -> {
                    int mode = com.limelight.pocket.GameProfile.frameGen(ctx, uuid, appId);
                    int n = com.limelight.pocket.GameProfile.FRAME_GEN_MODES;
                    mode = (mode + (direction > 0 ? 1 : n - 1)) % n;
                    com.limelight.pocket.GameProfile.setFrameGen(ctx, uuid, appId, mode);
                    return pocketFgLabel(mode);
                }));

        page.add(com.limelight.pocket.ui.StreamMenuView.Item.action(getString(R.string.pocket_gs_reset), () -> {
            com.limelight.pocket.GameProfile.clear(ctx, uuid, appId);
            Toast.makeText(ctx, R.string.pocket_gs_reset_done, Toast.LENGTH_SHORT).show();
            updateHero(carouselAdapter != null ? carouselAdapter.getFocusedPosition() : -1, null);
        }));
        page.add(com.limelight.pocket.ui.StreamMenuView.Item.repeatable(getString(R.string.pocket_gs_note, gameName), null));
        return page;
    }

    private String pocketBitrateLabel(int kbps) {
        return kbps <= 0 ? pocketDefaultLabel() : (kbps / 1000) + " Mbps";
    }

    private String pocketFpsLabel(int fps) {
        return fps <= 0 ? pocketDefaultLabel() : fps + " FPS";
    }

    private String pocketResLabel(String res) {
        int[] wh = com.limelight.pocket.GameProfile.parseResolution(res);
        return wh == null ? pocketDefaultLabel() : wh[0] + "\u00d7" + wh[1];
    }

    private String pocketFgLabel(int mode) {
        if (mode == com.limelight.pocket.GameProfile.FRAME_GEN_ON) {
            return getString(R.string.pocket_on);
        }
        if (mode == com.limelight.pocket.GameProfile.FRAME_GEN_OFF) {
            return getString(R.string.pocket_off);
        }
        if (mode == com.limelight.pocket.GameProfile.FRAME_GEN_LSFG) {
            return getString(R.string.pocket_framegen_lsfg_short);
        }
        return pocketDefaultLabel();
    }

    @Override
    public void onItemFocused(int pos, View itemView) {
        updateHero(pos, itemView);
        // First time in a library: what A, Y and START do
        scheduleTip(PocketPrefs.TIP_GAMES, itemView, 14, R.string.pocket_tip_games_title, R.string.pocket_tip_games_body);
    }

    private void updateSectionTitle() {
        TextView section = findViewById(R.id.pocketSectionTitle);
        if (section != null && appGridAdapter != null) {
            section.setText(getString(R.string.pocket_library_count, appGridAdapter.getCount()));
        }
    }

    private void updateHints() {
        boolean running = lastRunningAppId != 0;
        if (running) {
            setHints(hintA(this, R.string.pocket_hint_play),
                    hintY(this, R.string.pocket_hint_options),
                    hintX(this, R.string.pocket_hint_quit_game),
                    hintB(this, R.string.pocket_hint_back),
                    hintStart(this, R.string.pocket_hint_settings));
        }
        else {
            setHints(hintA(this, R.string.pocket_hint_play),
                    hintY(this, R.string.pocket_hint_options),
                    hintB(this, R.string.pocket_hint_back),
                    hintStart(this, R.string.pocket_hint_settings));
        }
    }

    private void updateHero(int position, View card) {
        TextView title = findViewById(R.id.pocketHeroTitle);
        TextView subtitle = findViewById(R.id.pocketHeroSubtitle);
        if (title == null || subtitle == null || appGridAdapter == null) {
            return;
        }
        if (position < 0 || position >= appGridAdapter.getCount()) {
            return;
        }
        AppObject obj = (AppObject) appGridAdapter.getItem(position);
        int dir = pocketHeroPos < 0 ? 0 : Integer.signum(position - pocketHeroPos);
        if (title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dir = -dir;
        }
        boolean moved = position != pocketHeroPos;
        pocketHeroPos = position;
        com.limelight.pocket.ui.RowIndicator indicator = findViewById(R.id.pocketRowIndicator);
        if (indicator != null) {
            indicator.set(position, appGridAdapter.getCount());
        }
        com.limelight.pocket.ui.PocketMotion.swapText(title, obj.app.getAppName(), dir);
        heroTitleForLaunch = obj.app.getAppName();
        TextView playtime = findViewById(R.id.pocketHeroPlaytime);
        if (playtime != null) {
            String line = pocketHeroDetails(obj.app.getAppId());
            if (moved && !line.isEmpty() && com.limelight.pocket.ui.PocketMotion.on(playtime)) {
                playtime.animate().cancel();
                playtime.setAlpha(0f);
                playtime.animate().alpha(1f).setStartDelay(140).setDuration(320).start();
            }
            playtime.setText(line);
            playtime.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        }

        int lastAppId = computer != null ? PocketPrefs.getLastAppId(this, computer.uuid) : 0;
        if (moved) {
            com.limelight.pocket.ui.PocketMotion.bump(subtitle);
        }
        if (obj.isRunning) {
            // Glass chip with the accent: the filled Resume pill is the one bright thing here
            subtitle.setText(R.string.pocket_now_playing);
            subtitle.setTextColor(com.limelight.pocket.ui.PocketTheme.accent(this));
            subtitle.setBackgroundResource(R.drawable.pocket_chip_bg);
        }
        else {
            String chip = getString(obj.app.getAppId() == lastAppId ? R.string.pocket_last_played : R.string.pocket_ready_to_play);
            if (computer != null && PocketPrefs.isFavorite(this, computer.uuid, obj.app.getAppId())) {
                chip = "\u2605  " + chip;
            }
            subtitle.setText(chip);
            subtitle.setTextColor(ContextCompat.getColor(this, R.color.pocket_text));
            subtitle.setBackgroundResource(R.drawable.pocket_chip_bg);
        }

        // The Play button of the hero (A), in the accent colour
        TextView play = findViewById(R.id.pocketHeroPlay);
        if (play != null) {
            if (play.getBackground() == null) {
                android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                pill.setCornerRadius(100 * getResources().getDisplayMetrics().density);
                pill.setColor(com.limelight.pocket.ui.PocketTheme.accent(this));
                play.setBackground(pill);
            }
            String label = "\u25B6  " + getString(obj.isRunning ? R.string.pocket_hero_resume : R.string.pocket_hero_play);
            if (!label.contentEquals(play.getText())) {
                play.setText(label);
                if (moved || play.getAlpha() < 1f) {
                    com.limelight.pocket.ui.PocketMotion.bump(play);
                }
            }
        }

        if (card != null) {
            ImageView art = card.findViewById(R.id.grid_image);
            if (art != null && art.getDrawable() instanceof BitmapDrawable) {
                showHeroArt(((BitmapDrawable) art.getDrawable()).getBitmap());
            }
        }
        pocketShowGameArt(obj, card);
    }

    private int pocketHeroPos = -1;

    // ---- Artemis Pocket: key art and logos (SteamGridDB) --------------------------------------

    private int pocketArtFor = -1;
    private int pocketArtVersion = -1;
    private boolean keyArtOnA;
    private Bitmap keyArtShown;
    private Bitmap logoShown;
    private Runnable pocketAutoArt;
    private final java.util.Map<Bitmap, Boolean> pocketDarkLogos = new java.util.WeakHashMap<>();

    /** The focused game's background and logo (if any), and fetching them once you rest on it. */
    private void pocketShowGameArt(final AppObject obj, final View card) {
        if (computer == null || appCarousel == null) {
            return;
        }
        final int appId = obj.app.getAppId();
        final String uuid = computer.uuid;
        pocketArtFor = appId;
        // Not in memory yet: the previous game's logo must not linger next to this game's details
        if (com.limelight.pocket.PocketArt.cached(this, uuid, appId, com.limelight.pocket.PocketArt.LOGO) == null) {
            showLogo(null);
        }
        if (!com.limelight.pocket.PocketArt.has(this, uuid, appId, com.limelight.pocket.PocketArt.HERO)) {
            showKeyArt(null);
        }
        com.limelight.pocket.PocketArt.load(this, uuid, appId, com.limelight.pocket.PocketArt.HERO, bitmap -> {
            if (pocketArtFor == appId) {
                showKeyArt(bitmap);
            }
        });
        com.limelight.pocket.PocketArt.load(this, uuid, appId, com.limelight.pocket.PocketArt.LOGO, bitmap -> {
            if (pocketArtFor == appId) {
                showLogo(bitmap);
            }
        });
        if (pocketAutoArt != null) {
            appCarousel.removeCallbacks(pocketAutoArt);
        }
        final boolean needsCover = pocketNeedsCover(card);
        final String name = obj.app.getAppName();
        pocketAutoArt = () -> com.limelight.pocket.PocketArt.autoFetch(this, uuid, appId, name, needsCover,
                this::pocketArtChanged);
        appCarousel.postDelayed(pocketAutoArt, 900);
    }

    /** Puts the focus back on a game after the row was sorted again. */
    private void pocketFocusApp(int appId) {
        if (appGridAdapter == null || appCarousel == null) {
            return;
        }
        for (int i = 0; i < appGridAdapter.getCount(); i++) {
            if (((AppObject) appGridAdapter.getItem(i)).app.getAppId() == appId) {
                final int pos = i;
                appCarousel.scrollToPosition(pos);
                appCarousel.post(() -> {
                    RecyclerView.LayoutManager lm = appCarousel.getLayoutManager();
                    View v = lm != null ? lm.findViewByPosition(pos) : null;
                    if (v != null) {
                        v.requestFocus();
                    }
                });
                return;
            }
        }
    }

    /** Backgrounds, logos and missing covers for every game here, in one go. */
    private void pocketFetchAllArt() {
        if (computer == null || appGridAdapter == null) {
            return;
        }
        int n = appGridAdapter.getCount();
        int[] ids = new int[n];
        String[] names = new String[n];
        for (int i = 0; i < n; i++) {
            AppObject o = (AppObject) appGridAdapter.getItem(i);
            ids[i] = o.app.getAppId();
            names[i] = o.app.getAppName();
        }
        Toast.makeText(this, getString(R.string.pocket_art_fetch_all_started, n), Toast.LENGTH_SHORT).show();
        com.limelight.pocket.PocketArt.fetchAll(this, computer.uuid, ids, names, (changed, badKey) -> {
            if (isFinishing()) {
                return;
            }
            Toast.makeText(this, badKey ? getString(R.string.pocket_art_bad_key)
                    : getString(R.string.pocket_art_fetch_all_done, changed), Toast.LENGTH_LONG).show();
            pocketArtChanged();
        });
    }

    /** The card has no real cover (still loading, none, or a grey placeholder). */
    private boolean pocketNeedsCover(View card) {
        ImageView art = card != null ? card.findViewById(R.id.grid_image) : null;
        if (art == null || !(art.getDrawable() instanceof BitmapDrawable)) {
            return false;
        }
        Bitmap b = ((BitmapDrawable) art.getDrawable()).getBitmap();
        return b != null && b.getWidth() > 1 && com.limelight.pocket.ui.HeroArt.isPlaceholder(b);
    }

    /** New art arrived or was chosen: cards reload theirs and the hero updates. */
    private void pocketArtChanged() {
        pocketArtVersion = com.limelight.pocket.PocketArt.version();
        if (appGridAdapter != null) {
            appGridAdapter.notifyDataSetChanged();
        }
        keyArtShown = null;
        logoShown = null;
        if (appGridAdapter != null && pocketHeroPos >= 0 && pocketHeroPos < appGridAdapter.getCount()) {
            View focused = appCarousel != null ? appCarousel.getFocusedChild() : null;
            View card = focused instanceof ViewGroup && ((ViewGroup) focused).getChildCount() > 0
                    ? ((ViewGroup) focused).getChildAt(0) : null;
            pocketShowGameArt((AppObject) appGridAdapter.getItem(pocketHeroPos), card);
        }
    }

    private void showKeyArt(Bitmap bitmap) {
        View frame = findViewById(R.id.pocketKeyArtFrame);
        View under = findViewById(R.id.pocketKeyArtUnderlay);
        ImageView a = findViewById(R.id.pocketKeyArtA);
        ImageView b = findViewById(R.id.pocketKeyArtB);
        if (frame == null || under == null || a == null || b == null) {
            return;
        }
        // The "Starting..." screen uses it too
        com.limelight.pocket.ui.LaunchOverlay.rememberKeyArt(heroTitleForLaunch, bitmap);
        if (bitmap != null && !PocketPrefs.BACKDROP_ART.equals(PocketPrefs.backdrop(this))) {
            bitmap = null;
        }
        if (bitmap == keyArtShown && (bitmap != null || frame.getAlpha() == 0f)) {
            return;
        }
        keyArtShown = bitmap;
        boolean animate = PocketPrefs.animationsEnabled(this);
        if (bitmap == null) {
            if (animate) {
                frame.animate().alpha(0f).setDuration(300).start();
                under.animate().alpha(0f).setDuration(300).start();
            }
            else {
                frame.setAlpha(0f);
                under.setAlpha(0f);
            }
            return;
        }
        ImageView incoming = keyArtOnA ? b : a;
        ImageView outgoing = keyArtOnA ? a : b;
        keyArtOnA = !keyArtOnA;
        incoming.setImageBitmap(bitmap);
        if (PocketPrefs.colorBackgroundArt(this)) {
            incoming.clearColorFilter();
        }
        else {
            ColorMatrix mono = new ColorMatrix();
            mono.setSaturation(0f);
            incoming.setColorFilter(new ColorMatrixColorFilter(mono));
        }
        incoming.animate().cancel();
        outgoing.animate().cancel();
        if (animate) {
            // A soft cross-fade with a slow settle in size (like a console's home screen)
            incoming.setPivotY(0f);
            incoming.setScaleX(1.04f);
            incoming.setScaleY(1.04f);
            incoming.setAlpha(0f);
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(700)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            outgoing.animate().alpha(0f).setDuration(380).start();
            frame.animate().alpha(1f).setDuration(380).start();
            under.animate().alpha(1f).setDuration(380).start();
        }
        else {
            incoming.setAlpha(1f);
            outgoing.setAlpha(0f);
            frame.setAlpha(1f);
            under.setAlpha(1f);
        }
    }

    /** True for a logo too dark to read on the black backdrop. */
    private boolean pocketDarkLogo(Bitmap logo) {
        Boolean known = pocketDarkLogos.get(logo);
        if (known != null) {
            return known;
        }
        boolean dark = false;
        try {
            Bitmap small = Bitmap.createScaledBitmap(logo, 48, Math.max(1, 48 * logo.getHeight() / Math.max(1, logo.getWidth())), true);
            double sum = 0;
            int n = 0;
            for (int y = 0; y < small.getHeight(); y++) {
                for (int x = 0; x < small.getWidth(); x++) {
                    int c = small.getPixel(x, y);
                    if (android.graphics.Color.alpha(c) > 160) {
                        sum += (0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c)
                                + 0.114 * android.graphics.Color.blue(c)) / 255.0;
                        n++;
                    }
                }
            }
            dark = n == 0 || sum / n < 0.22;
            if (small != logo) {
                small.recycle();
            }
        } catch (RuntimeException ignored) {
        }
        pocketDarkLogos.put(logo, dark);
        return dark;
    }

    private void showLogo(Bitmap logo) {
        ImageView view = findViewById(R.id.pocketHeroLogo);
        TextView title = findViewById(R.id.pocketHeroTitle);
        if (view == null || title == null) {
            return;
        }
        if (logo != null && (!com.limelight.pocket.PocketArt.logosForNames(this) || pocketDarkLogo(logo))) {
            logo = null;
        }
        com.limelight.pocket.ui.LaunchOverlay.rememberLogo(heroTitleForLaunch, logo);
        if (logo == logoShown && (logo != null) == (view.getVisibility() == View.VISIBLE)) {
            return;
        }
        logoShown = logo;
        if (logo == null) {
            view.setVisibility(View.GONE);
            view.setImageDrawable(null);
            title.setVisibility(View.VISIBLE);
            return;
        }
        view.setImageBitmap(logo);
        view.setContentDescription(title.getText());
        title.setVisibility(View.GONE);
        view.setVisibility(View.VISIBLE);
        if (PocketPrefs.animationsEnabled(this)) {
            view.setAlpha(0f);
            view.setTranslationX(12 * getResources().getDisplayMetrics().density);
            view.animate().alpha(1f).translationX(0).setDuration(420)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
        }
    }

    private void showHeroArt(Bitmap bitmap) {
        if (bitmap == null || bitmap == heroBitmap || bitmap.isRecycled()) {
            return;
        }
        // The art also becomes the backdrop of the "Starting..." screen
        if (heroTitleForLaunch != null) {
            com.limelight.pocket.ui.LaunchOverlay.rememberArt(heroTitleForLaunch, bitmap);
        }
        // The screen's light takes on the game's colour
        com.limelight.pocket.ui.HeroArt.ambient(getWindow().getDecorView(), bitmap);
        if (!PocketPrefs.BACKDROP_ART.equals(PocketPrefs.backdrop(this))) {
            heroBitmap = bitmap;
            return;
        }
        if (com.limelight.pocket.ui.HeroArt.isPlaceholder(bitmap)) {
            // A plain default icon (like Desktop's) blown up behind the screen is just a grey
            // smudge: let the lights show instead
            heroBitmap = bitmap;
            ImageView pa = findViewById(R.id.pocketHeroArtA);
            ImageView pb = findViewById(R.id.pocketHeroArtB);
            if (pa != null && pb != null) {
                pa.animate().alpha(0f).setDuration(300).start();
                pb.animate().alpha(0f).setDuration(300).start();
            }
            return;
        }
        heroBitmap = bitmap;
        ImageView a = findViewById(R.id.pocketHeroArtA);
        ImageView b = findViewById(R.id.pocketHeroArtB);
        if (a == null || b == null) {
            return;
        }
        ImageView incoming = heroArtOnA ? b : a;
        ImageView outgoing = heroArtOnA ? a : b;
        heroArtOnA = !heroArtOnA;

        incoming.setImageBitmap(bitmap);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            incoming.setRenderEffect(RenderEffect.createBlurEffect(42f, 42f, Shader.TileMode.CLAMP));
        }
        // Mono glass theme: the backdrop art is shown in black and white unless asked otherwise
        if (PocketPrefs.colorBackgroundArt(this)) {
            incoming.clearColorFilter();
        }
        else {
            ColorMatrix mono = new ColorMatrix();
            mono.setSaturation(0f);
            incoming.setColorFilter(new ColorMatrixColorFilter(mono));
        }
        boolean animate = PocketPrefs.animationsEnabled(this);
        float targetAlpha = PocketPrefs.colorBackgroundArt(this) ? 0.58f : 0.42f;
        incoming.animate().cancel();
        outgoing.animate().cancel();
        incoming.setScaleX(1.04f);
        incoming.setScaleY(1.04f);
        if (animate) {
            incoming.animate().alpha(targetAlpha).setDuration(260).setInterpolator(PocketUi.EASE).start();
            outgoing.animate().alpha(0f).setDuration(260).start();
        }
        else {
            incoming.setAlpha(targetAlpha);
            outgoing.setAlpha(0f);
        }
    }

    /** A very slow drift-zoom on the backdrop, like a living wallpaper. */
    private void startKenBurns(ImageView view) {
        if (isFinishing()) {
            return;
        }
        if (kenBurns != null) {
            kenBurns.cancel();
        }
        kenBurns = ObjectAnimator.ofPropertyValuesHolder(view,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X, 1.02f, 1.12f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.02f, 1.12f),
                android.animation.PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0f, -24f));
        kenBurns.setDuration(20000);
        kenBurns.setRepeatMode(ValueAnimator.REVERSE);
        kenBurns.setRepeatCount(ValueAnimator.INFINITE);
        kenBurns.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        kenBurns.start();
    }

    @Override
    protected boolean onButtonX() {
        if (lastRunningAppId == 0 || computer == null || managerBinder == null) {
            return false;
        }
        UiHelper.displayQuitConfirmationDialog(this, () -> {
            suspendGridUpdates = true;
            ServerHelper.doQuit(AppView.this, computer,
                    new NvApp("app", null, lastRunningAppId, false), managerBinder, () -> {
                        suspendGridUpdates = false;
                        if (poller != null) {
                            poller.pollNow();
                        }
                    });
        }, null);
        return true;
    }

    /** First letter of a game's name, for jumping through an A to Z library. */
    private char pocketInitial(int position) {
        String name = ((AppObject) appGridAdapter.getItem(position)).app.getAppName();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return Character.isDigit(c) ? '#' : Character.toUpperCase(c);
            }
        }
        return '#';
    }

    /** L2 / R2: the next / previous letter (A to Z order), or the first / last game otherwise. */
    @Override
    protected boolean onTrigger(int direction) {
        if (appCarousel == null || appGridAdapter == null || appGridAdapter.getCount() == 0) {
            return false;
        }
        int count = appGridAdapter.getCount();
        int current = carouselAdapter != null ? Math.max(0, carouselAdapter.getFocusedPosition()) : 0;
        int target;
        boolean letters = PocketPrefs.SORT_AZ.equals(PocketPrefs.sortMode(this));
        if (letters) {
            char letter = pocketInitial(current);
            int i = current;
            if (direction > 0) {
                while (i < count && pocketInitial(i) == letter) {
                    i++;
                }
                target = Math.min(i, count - 1);
            }
            else {
                while (i > 0 && pocketInitial(i - 1) == letter) {
                    i--;
                }
                if (i == current && i > 0) {
                    char previous = pocketInitial(i - 1);
                    i--;
                    while (i > 0 && pocketInitial(i - 1) == previous) {
                        i--;
                    }
                }
                target = i;
            }
        }
        else {
            target = direction > 0 ? count - 1 : 0;
        }
        if (target == current) {
            return true;
        }
        final int pos = target;
        appCarousel.scrollToPosition(pos);
        appCarousel.post(() -> {
            RecyclerView.ViewHolder holder = appCarousel.findViewHolderForAdapterPosition(pos);
            if (holder != null) {
                holder.itemView.requestFocus();
            }
        });
        if (letters) {
            pocketShowLetter(pocketInitial(pos));
        }
        return true;
    }

    private TextView pocketLetter;

    /** A big letter in a glass disc, for a moment, while jumping through the alphabet. */
    private void pocketShowLetter(char letter) {
        ViewGroup content = findViewById(android.R.id.content);
        if (content == null) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        if (pocketLetter == null) {
            pocketLetter = new TextView(this);
            pocketLetter.setGravity(android.view.Gravity.CENTER);
            pocketLetter.setTextColor(0xFFFFFFFF);
            pocketLetter.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 38);
            pocketLetter.setTypeface(android.graphics.Typeface.create("sans-serif-light", android.graphics.Typeface.NORMAL));
            pocketLetter.setBackgroundResource(R.drawable.pocket_letter_bg);
            pocketLetter.setAlpha(0f);
            int size = Math.round(96 * d);
            content.addView(pocketLetter, new android.widget.FrameLayout.LayoutParams(size, size, android.view.Gravity.CENTER));
        }
        pocketLetter.setText(String.valueOf(letter));
        pocketLetter.animate().cancel();
        pocketLetter.setScaleX(0.92f);
        pocketLetter.setScaleY(0.92f);
        pocketLetter.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(160)
                .setInterpolator(com.limelight.pocket.ui.PocketMotion.DECELERATE)
                .withEndAction(() -> pocketLetter.animate().alpha(0f).setStartDelay(520).setDuration(260)
                        .withEndAction(null).start())
                .start();
    }

    @Override
    protected boolean onShoulder(int direction) {
        if (appCarousel == null || appGridAdapter == null || appGridAdapter.getCount() == 0) {
            return false;
        }
        int current = carouselAdapter != null ? carouselAdapter.getFocusedPosition() : 0;
        if (current < 0) {
            current = 0;
        }
        final int target = Math.max(0, Math.min(appGridAdapter.getCount() - 1, current + direction * 5));
        appCarousel.scrollToPosition(target);
        appCarousel.post(() -> {
            RecyclerView.ViewHolder holder = appCarousel.findViewHolderForAdapterPosition(target);
            if (holder != null) {
                holder.itemView.requestFocus();
            }
        });
        return true;
    }

    public static class AppObject {
        public final NvApp app;
        public boolean isRunning;
        public boolean isHidden;

        public AppObject(NvApp app) {
            if (app == null) {
                throw new IllegalArgumentException("app must not be null");
            }
            this.app = app;
        }

        @Override
        public String toString() {
            return app.getAppName();
        }
    }
}
