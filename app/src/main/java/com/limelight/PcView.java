package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.net.UnknownHostException;

import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.crypto.AndroidCryptoProvider;
import com.limelight.computers.ComputerManagerListener;
import com.limelight.computers.ComputerManagerService;
import com.limelight.grid.PcGridAdapter;
import com.limelight.grid.assets.DiskAssetLoader;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvApp;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;
import com.limelight.nvstream.wol.WakeOnLanSender;
import com.limelight.preferences.AddComputerManually;
import com.limelight.preferences.GlPreferences;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.StreamSettings;
import com.limelight.profiles.ProfilesManager;
import com.limelight.pocket.ConsoleActivity;
import com.limelight.pocket.PocketPrefs;
import com.limelight.pocket.UsbLink;
import com.limelight.pocket.UsbBridge;
import com.limelight.pocket.ui.CarouselAdapter;
import com.limelight.pocket.ui.PocketUi;
import com.limelight.utils.Dialog;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.ServerHelper;
import com.limelight.utils.ShortcutHelper;
import com.limelight.utils.UiHelper;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.view.KeyEvent;
import android.view.ViewGroup;
import android.widget.TextView;
import android.provider.Settings;
import android.text.InputFilter;
import android.text.InputType;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.View.OnClickListener;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.Toast;
import android.widget.AdapterView.AdapterContextMenuInfo;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.preference.PreferenceManager;

import org.xmlpull.v1.XmlPullParserException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class PcView extends ConsoleActivity implements CarouselAdapter.Listener,
        com.limelight.pocket.ui.QuickResumePanel.Source {
    private View noPcFoundLayout;
    private RecyclerView pcCarousel;
    private CarouselAdapter carouselAdapter;
    private int contextPosition = -1;

    // USB-C link state
    private static final int USB_IDLE = 0;
    private static final int USB_SEARCHING = 1;
    private static final int USB_COUNTDOWN = 2;
    private static final int USB_NOT_FOUND = 3;
    private static final int USB_NEEDS_PAIR = 4;
    private static final int USB_NEEDS_TETHER = 5;
    private static boolean splashShownThisProcess;
    private static boolean autoOpenedLastPc;
    private boolean tetherPromptDismissed;
    private boolean cableWasConnected;
    private int cableTicks;
    private boolean carouselAnimated;
    private String latencyText;
    private String latencyForUuid;
    private boolean latencyProbeRunning;
    private final Handler usbHandler = new Handler(Looper.getMainLooper());
    private boolean usbLinkWasUp;
    private int usbState = USB_IDLE;
    private int usbCountdown;
    private Thread usbThread;
    private ComputerDetails usbComputer;
    private ObjectAnimator usbPulse;
    private PcGridAdapter pcGridAdapter;
    private ShortcutHelper shortcutHelper;
    private ComputerManagerService.ComputerManagerBinder managerBinder;
    private boolean freezeUpdates, runningPolling, inForeground, completeOnCreateCalled;
    private ComputerDetails.AddressTuple pendingPairingAddress;
    private String pendingPairingPin, pendingPairingPassphrase;
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

                    // Now make the binder visible
                    managerBinder = localBinder;

                    // Start updates
                    startComputerUpdates();

                    // Force a keypair to be generated early to avoid discovery delays
                    new AndroidCryptoProvider(PcView.this).getClientCertificate();
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

        // Only reinitialize views if completeOnCreate() was called
        // before this callback. If it was not, completeOnCreate() will
        // handle initializing views with the config change accounted for.
        // This is not prone to races because both callbacks are invoked
        // in the main thread.
        if (completeOnCreateCalled) {
            // Reinitialize views just in case orientation changed
            initializeViews();
        }

        refreshProfileButton();
    }

    private final static int PAIR_ID = 2;
    private final static int UNPAIR_ID = 3;
    private final static int WOL_ID = 4;
    private final static int DELETE_ID = 5;
    private final static int RESUME_ID = 6;
    private final static int QUIT_ID = 7;
    private final static int VIEW_DETAILS_ID = 8;
    private final static int FULL_APP_LIST_ID = 9;
    private final static int TEST_NETWORK_ID = 10;
    private final static int GAMESTREAM_EOL_ID = 11;
    private final static int OPEN_MANAGEMENT_PAGE_ID = 20;
    private final static int POCKET_POWER_MENU_ID = 99;
    private final static int POCKET_POWER_BASE_ID = 100;
    private final static int POCKET_COMPANION_PIN_ID = 130;
    private final static int PAIR_ID_OTP = 21;

    private void initializeViews() {
        setContentView(R.layout.activity_pc_view);

        UiHelper.notifyNewRootView(this);

        // Allow floating expanded PiP overlays while browsing PCs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setShouldDockBigOverlays(false);
        }

        // Set default preferences if we've never been run
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

        // Set the correct layout for the PC grid
        pcGridAdapter.updateLayoutWithPreferences(this, PreferenceConfiguration.readPreferences(this));

        // Console carousel of PCs
        pcCarousel = findViewById(R.id.pocketPcCarousel);
        pcCarousel.setLayoutManager(new com.limelight.pocket.ui.AnchoredLayoutManager(this));
        pcCarousel.setItemAnimator(null);
        carouselAdapter = new CarouselAdapter(pcGridAdapter, this, 20);
        pcCarousel.setAdapter(carouselAdapter);

        // "Jump back in": the games played last on the focused PC, right after the PCs
        pocketRecentCarousel = findViewById(R.id.pocketRecentCarousel);
        if (pocketRecentCarousel != null) {
            pocketRecentCarousel.setLayoutManager(new com.limelight.pocket.ui.AnchoredLayoutManager(this));
            pocketRecentCarousel.setItemAnimator(null);
            if (pocketRecentCarousel instanceof com.limelight.pocket.ui.CarouselRow) {
                ((com.limelight.pocket.ui.CarouselRow) pocketRecentCarousel).setStartDivider(true);
            }
            pocketRecentAdapter = new com.limelight.pocket.ui.RecentGamesAdapter(this);
            pocketRecentCarousel.setAdapter(new CarouselAdapter(pocketRecentAdapter, pocketRecentListener, 14));
        }

        setHints(hintA(this, R.string.pocket_hint_open),
                hintY(this, R.string.pocket_hint_options),
                hintX(this, R.string.pocket_hint_add_pc),
                hintStart(this, R.string.pocket_hint_settings));

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = findViewById(R.id.helpButton);
        TextView profilesButton = findViewById(R.id.profilesButton);

        settingsButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                openQuickSettings();
            }
        });
        addComputerButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(PcView.this, AddComputerManually.class);
                startActivity(i);
            }
        });
        helpButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                com.limelight.pocket.QuickSettingsActivity.showUsbHelp(PcView.this);
            }
        });
        profilesButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PcView.this, ProfilesActivity.class));
            }
        });

        // Amazon review didn't like the help button because the wiki was not entirely
        // navigable via the Fire TV remote (though the relevant parts were). Let's hide
        // it on Fire TV.
        if (getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            helpButton.setVisibility(View.GONE);
        }

        noPcFoundLayout = findViewById(R.id.no_pc_found_layout);
        if (pcGridAdapter.getCount() == 0) {
            noPcFoundLayout.setVisibility(View.VISIBLE);
        }
        else {
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }
        pcGridAdapter.notifyDataSetChanged();
        updateHero(carouselAdapter.getFocusedPosition());
        renderUsbOverlay();
        pcCarousel.post(this::focusFirstPcIfNeeded);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Assume we're in the foreground when created to avoid a race
        // between binding to CMS and onResume()
        inForeground = true;

        // Create a GLSurfaceView to fetch GLRenderer unless we have
        // a cached result already.
        final GlPreferences glPrefs = GlPreferences.readPreferences(this);
        if (!glPrefs.savedFingerprint.equals(Build.FINGERPRINT) || glPrefs.glRenderer.isEmpty()) {
            GLSurfaceView surfaceView = new GLSurfaceView(this);
            surfaceView.setRenderer(new GLSurfaceView.Renderer() {
                @Override
                public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
                    // Save the GLRenderer string so we don't need to do this next time
                    glPrefs.glRenderer = gl10.glGetString(GL10.GL_RENDERER);
                    glPrefs.savedFingerprint = Build.FINGERPRINT;
                    glPrefs.writePreferences();

                    LimeLog.info("Fetched GL Renderer: " + glPrefs.glRenderer);

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            completeOnCreate();
                        }
                    });
                }

                @Override
                public void onSurfaceChanged(GL10 gl10, int i, int i1) {
                }

                @Override
                public void onDrawFrame(GL10 gl10) {
                }
            });
            setContentView(surfaceView);
        }
        else {
            LimeLog.info("Cached GL Renderer: " + glPrefs.glRenderer);
            completeOnCreate();
        }

        Intent intent = getIntent();

        String hostname = intent.getStringExtra("hostname");
        int port = intent.getIntExtra("port", NvHTTP.DEFAULT_HTTP_PORT);
        pendingPairingPin = intent.getStringExtra("pin");
        pendingPairingPassphrase = intent.getStringExtra("passphrase");

        if (hostname != null && pendingPairingPin != null && pendingPairingPassphrase != null) {
            pendingPairingAddress = new ComputerDetails.AddressTuple(hostname, port);
        } else {
            pendingPairingPin = null;
            pendingPairingPassphrase = null;
        }
    }

    private void completeOnCreate() {
        completeOnCreateCalled = true;

        shortcutHelper = new ShortcutHelper(this);

        UiHelper.setLocale(this);

        // Bind to the computer manager service
        bindService(new Intent(PcView.this, ComputerManagerService.class), serviceConnection,
                Service.BIND_AUTO_CREATE);

        pcGridAdapter = new PcGridAdapter(this, PreferenceConfiguration.readPreferences(this));

        initializeViews();
    }

    private void startComputerUpdates() {
        // Only allow polling to start if we're bound to CMS, polling is not already running,
        // and our activity is in the foreground.
        if (managerBinder != null && !runningPolling && inForeground) {
            freezeUpdates = false;
            managerBinder.startPolling(new ComputerManagerListener() {
                @Override
                public void notifyComputerUpdated(final ComputerDetails details) {
                    if (!freezeUpdates) {
                        PcView.this.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                updateComputer(details);
                            }
                        });

                        // Add a launcher shortcut for this PC (off the main thread to prevent ANRs)
                        if (details.pairState == PairState.PAIRED) {
                            shortcutHelper.createAppViewShortcutForOnlineHost(details);
//                        } else
                        }
                            if (pendingPairingAddress != null) {
                            if (
                                details.state == ComputerDetails.State.ONLINE &&
                                details.activeAddress.equals(pendingPairingAddress)
                            ) {
                                PcView.this.runOnUiThread(() -> {
                                    doPair(details, pendingPairingPin, pendingPairingPassphrase);
                                    pendingPairingAddress = null;
                                    pendingPairingPin = null;
                                    pendingPairingPassphrase = null;
                                });
                            }
                        }
                    }
                }
            });
            runningPolling = true;
        }
    }

    private void stopComputerUpdates(boolean wait) {
        if (managerBinder != null) {
            if (!runningPolling) {
                return;
            }

            freezeUpdates = true;

            managerBinder.stopPolling();

            if (wait) {
                managerBinder.waitForPollingStopped();
            }

            runningPolling = false;
        }
    }

    private void refreshProfileButton() {
        // A round button like its neighbours; a pill with the name when a profile is active
        styleProfileButton(findViewById(R.id.profilesButton), ProfilesManager.getInstance().getActiveName());
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (managerBinder != null) {
            unbindService(serviceConnection);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        // Display a decoder crash notification if we've returned after a crash
        UiHelper.showDecoderCrashDialog(this);

        refreshProfileButton();

        inForeground = true;
        startComputerUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();

        inForeground = false;
        stopComputerUpdates(false);
        if (usbState != USB_IDLE) {
            // Interrupted (not finished or cancelled): try again when we're back
            usbLinkWasUp = false;
        }
        cancelUsbFlow(false);
    }

    @Override
    protected void onStop() {
        super.onStop();

        Dialog.closeDialogs();
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        stopComputerUpdates(false);

        // Call superclass
        super.onCreateContextMenu(menu, v, menuInfo);

        int position = contextPosition;
        if (menuInfo instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) menuInfo).position;
        }
        if (position < 0 || position >= pcGridAdapter.getCount()) {
            return;
        }
        contextPosition = position;
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);

        // Add a header with PC status details
        menu.clearHeader();
        String headerTitle = computer.details.name + " - ";
        switch (computer.details.state)
        {
            case ONLINE:
                headerTitle += getResources().getString(R.string.pcview_menu_header_online);
                break;
            case OFFLINE:
                menu.setHeaderIcon(R.drawable.ic_pc_offline);
                headerTitle += getResources().getString(R.string.pcview_menu_header_offline);
                break;
            case UNKNOWN:
                headerTitle += getResources().getString(R.string.pcview_menu_header_unknown);
                break;
        }

        menu.setHeaderTitle(headerTitle);

        // Inflate the context menu
        if (computer.details.state == ComputerDetails.State.OFFLINE ||
            computer.details.state == ComputerDetails.State.UNKNOWN) {
            menu.add(Menu.NONE, WOL_ID, 1, getResources().getString(R.string.pcview_menu_send_wol));
            menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 2, getResources().getString(R.string.pcview_menu_eol));
        }
        else if (computer.details.pairState != PairState.PAIRED) {
            menu.add(Menu.NONE, PAIR_ID_OTP, 1, getResources().getString(R.string.pcview_menu_pair_pc_otp));
            menu.add(Menu.NONE, PAIR_ID, 2, getResources().getString(R.string.pcview_menu_pair_pc));
            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }
        }
        else {
            if (computer.details.runningGameId != 0) {
                menu.add(Menu.NONE, RESUME_ID, 1, getResources().getString(R.string.applist_menu_resume));
                menu.add(Menu.NONE, QUIT_ID, 2, getResources().getString(R.string.applist_menu_quit));
            }

            if (computer.details.nvidiaServer) {
                menu.add(Menu.NONE, GAMESTREAM_EOL_ID, 3, getResources().getString(R.string.pcview_menu_eol));
            } else {
                menu.add(Menu.NONE, OPEN_MANAGEMENT_PAGE_ID, 3, getResources().getString(R.string.pcview_menu_open_management_page));
            }

            menu.add(Menu.NONE, FULL_APP_LIST_ID, 4, getResources().getString(R.string.pcview_menu_app_list));

            // Artemis Pocket: PC power through the Artemis Pocket PC helper
            android.view.SubMenu power = menu.addSubMenu(Menu.NONE, POCKET_POWER_MENU_ID, 4,
                    getString(R.string.pocket_sm_power));
            com.limelight.pocket.PcPower[] actions = com.limelight.pocket.PcPower.values();
            for (int i = 0; i < actions.length; i++) {
                power.add(Menu.NONE, POCKET_POWER_BASE_ID + i, i, actions[i].label(this));
            }
            menu.add(Menu.NONE, POCKET_COMPANION_PIN_ID, 4, getString(R.string.pocket_companion_pin_menu));
        }

        menu.add(Menu.NONE, TEST_NETWORK_ID, 5, getResources().getString(R.string.pcview_menu_test_network));
        menu.add(Menu.NONE, DELETE_ID, 6, getResources().getString(R.string.pcview_menu_delete_pc));
        menu.add(Menu.NONE, VIEW_DETAILS_ID, 7,  getResources().getString(R.string.pcview_menu_details));
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        // For some reason, this gets called again _after_ onPause() is called on this activity.
        // startComputerUpdates() manages this and won't actual start polling until the activity
        // returns to the foreground.
        startComputerUpdates();
    }

    private void doPair(final ComputerDetails computer, String otp, String passphrase) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.pair_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.pairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                boolean success = false;
                try {
                    // Stop updates and wait while pairing
                    stopComputerUpdates(true);

                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        // Don't display any toast, but open the app list
                        message = null;
                        success = true;
                    }
                    else {
                        String pinStr = otp;
                        if (pinStr == null) {
                            pinStr = PairingManager.generatePinString();
                        }

                        // Spin the dialog off in a thread because it blocks
                        if (passphrase == null) {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_pairing_msg)+" "+pinStr+"\n\n"+
                                            getResources().getString(R.string.pair_pairing_help), false);
                        } else {
                            Dialog.displayDialog(PcView.this, getResources().getString(R.string.pair_pairing_title),
                                    getResources().getString(R.string.pair_otp_pairing_msg)+"\n\n"+
                                            getResources().getString(R.string.pair_otp_pairing_help), false);
                        }

                        PairingManager pm = httpConn.getPairingManager();

                        PairState pairState = pm.pair(httpConn.getServerInfo(true), pinStr, passphrase);
                        if (pairState == PairState.PIN_WRONG) {
                            message = getResources().getString(R.string.pair_incorrect_pin);
                        }
                        else if (pairState == PairState.FAILED) {
                            if (computer.runningGameId != 0) {
                                message = getResources().getString(R.string.pair_pc_ingame);
                            }
                            else {
                                message = getResources().getString(R.string.pair_fail);
                            }
                        }
                        else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getResources().getString(R.string.pair_already_in_progress);
                        }
                        else if (pairState == PairState.PAIRED) {
                            // Just navigate to the app view without displaying a toast
                            message = null;
                            success = true;

                            // Pin this certificate for later HTTPS use
                            managerBinder.getComputer(computer.uuid).serverCert = pm.getPairedCert();

                            // Invalidate reachability information after pairing to force
                            // a refresh before reading pair state again
                            managerBinder.invalidateStateForComputer(computer.uuid);
                        }
                        else {
                            // Should be no other values
                            message = null;
                        }
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    e.printStackTrace();
                    message = e.getMessage();
                }

                Dialog.closeDialogs();

                final String toastMessage = message;
                final boolean toastSuccess = success;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (toastMessage != null) {
                            Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                        }

                        if (toastSuccess) {
                            // Open the app list after a successful pairing attempt
                            doAppList(computer, true, false);
                        }
                        else {
                            // Start polling again if we're still in the foreground
                            startComputerUpdates();
                        }
                    }
                });
            }
        }).start();
    }

    private void doOTPPair(final ComputerDetails computer) {
        Context context = PcView.this;

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 40);

        final EditText otpInput = new EditText(context);
        otpInput.setHint("PIN");
        otpInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        otpInput.setFilters(new InputFilter[] { new InputFilter.LengthFilter(4) });

        final EditText passphraseInput = new EditText(context);
        passphraseInput.setHint(getString(R.string.pair_passphrase_hint));
        passphraseInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);

        layout.addView(otpInput);
        layout.addView(passphraseInput);

        AlertDialog.Builder dialogBuilder = new AlertDialog.Builder(context);
        dialogBuilder.setTitle(R.string.pcview_menu_pair_pc_otp);
        dialogBuilder.setView(layout);

        dialogBuilder.setPositiveButton(getString(R.string.proceed), null);

        dialogBuilder.setNegativeButton(getString(R.string.cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog dialog = dialogBuilder.create();
        dialog.show();

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String pin = otpInput.getText().toString();
            String passphrase = passphraseInput.getText().toString();
            if (pin.length() != 4) {
                Toast.makeText(context, getString(R.string.pair_pin_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            if (passphrase.length() < 4 ) {
                Toast.makeText(context, getString(R.string.pair_passphrase_length_msg), Toast.LENGTH_SHORT).show();
                return;
            }
            doPair(computer, pin, passphrase);
            dialog.dismiss(); // Manually dismiss the dialog if the input is valid
        });
    }

    private void doWakeOnLan(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.ONLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_pc_online), Toast.LENGTH_SHORT).show();
            return;
        }

        if (computer.macAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.wol_no_mac), Toast.LENGTH_SHORT).show();
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                String message;
                try {
                    WakeOnLanSender.sendWolPacket(computer);
                    message = getResources().getString(R.string.wol_waking_msg);
                } catch (IOException e) {
                    message = getResources().getString(R.string.wol_fail);
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doUnpair(final ComputerDetails computer) {
        if (computer.state == ComputerDetails.State.OFFLINE || computer.activeAddress == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Toast.makeText(PcView.this, getResources().getString(R.string.unpairing), Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                NvHTTP httpConn;
                String message;
                try {
                    httpConn = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer),
                            computer.httpsPort, managerBinder.getUniqueId(), computer.serverCert,
                            PlatformBinding.getCryptoProvider(PcView.this));
                    if (httpConn.getPairState() == PairState.PAIRED) {
                        httpConn.unpair();
                        if (httpConn.getPairState() == PairState.NOT_PAIRED) {
                            message = getResources().getString(R.string.unpair_success);
                        }
                        else {
                            message = getResources().getString(R.string.unpair_fail);
                        }
                    }
                    else {
                        message = getResources().getString(R.string.unpair_error);
                    }
                } catch (UnknownHostException e) {
                    message = getResources().getString(R.string.error_unknown_host);
                } catch (FileNotFoundException e) {
                    message = getResources().getString(R.string.error_404);
                } catch (XmlPullParserException | IOException e) {
                    message = e.getMessage();
                    e.printStackTrace();
                }

                final String toastMessage = message;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(PcView.this, toastMessage, Toast.LENGTH_LONG).show();
                    }
                });
            }
        }).start();
    }

    private void doAppList(ComputerDetails computer, boolean newlyPaired, boolean showHiddenGames) {
        if (computer.state == ComputerDetails.State.OFFLINE) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_pc_offline), Toast.LENGTH_SHORT).show();
            return;
        }
        if (managerBinder == null) {
            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
            return;
        }

        Intent i = new Intent(this, AppView.class);
        i.putExtra(AppView.NAME_EXTRA, computer.name);
        i.putExtra(AppView.UUID_EXTRA, computer.uuid);
        i.putExtra(AppView.NEW_PAIR_EXTRA, newlyPaired);
        i.putExtra(AppView.SHOW_HIDDEN_APPS_EXTRA, showHiddenGames);
        startActivity(i);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        int position = contextPosition;
        if (item.getMenuInfo() instanceof AdapterContextMenuInfo) {
            position = ((AdapterContextMenuInfo) item.getMenuInfo()).position;
        }
        if (position < 0 || position >= pcGridAdapter.getCount()) {
            return super.onContextItemSelected(item);
        }
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
        if (pocketHandleCompanionItem(item.getItemId(), computer.details)) {
            return true;
        }
        switch (item.getItemId()) {
            case PAIR_ID:
                doPair(computer.details, null, null);
                return true;

            case PAIR_ID_OTP:
                doOTPPair(computer.details);
                return true;

            case UNPAIR_ID:
                doUnpair(computer.details);
                return true;

            case WOL_ID:
                doWakeOnLan(computer.details);
                return true;

            case DELETE_ID:
                if (ActivityManager.isUserAMonkey()) {
                    LimeLog.info("Ignoring delete PC request from monkey");
                    return true;
                }
                UiHelper.displayDeletePcConfirmationDialog(this, computer.details, new Runnable() {
                    @Override
                    public void run() {
                        if (managerBinder == null) {
                            Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                            return;
                        }
                        removeComputer(computer.details);
                    }
                }, null);
                return true;

            case FULL_APP_LIST_ID:
                doAppList(computer.details, false, true);
                return true;

            case RESUME_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Ask for the virtual display too: if the PC's session ended meanwhile this becomes a
                // fresh launch, which would otherwise mirror the main screen
                ServerHelper.doStart(this, new NvApp("app", null, computer.details.runningGameId, false), computer.details, managerBinder,
                        PreferenceConfiguration.readPreferences(this).useVirtualDisplay);
                return true;

            case QUIT_ID:
                if (managerBinder == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.error_manager_not_running), Toast.LENGTH_LONG).show();
                    return true;
                }

                // Display a confirmation dialog first
                UiHelper.displayQuitConfirmationDialog(this, new Runnable() {
                    @Override
                    public void run() {
                        ServerHelper.doQuit(PcView.this, computer.details,
                                new NvApp("app", null, 0, false), managerBinder, null);
                    }
                }, null);
                return true;

            case VIEW_DETAILS_ID:
                Dialog.displayDialog(PcView.this, getResources().getString(R.string.title_details), computer.details.toString(), false);
                return true;

            case TEST_NETWORK_ID:
                ServerHelper.doNetworkTest(PcView.this);
                return true;

            case GAMESTREAM_EOL_ID:
                HelpLauncher.launchGameStreamEolFaq(PcView.this);
                return true;

            case OPEN_MANAGEMENT_PAGE_ID:
                String managementUrl = computer.guessManagementUrl();
                if (managementUrl == null) {
                    Toast.makeText(PcView.this, getResources().getString(R.string.pcview_error_no_management_url), Toast.LENGTH_LONG).show();
                } else {
                    HelpLauncher.launchUrl(PcView.this, managementUrl);
                }

            default:
                return super.onContextItemSelected(item);
        }
    }

    // ---- Artemis Pocket: PC power + helper pairing -----------------------------------------

    private String pocketCompanionHost(ComputerDetails details) {
        String usb = UsbLink.getUsbAddressFor(this, details.uuid);
        if (usb != null) {
            return usb;
        }
        return details.activeAddress != null ? details.activeAddress.address : null;
    }

    private boolean pocketHandleCompanionItem(int id, final ComputerDetails details) {
        final com.limelight.pocket.PcPower[] actions = com.limelight.pocket.PcPower.values();
        if (id >= POCKET_POWER_BASE_ID && id < POCKET_POWER_BASE_ID + actions.length) {
            final com.limelight.pocket.PcPower action = actions[id - POCKET_POWER_BASE_ID];
            Runnable run = () -> com.limelight.pocket.PcCompanion.runAsync(this, pocketCompanionHost(details),
                    details.uuid, "POWER " + action.command, reply -> {
                        int msg;
                        if (reply == null) {
                            msg = R.string.pocket_companion_missing;
                        }
                        else if (reply.startsWith("ERR auth")) {
                            msg = R.string.pocket_companion_not_paired;
                        }
                        else if (reply.startsWith("OK")) {
                            msg = R.string.pocket_power_sent;
                        }
                        else {
                            msg = R.string.pocket_power_failed;
                        }
                        Toast.makeText(PcView.this, msg, Toast.LENGTH_LONG).show();
                    });
            if (action.endsStream) {
                new android.app.AlertDialog.Builder(this)
                        .setTitle(action.label(this))
                        .setMessage(getString(R.string.pocket_power_confirm, details.name))
                        .setPositiveButton(action.label(this), (d, w) -> run.run())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
            else {
                run.run();
            }
            return true;
        }
        if (id == POCKET_COMPANION_PIN_ID) {
            final android.widget.EditText pin = new android.widget.EditText(this);
            pin.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
            pin.setHint("000000");
            pin.setTextSize(22);
            pin.setGravity(android.view.Gravity.CENTER);
            new android.app.AlertDialog.Builder(this)
                    .setTitle(R.string.pocket_companion_pin_title)
                    .setMessage(R.string.pocket_companion_pin_body)
                    .setView(pin)
                    .setPositiveButton(android.R.string.ok, (d, w) -> com.limelight.pocket.PcCompanion.runAsync(this,
                            pocketCompanionHost(details), details.uuid, "PAIR " + pin.getText().toString().trim(), reply -> {
                                int msg;
                                if (reply == null) {
                                    msg = R.string.pocket_companion_missing;
                                }
                                else if (reply.startsWith("TOKEN")) {
                                    msg = R.string.pocket_companion_linked;
                                }
                                else if (reply.startsWith("ERR locked")) {
                                    msg = R.string.pocket_companion_locked;
                                }
                                else {
                                    msg = R.string.pocket_companion_wrong_pin;
                                }
                                Toast.makeText(PcView.this, msg, Toast.LENGTH_LONG).show();
                            }))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return false;
    }

    private void removeComputer(ComputerDetails details) {
        managerBinder.removeComputer(details);

        new DiskAssetLoader(this).deleteAssetsForComputer(details.uuid);

        // Delete hidden games preference value
        getSharedPreferences(AppView.HIDDEN_APPS_PREF_FILENAME, MODE_PRIVATE)
                .edit()
                .remove(details.uuid)
                .apply();

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            if (details.equals(computer.details)) {
                // Disable or delete shortcuts referencing this PC
                shortcutHelper.disableComputerShortcut(details,
                        getResources().getString(R.string.scut_deleted_pc));

                pcGridAdapter.removeComputer(computer);
                pcGridAdapter.notifyDataSetChanged();

                if (pcGridAdapter.getCount() == 0) {
                    // Show the "Discovery in progress" view
                    noPcFoundLayout.setVisibility(View.VISIBLE);
                }
                updateHero(carouselAdapter != null ? carouselAdapter.getFocusedPosition() : -1);

                break;
            }
        }
    }

    private void updateComputer(ComputerDetails details) {
        ComputerObject existingEntry = null;

        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(i);

            // Check if this is the same computer
            if (details.uuid.equals(computer.details.uuid)) {
                existingEntry = computer;
                break;
            }
        }

        if (existingEntry != null) {
            // Replace the information in the existing entry
            existingEntry.details = details;
        }
        else {
            // Add a new entry
            pcGridAdapter.addComputer(new ComputerObject(details));

            // Remove the "Discovery in progress" view
            noPcFoundLayout.setVisibility(View.INVISIBLE);
        }

        // Notify the view that the data has changed
        pcGridAdapter.notifyDataSetChanged();
        updateHero(carouselAdapter != null ? carouselAdapter.getFocusedPosition() : -1);
        if (pocketWakingUuid != null && pocketWakingUuid.equals(details.uuid)
                && details.state == ComputerDetails.State.ONLINE) {
            // It woke up: straight into its library
            pocketStopWaking();
            hideBlockingCard();
            doAppList(details, false, false);
            return;
        }
        maybeOpenLastPc(details);
        if (pcCarousel != null) {
            pcCarousel.post(this::focusFirstPcIfNeeded);
        }
    }

    // ---- Console carousel ------------------------------------------------------------

    @Override
    public void onItemClicked(int pos, View itemView) {
        if (pos < 0 || pos >= pcGridAdapter.getCount()) {
            return;
        }
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
        if (computer.details.state == ComputerDetails.State.OFFLINE
                && computer.details.pairState == PairState.PAIRED && computer.details.macAddress != null) {
            // Artemis Pocket: a sleeping PC is woken up and opened as soon as it answers
            pocketWakeAndOpen(computer.details);
        }
        else if (computer.details.state == ComputerDetails.State.UNKNOWN ||
            computer.details.state == ComputerDetails.State.OFFLINE) {
            // Open the context menu if a PC is offline or refreshing
            onItemOptions(pos, itemView);
        } else if (computer.details.pairState != PairState.PAIRED) {
            // Pair an unpaired machine by default
            doPair(computer.details, null, null);
        } else {
            doAppList(computer.details, false, false);
        }
    }

    @Override
    public void onItemOptions(int pos, View itemView) {
        if (pos < 0 || pos >= pcGridAdapter.getCount() || itemView == null) {
            return;
        }
        contextPosition = pos;
        // Artemis Pocket: the options open as a console panel instead of Android's menu
        final ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(pos);
        List<com.limelight.pocket.ui.StreamMenuView.Item> extra = new ArrayList<>();
        // Quick play: straight into the last game, without opening the library
        final int lastId = PocketPrefs.getLastAppId(this, computer.details.uuid);
        final String lastName = PocketPrefs.getLastAppName(this, computer.details.uuid);
        if (computer.details.state == ComputerDetails.State.ONLINE && computer.details.pairState == PairState.PAIRED
                && lastId != 0 && lastName != null && !lastName.isEmpty() && managerBinder != null
                && (computer.details.runningGameId == 0 || computer.details.runningGameId == lastId)
                && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            boolean resume = computer.details.runningGameId == lastId;
            extra.add(com.limelight.pocket.ui.StreamMenuView.Item.action("\u25B6  " + getString(
                    resume ? R.string.pocket_quick_resume : R.string.pocket_quick_play, lastName), () -> {
                hideConsolePanel();
                com.limelight.utils.ServerHelper.doStart(this,
                        new com.limelight.nvstream.http.NvApp(lastName, null, lastId, false), computer.details,
                        managerBinder, com.limelight.preferences.PreferenceConfiguration.readPreferences(this).useVirtualDisplay);
            }));
        }
        if (computer.details.state == ComputerDetails.State.ONLINE) {
            extra.add(com.limelight.pocket.ui.StreamMenuView.Item.action(getString(R.string.pocket_nettest),
                    () -> {
                        String usb = UsbLink.getUsbAddressFor(this, computer.details.uuid);
                        if (usb == null && computer.details.activeAddress == null) {
                            return;
                        }
                        String host = usb != null ? usb : computer.details.activeAddress.address;
                        int port = usb != null ? UsbLink.HTTP_PORT : computer.details.activeAddress.port;
                        com.limelight.pocket.ConnectionTest.run(this, host, port, usb != null, computer.details.name);
                    }));
        }
        showOptionsFor(itemView, describeComputer(computer.details, false), extra);
    }

    // ---- Artemis Pocket: wake a sleeping PC and open it when it answers --------------------

    private String pocketWakingUuid;
    private ComputerDetails pocketWakingPc;
    private final Runnable pocketWakeResend = new Runnable() {
        @Override
        public void run() {
            final ComputerDetails pc = pocketWakingPc;
            if (pc == null) {
                return;
            }
            new Thread(() -> {
                try {
                    WakeOnLanSender.sendWolPacket(pc);
                } catch (IOException ignored) {
                }
            }, "WoL").start();
            getWindow().getDecorView().postDelayed(this, 8000);
        }
    };
    private final Runnable pocketWakeTimeout = () -> {
        if (pocketWakingUuid != null) {
            pocketStopWaking();
            hideBlockingCard();
            Toast.makeText(PcView.this, R.string.pocket_wake_failed, Toast.LENGTH_LONG).show();
        }
    };

    private void pocketWakeAndOpen(ComputerDetails details) {
        pocketWakingUuid = details.uuid;
        pocketWakingPc = details;
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(pocketWakeResend);
        decor.removeCallbacks(pocketWakeTimeout);
        decor.post(pocketWakeResend);
        decor.postDelayed(pocketWakeTimeout, 75000);
        showBlockingCard(getString(R.string.pocket_wake_caption), details.name,
                getString(R.string.pocket_wake_sub), 75000, this::pocketStopWaking);
    }

    private void pocketStopWaking() {
        pocketWakingUuid = null;
        pocketWakingPc = null;
        View decor = getWindow().getDecorView();
        decor.removeCallbacks(pocketWakeResend);
        decor.removeCallbacks(pocketWakeTimeout);
    }

    @Override
    public void onItemFocused(int pos, View itemView) {
        updateHero(pos);
        // First time here: what A and Y do on a PC
        scheduleTip(PocketPrefs.TIP_PCS, itemView, 20, R.string.pocket_tip_pcs_title, R.string.pocket_tip_pcs_body);
    }

    private void focusFirstPcIfNeeded() {
        if (pcCarousel == null || pcGridAdapter.getCount() == 0 || usbState != USB_IDLE) {
            return;
        }
        View focused = getCurrentFocus();
        if (focused != null && focused != pcCarousel && isDescendant(pcCarousel, focused)) {
            return;
        }
        if (focused != null && focused != getWindow().getDecorView() && !(focused instanceof RecyclerView)
                && userNavigated) {
            // The person moved to something else (e.g. a toolbar button): leave it there. Before
            // that, a button that only got focus because it came first in the layout gives way.
            return;
        }
        RecyclerView.ViewHolder holder = pcCarousel.findViewHolderForAdapterPosition(0);
        if (holder != null) {
            holder.itemView.requestFocus();
        }
    }

    private static boolean isDescendant(ViewGroup parent, View child) {
        Object p = child.getParent();
        while (p instanceof View) {
            if (p == parent) {
                return true;
            }
            p = ((View) p).getParent();
        }
        return false;
    }

    private void updateHero(int position) {
        TextView title = findViewById(R.id.pocketHeroTitle);
        TextView subtitle = findViewById(R.id.pocketHeroSubtitle);
        if (title == null || subtitle == null || pcGridAdapter == null) {
            return;
        }
        TextView play = findViewById(R.id.pocketHeroPlay);
        TextView lastLine = findViewById(R.id.pocketHeroPlaytime);
        int count = pcGridAdapter.getCount();
        if (count == 0) {
            title.setText(R.string.pocket_brand_title);
            subtitle.setText(R.string.pocket_hero_no_pc);
            if (play != null) {
                play.setVisibility(View.GONE);
            }
            if (lastLine != null) {
                lastLine.setVisibility(View.GONE);
            }
            pocketBackdropKey = null;
            pocketHeroArt().show(null);
            if (pocketKeyArt() != null) {
                pocketKeyArt().show(null);
            }
            pocketHeroLogo().clear(getString(R.string.pocket_brand_title));
            return;
        }
        if (position < 0 || position >= count) {
            position = 0;
        }
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
        pocketRefreshRecent(computer.details);
        if (pocketRecentCarousel != null && pocketRecentCarousel.hasFocus()) {
            // A game of the row has the focus: the hero is about that game, not the PC
            return;
        }
        pocketHeroShowsGame = false;
        TextView section = findViewById(R.id.pocketSectionTitle);
        if (section != null) {
            section.setText(R.string.pocket_my_pcs);
        }
        pocketHintsFor(false);
        int dir = pocketHeroPos < 0 ? 0 : Integer.signum(position - pocketHeroPos);
        if (title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dir = -dir;
        }
        boolean moved = position != pocketHeroPos;
        pocketHeroPos = position;
        com.limelight.pocket.ui.PocketMotion.swapText(title, computer.details.name, dir);
        // A PC has no logo: its name
        pocketHeroLogo().clear(computer.details.name);
        String sub = describeComputer(computer.details, true);
        if (latencyText != null && computer.details.uuid.equals(latencyForUuid)
                && computer.details.state == ComputerDetails.State.ONLINE) {
            sub += " · " + latencyText;
        }
        if (!sub.contentEquals(subtitle.getText())) {
            subtitle.setText(sub);
            // Only a new PC gets the little pop (not every ping update)
            if (moved) {
                com.limelight.pocket.ui.PocketMotion.bump(subtitle);
            }
        }

        // What A does here, as the hero's button
        if (play != null) {
            int label = pocketPrimaryLabel(computer.details);
            if (label == 0) {
                play.setVisibility(View.GONE);
            }
            else {
                if (play.getBackground() == null) {
                    android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
                    pill.setCornerRadius(100 * getResources().getDisplayMetrics().density);
                    pill.setColor(com.limelight.pocket.ui.PocketTheme.accent(this));
                    play.setBackground(pill);
                }
                String text = (label == R.string.pocket_hint_pair ? "\u25CF  " : "\u25B6  ") + getString(label);
                play.setVisibility(View.VISIBLE);
                if (!text.contentEquals(play.getText())) {
                    play.setText(text);
                    if (moved) {
                        com.limelight.pocket.ui.PocketMotion.bump(play);
                    }
                }
            }
        }

        // What it is playing / played last, and that game's colours (or key art) behind everything
        int lastId = PocketPrefs.getLastAppId(this, computer.details.uuid);
        String lastName = PocketPrefs.getLastAppName(this, computer.details.uuid);
        String runningName = pocketGameName(computer.details.uuid, computer.details.runningGameId);
        if (lastLine != null) {
            String line = "";
            if (runningName != null) {
                line = getString(R.string.pocket_hero_playing, runningName);
            }
            else if (lastName != null && !lastName.isEmpty()) {
                line = getString(R.string.pocket_hero_last_played, lastName);
                long when = PocketPrefs.getLastPlayed(this, computer.details.uuid, lastId);
                if (when > 0) {
                    line += "  \u00b7  " + android.text.format.DateUtils.getRelativeTimeSpanString(when,
                            System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS);
                }
            }
            lastLine.setText(line);
            lastLine.setVisibility(line.isEmpty() ? View.GONE : View.VISIBLE);
        }
        int artId = computer.details.runningGameId != 0 ? computer.details.runningGameId : lastId;
        pocketBackdropFor(computer.details.uuid, artId, runningName != null ? runningName : lastName);
        probeLatency(computer.details);
    }

    /** The name of one of a PC's games (from its known list), or null. */
    private String pocketGameName(String uuid, int appId) {
        if (appId == 0) {
            return null;
        }
        for (com.limelight.pocket.RecentGames.Game g : com.limelight.pocket.RecentGames.forPc(this, uuid, 200)) {
            if (g.app.getAppId() == appId) {
                return g.app.getAppName();
            }
        }
        return appId == PocketPrefs.getLastAppId(this, uuid) ? PocketPrefs.getLastAppName(this, uuid) : null;
    }

    private int pocketBackdropToken;
    private String pocketBackdropKey;
    private String pocketBackdropUuid;
    private int pocketBackdropAppId;
    private String pocketBackdropName;
    private final Runnable pocketBackdropTask = () ->
            pocketApplyBackdrop(pocketBackdropUuid, pocketBackdropAppId, pocketBackdropName);

    /**
     * The backdrop for a game: its wide key art when it has one; otherwise its colours light the
     * screen. A refresh asking for the same thing changes nothing, and while you move through
     * the rows it waits until you rest on something (moving stays light and smooth).
     */
    private void pocketBackdropFor(final String uuid, final int appId, final String name) {
        String key = uuid + ":" + appId + "@" + com.limelight.pocket.PocketArt.version();
        if (key.equals(pocketBackdropKey)) {
            return;
        }
        boolean first = pocketBackdropKey == null;
        pocketBackdropKey = key;
        pocketBackdropUuid = uuid;
        pocketBackdropAppId = appId;
        pocketBackdropName = name;
        View host = getWindow().getDecorView();
        host.removeCallbacks(pocketBackdropTask);
        ++pocketBackdropToken;
        if (first || !com.limelight.pocket.PocketPrefs.animationsEnabled(this)) {
            pocketBackdropTask.run();
        }
        else {
            host.postDelayed(pocketBackdropTask, 150);
        }
    }

    private void pocketApplyBackdrop(final String uuid, final int appId, final String name) {
        if (isFinishing()) {
            return;
        }
        final int token = ++pocketBackdropToken;
        final String key = uuid + ":" + appId;
        // Its key art sharp across the top (like the library) over its colours; without key art,
        // just its colours (or its cover, by the style)
        android.graphics.Bitmap keyArt = com.limelight.pocket.PocketArt.cached(this, uuid, appId, com.limelight.pocket.PocketArt.HERO);
        com.limelight.pocket.ui.KeyArtStage stage = pocketKeyArt();
        if (stage != null) {
            com.limelight.pocket.ui.LaunchOverlay.rememberKeyArt(name, keyArt);
            if (keyArt != null || !com.limelight.pocket.PocketArt.has(this, uuid, appId, com.limelight.pocket.PocketArt.HERO)) {
                stage.show(keyArt);
            }
        }
        else if (keyArt != null) {
            pocketHeroArt().show(keyArt, "hero:" + key, true);
            return;
        }
        android.graphics.Bitmap card = com.limelight.pocket.ui.HeroArt.cachedCard(this, uuid, appId, name);
        if (card != null) {
            pocketHeroArt().show(card, "card:" + key, false);
        }
        else {
            com.limelight.pocket.ui.HeroArt.loadCardAsync(this, uuid, appId, name, bitmap -> {
                if (token == pocketBackdropToken && !isFinishing()) {
                    pocketHeroArt().show(bitmap, "card:" + key, false);
                }
            });
        }
        if (keyArt == null) {
            com.limelight.pocket.PocketArt.load(this, uuid, appId, com.limelight.pocket.PocketArt.HERO, bitmap -> {
                if (token != pocketBackdropToken || isFinishing()) {
                    return;
                }
                if (stage != null) {
                    com.limelight.pocket.ui.LaunchOverlay.rememberKeyArt(name, bitmap);
                    stage.show(bitmap);
                }
                else if (bitmap != null) {
                    pocketHeroArt().show(bitmap, "hero:" + key, true);
                }
            });
        }
    }

    private com.limelight.pocket.ui.HeroArt pocketHeroArt;
    private com.limelight.pocket.ui.KeyArtStage pocketKeyArt;
    private boolean pocketKeyArtLooked;
    private com.limelight.pocket.ui.HeroLogo pocketHeroLogo;
    private Runnable pocketAutoArt;

    private com.limelight.pocket.ui.KeyArtStage pocketKeyArt() {
        if (!pocketKeyArtLooked) {
            pocketKeyArtLooked = true;
            pocketKeyArt = com.limelight.pocket.ui.KeyArtStage.find(this);
        }
        return pocketKeyArt;
    }

    private com.limelight.pocket.ui.HeroLogo pocketHeroLogo() {
        if (pocketHeroLogo == null) {
            pocketHeroLogo = new com.limelight.pocket.ui.HeroLogo(this,
                    (android.widget.ImageView) findViewById(R.id.pocketHeroLogo), (TextView) findViewById(R.id.pocketHeroTitle));
        }
        return pocketHeroLogo;
    }

    /** New art arrived (fetched while resting on a game): the hero and the cards take it. */
    private void pocketArtArrived() {
        if (isFinishing()) {
            return;
        }
        pocketBackdropKey = null;
        if (pocketKeyArt != null) {
            pocketKeyArt.invalidate();
        }
        pocketHeroLogo().invalidate();
        if (pocketRecentAdapter != null) {
            pocketRecentAdapter.notifyDataSetChanged();
        }
        if (pocketHeroShowsGame && pocketRecentHeroPos >= 0) {
            pocketShowRecentHero(pocketRecentHeroPos);
        }
    }

    // ---- Artemis Pocket: "Jump back in" (recent games on the home screen) ---------------------

    private RecyclerView pocketRecentCarousel;
    private com.limelight.pocket.ui.RecentGamesAdapter pocketRecentAdapter;
    private boolean pocketHeroShowsGame;
    private int pocketRecentHeroPos = -1;
    private Boolean pocketHintsRecent;

    private final CarouselAdapter.Listener pocketRecentListener = new CarouselAdapter.Listener() {
        @Override
        public void onItemClicked(int position, View itemView) {
            pocketPlayRecent(position);
        }

        @Override
        public void onItemOptions(int position, View itemView) {
            // Y: the whole library of that PC
            ComputerDetails pc = pocketRecentPc();
            if (pc != null && pc.state == ComputerDetails.State.ONLINE && pc.pairState == PairState.PAIRED) {
                doAppList(pc, false, false);
            }
        }

        @Override
        public void onItemFocused(int position, View itemView) {
            pocketShowRecentHero(position);
        }
    };

    /** The PC whose games the row shows. */
    private ComputerDetails pocketRecentPc() {
        if (pocketRecentAdapter == null || pocketRecentAdapter.getUuid() == null) {
            return null;
        }
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerObject o = (ComputerObject) pcGridAdapter.getItem(i);
            if (pocketRecentAdapter.getUuid().equals(o.details.uuid)) {
                return o.details;
            }
        }
        return null;
    }

    /** Shows the recent games of this PC next to the PCs (only for a paired PC with a known list). */
    private void pocketRefreshRecent(ComputerDetails pc) {
        if (pocketRecentAdapter == null || pocketRecentCarousel == null) {
            return;
        }
        boolean known = pc != null && pc.pairState == PairState.PAIRED;
        java.util.List<com.limelight.pocket.RecentGames.Game> games = known
                ? com.limelight.pocket.RecentGames.forPc(this, pc.uuid, 10)
                : new ArrayList<com.limelight.pocket.RecentGames.Game>();
        if (games.isEmpty() && pocketRecentCarousel.hasFocus()) {
            // Don't pull the row away from under the focus
            return;
        }
        pocketRecentAdapter.set(pc != null ? pc.uuid : null, games, pc != null ? pc.runningGameId : 0);
        int visibility = games.isEmpty() ? View.GONE : View.VISIBLE;
        if (pocketRecentCarousel.getVisibility() != visibility) {
            pocketRecentCarousel.setVisibility(visibility);
            if (visibility == View.VISIBLE && com.limelight.pocket.PocketPrefs.animationsEnabled(this)) {
                pocketRecentCarousel.post(() -> com.limelight.pocket.ui.PocketMotion.cascade(
                        pocketRecentCarousel, 60, 40, 40, 0, 8));
            }
        }
    }

    private void pocketHintsFor(boolean recent) {
        if (pocketHintsRecent != null && pocketHintsRecent == recent) {
            return;
        }
        pocketHintsRecent = recent;
        if (recent) {
            setHints(hintA(this, R.string.pocket_hint_play),
                    hintX(this, R.string.pocket_hint_details),
                    hintY(this, R.string.pocket_hint_library),
                    hintSelect(this, R.string.pocket_quick_hint),
                    hintStart(this, R.string.pocket_hint_settings));
        }
        else {
            setHints(hintA(this, R.string.pocket_hint_open),
                    hintY(this, R.string.pocket_hint_options),
                    hintX(this, R.string.pocket_hint_add_pc),
                    hintSelect(this, R.string.pocket_quick_hint),
                    hintStart(this, R.string.pocket_hint_settings));
        }
    }

    /** The hero tells about the focused game: name, Play / Resume, its PC, playtime; its art behind. */
    private void pocketShowRecentHero(int position) {
        if (pocketRecentAdapter == null || position < 0 || position >= pocketRecentAdapter.getCount()) {
            return;
        }
        TextView section = findViewById(R.id.pocketSectionTitle);
        TextView title = findViewById(R.id.pocketHeroTitle);
        TextView subtitle = findViewById(R.id.pocketHeroSubtitle);
        TextView play = findViewById(R.id.pocketHeroPlay);
        TextView lastLine = findViewById(R.id.pocketHeroPlaytime);
        if (title == null || subtitle == null) {
            return;
        }
        com.limelight.pocket.RecentGames.Game game = pocketRecentAdapter.getItem(position);
        String uuid = pocketRecentAdapter.getUuid();
        ComputerDetails pc = pocketRecentPc();
        int appId = game.app.getAppId();
        boolean running = pc != null && pc.runningGameId != 0 && pc.runningGameId == appId;

        boolean moved = !pocketHeroShowsGame || position != pocketRecentHeroPos;
        int dir = !pocketHeroShowsGame || pocketRecentHeroPos < 0 ? 1 : Integer.signum(position - pocketRecentHeroPos);
        if (title.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
            dir = -dir;
        }
        pocketHeroShowsGame = true;
        pocketRecentHeroPos = position;
        pocketHintsFor(true);

        if (section != null) {
            section.setText(R.string.pocket_jump_back_in);
        }
        com.limelight.pocket.ui.PocketMotion.swapText(title, game.app.getAppName(), dir);
        // Its logo in place of its name, like the library
        pocketHeroLogo().forGame(uuid, appId, game.app.getAppName(), dir);
        String sub = (pc != null ? pc.name + "  \u00b7  " : "")
                + getString(running ? R.string.pocket_now_playing : R.string.pocket_ready_to_play);
        if (!sub.contentEquals(subtitle.getText())) {
            subtitle.setText(sub);
            if (moved) {
                com.limelight.pocket.ui.PocketMotion.bump(subtitle);
            }
        }
        if (play != null) {
            String text = "\u25B6  " + getString(running ? R.string.pocket_hero_resume : R.string.pocket_hero_play);
            play.setVisibility(View.VISIBLE);
            if (!text.contentEquals(play.getText())) {
                play.setText(text);
                if (moved) {
                    com.limelight.pocket.ui.PocketMotion.bump(play);
                }
            }
        }
        if (lastLine != null) {
            StringBuilder line = new StringBuilder();
            long played = PocketPrefs.getPlaytime(this, uuid, appId);
            if (played >= 60) {
                long hours = played / 3600;
                long minutes = (played % 3600) / 60;
                line.append(getString(R.string.pocket_playtime, hours > 0
                        ? getString(R.string.pocket_playtime_hm, hours, minutes)
                        : getString(R.string.pocket_playtime_m, Math.max(1, minutes))));
            }
            if (game.lastPlayed > 0) {
                if (line.length() > 0) {
                    line.append("  \u00b7  ");
                }
                line.append(android.text.format.DateUtils.getRelativeTimeSpanString(game.lastPlayed,
                        System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS));
            }
            lastLine.setText(line);
            lastLine.setVisibility(line.length() == 0 ? View.GONE : View.VISIBLE);
        }
        // Its key art behind everything when it has one, else its colours
        pocketBackdropFor(uuid, appId, game.app.getAppName());

        // Rest on it a moment and its key art and logo are fetched (SteamGridDB), as in the library
        final String fetchName = game.app.getAppName();
        View host = getWindow().getDecorView();
        if (pocketAutoArt != null) {
            host.removeCallbacks(pocketAutoArt);
        }
        pocketAutoArt = () -> com.limelight.pocket.PocketArt.autoFetch(this, uuid, appId, fetchName, false,
                this::pocketArtArrived);
        host.postDelayed(pocketAutoArt, 900);
    }

    /** A: play it (or resume it); the library takes over when another game is running. */
    private void pocketPlayRecent(int position) {
        if (pocketRecentAdapter == null || position < 0 || position >= pocketRecentAdapter.getCount()) {
            return;
        }
        com.limelight.pocket.RecentGames.Game game = pocketRecentAdapter.getItem(position);
        pocketLaunch(pocketRecentPc(), game.app);
    }

    /** Starts (or resumes) a game on a PC: wakes it, pairs it, or lets the library ask first. */
    private void pocketLaunch(ComputerDetails pc, NvApp app) {
        if (pc == null || app == null) {
            return;
        }
        if (pc.state == ComputerDetails.State.OFFLINE && pc.pairState == PairState.PAIRED && pc.macAddress != null) {
            pocketWakeAndOpen(pc);
            return;
        }
        if (pc.state != ComputerDetails.State.ONLINE) {
            Toast.makeText(this, R.string.pair_pc_offline, Toast.LENGTH_SHORT).show();
            return;
        }
        if (pc.pairState != PairState.PAIRED) {
            doPair(pc, null, null);
            return;
        }
        if (pc.runningGameId != 0 && pc.runningGameId != app.getAppId()) {
            // Another game is running: the library asks what to do with it
            doAppList(pc, false, false);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && managerBinder != null) {
            com.limelight.pocket.RecentGames.invalidate(pc.uuid);
            ServerHelper.doStart(this, app, pc, managerBinder,
                    PreferenceConfiguration.readPreferences(this).useVirtualDisplay);
        }
    }

    // ---- Quick Resume ---------------------------------------------------------------------------

    @Override
    public java.util.List<com.limelight.pocket.ui.QuickResumePanel.Entry> quickResumeEntries() {
        java.util.List<com.limelight.pocket.ui.QuickResumePanel.Entry> out = new java.util.ArrayList<>();
        if (pcGridAdapter == null) {
            return out;
        }
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerDetails pc = ((ComputerObject) pcGridAdapter.getItem(i)).details;
            if (pc == null || pc.uuid == null || pc.pairState != PairState.PAIRED) {
                continue;
            }
            for (com.limelight.pocket.RecentGames.Game g : com.limelight.pocket.RecentGames.forPc(this, pc.uuid, 200)) {
                boolean running = pc.state == ComputerDetails.State.ONLINE && pc.runningGameId != 0
                        && pc.runningGameId == g.app.getAppId();
                if (running || g.lastPlayed > 0) {
                    out.add(new com.limelight.pocket.ui.QuickResumePanel.Entry(pc.uuid, pc.name, g.app, running, g.lastPlayed));
                }
            }
        }
        return out;
    }

    @Override
    public void quickLaunch(com.limelight.pocket.ui.QuickResumePanel.Entry entry) {
        if (pcGridAdapter == null) {
            return;
        }
        for (int i = 0; i < pcGridAdapter.getCount(); i++) {
            ComputerDetails pc = ((ComputerObject) pcGridAdapter.getItem(i)).details;
            if (pc != null && entry.uuid.equals(pc.uuid)) {
                pocketLaunch(pc, entry.app);
                return;
            }
        }
    }

    private com.limelight.pocket.ui.HeroArt pocketHeroArt() {
        if (pocketHeroArt == null) {
            pocketHeroArt = new com.limelight.pocket.ui.HeroArt(getWindow().getDecorView());
        }
        return pocketHeroArt;
    }

    /** The label for what A does on this PC (0: nothing worth a button). */
    private int pocketPrimaryLabel(ComputerDetails details) {
        if (details.state == ComputerDetails.State.ONLINE) {
            if (details.pairState != PairState.PAIRED) {
                return R.string.pocket_hint_pair;
            }
            return details.runningGameId != 0 ? R.string.pocket_hero_resume : R.string.pocket_hint_open;
        }
        if (details.state == ComputerDetails.State.OFFLINE && details.pairState == PairState.PAIRED
                && details.macAddress != null) {
            return R.string.pocket_hero_wake;
        }
        return 0;
    }

    private int pocketHeroPos = -1;

    /** Measures how long a TCP handshake to the PC takes, as a rough "ping" in the hero line. */
    private void probeLatency(final ComputerDetails details) {
        if (latencyProbeRunning || !PocketPrefs.showLatency(this) ||
                details.state != ComputerDetails.State.ONLINE || details.activeAddress == null) {
            return;
        }
        String usb = UsbLink.getUsbAddressFor(this, details.uuid);
        final String host = usb != null ? usb : details.activeAddress.address;
        final int port = usb != null ? UsbLink.HTTP_PORT : details.activeAddress.port;
        final String uuid = details.uuid;
        latencyProbeRunning = true;
        new Thread(() -> {
            long best = Long.MAX_VALUE;
            for (int i = 0; i < 3; i++) {
                java.net.Socket socket = new java.net.Socket();
                long start = System.nanoTime();
                try {
                    socket.connect(new java.net.InetSocketAddress(host, port), 800);
                    best = Math.min(best, System.nanoTime() - start);
                } catch (java.io.IOException ignored) {
                } finally {
                    try {
                        socket.close();
                    } catch (java.io.IOException ignored) {
                    }
                }
            }
            final long result = best;
            runOnUiThread(() -> {
                latencyProbeRunning = false;
                if (result != Long.MAX_VALUE) {
                    double ms = result / 1_000_000.0;
                    latencyText = ms < 1 ? "<1 ms" : Math.round(ms) + " ms";
                    latencyForUuid = uuid;
                }
            });
        }, "latency probe").start();
    }

    /** Optional: jump straight into the library of the PC you used last. */
    private void maybeOpenLastPc(ComputerDetails details) {
        if (autoOpenedLastPc || usbState != USB_IDLE || !PocketPrefs.openLastPc(this) || !inForeground) {
            return;
        }
        if (isBooting()) {
            // Let the boot sequence play here first, then go to the library
            runAfterBoot(() -> maybeOpenLastPc(details));
            return;
        }
        String last = PocketPrefs.getLastPcUuid(this);
        if (last != null && last.equals(details.uuid) && details.state == ComputerDetails.State.ONLINE
                && details.pairState == PairState.PAIRED) {
            autoOpenedLastPc = true;
            doAppList(details, false, false);
        }
    }

    public String describeComputer(ComputerDetails details, boolean longForm) {
        String status;
        switch (details.state) {
            case ONLINE:
                if (details.pairState != PairState.PAIRED) {
                    status = getString(R.string.pocket_status_unpaired);
                }
                else if (details.runningGameId != 0) {
                    status = getString(R.string.pocket_status_ingame);
                }
                else {
                    status = getString(R.string.pocket_status_online);
                }
                break;
            case OFFLINE:
                status = getString(R.string.pocket_status_offline);
                break;
            default:
                status = getString(R.string.pocket_status_checking);
                break;
        }
        if (details.state == ComputerDetails.State.ONLINE &&
                UsbLink.getUsbAddressFor(this, details.uuid) != null) {
            status += " · " + getString(R.string.pocket_status_via_usb);
        }
        if (longForm && details.activeAddress != null && details.state == ComputerDetails.State.ONLINE) {
            status += " · " + details.activeAddress.address;
        }
        return status;
    }

    @Override
    protected boolean onButtonX() {
        // On a game of "Jump back in": its page; on a PC: add another PC
        if (pocketRecentCarousel != null && pocketRecentCarousel.hasFocus() && pocketOpenRecentPage()) {
            return true;
        }
        startActivity(new Intent(PcView.this, AddComputerManually.class));
        return true;
    }

    @Override
    protected boolean onGamePageShortcut() {
        return pocketRecentCarousel != null && pocketRecentCarousel.hasFocus() && pocketOpenRecentPage();
    }

    private boolean pocketOpenRecentPage() {
        if (pocketRecentAdapter == null || pocketRecentCarousel == null) {
            return false;
        }
        View focused = pocketRecentCarousel.getFocusedChild();
        int pos = focused != null ? pocketRecentCarousel.getChildAdapterPosition(focused) : -1;
        if (pos < 0 || pos >= pocketRecentAdapter.getCount()) {
            return false;
        }
        com.limelight.pocket.RecentGames.Game game = pocketRecentAdapter.getItem(pos);
        ComputerDetails pc = pocketRecentPc();
        android.widget.ImageView image = focused.findViewById(R.id.grid_image);
        android.graphics.Bitmap cover = image != null && image.getDrawable() instanceof android.graphics.drawable.BitmapDrawable
                ? ((android.graphics.drawable.BitmapDrawable) image.getDrawable()).getBitmap() : null;
        boolean running = pc != null && pc.runningGameId != 0 && pc.runningGameId == game.app.getAppId();
        com.limelight.pocket.GamePageActivity.open(this, image != null ? image : focused,
                pocketRecentAdapter.getUuid(), game.app.getAppId(), game.app.getAppName(),
                pc != null ? pc.name : null, running, false, cover);
        return true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, android.content.Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != com.limelight.pocket.GamePageActivity.REQUEST || data == null) {
            return;
        }
        if (data.getBooleanExtra(com.limelight.pocket.GamePageActivity.RESULT_CHANGED, false)) {
            com.limelight.pocket.RecentGames.invalidate(pocketRecentAdapter != null ? pocketRecentAdapter.getUuid() : null);
            pocketArtArrived();
        }
        if (com.limelight.pocket.GamePageActivity.ACTION_PLAY.equals(
                data.getStringExtra(com.limelight.pocket.GamePageActivity.RESULT_ACTION)) && pocketRecentAdapter != null) {
            int id = data.getIntExtra(com.limelight.pocket.GamePageActivity.RESULT_APP_ID, 0);
            for (int i = 0; i < pocketRecentAdapter.getCount(); i++) {
                if (pocketRecentAdapter.getItem(i).app.getAppId() == id) {
                    pocketPlayRecent(i);
                    break;
                }
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (usbState != USB_IDLE) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (usbState == USB_NEEDS_TETHER) {
                        tetherPromptDismissed = true;
                    }
                    cancelUsbFlow(true);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_BUTTON_A || keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_UP) {
                    if (usbState == USB_COUNTDOWN) {
                        usbCountdown = 0;
                        usbHandler.removeCallbacks(usbCountdownTick);
                        usbHandler.post(usbCountdownTick);
                    }
                    else if (usbState == USB_NEEDS_PAIR && usbComputer != null) {
                        ComputerDetails target = usbComputer;
                        cancelUsbFlow(false);
                        doPair(target, null, null);
                    }
                    else if (usbState == USB_NEEDS_TETHER) {
                        if (UsbLink.getTetheringSupport(this) == UsbLink.TETHER_UNSUPPORTED) {
                            com.limelight.pocket.QuickSettingsActivity.showUsbHelp(this);
                        }
                        else {
                            UsbLink.openTetherSettings(this);
                        }
                    }
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    // ---- USB-C plug & play ------------------------------------------------------------

    @Override
    protected void onStatusTick() {
        boolean cable = UsbLink.isUsbCableConnected(this);
        if (!cable && cableWasConnected) {
            tetherPromptDismissed = false;
        }
        cableWasConnected = cable;
        cableTicks = cable ? cableTicks + 1 : 0;

        boolean up = UsbLink.isAnyUsbLinkUp();
        if (up && usbState == USB_NEEDS_TETHER) {
            // Tethering or the ADB bridge just came up: hand over to the normal connect flow
            usbState = USB_IDLE;
        }
        else if (!up && cable && cableTicks >= 3 && usbState == USB_IDLE && !tetherPromptDismissed
                && PocketPrefs.usbAutoConnect(this) && inForeground) {
            usbState = USB_NEEDS_TETHER;
            renderUsbOverlay();
        }
        else if (!cable && usbState == USB_NEEDS_TETHER) {
            cancelUsbFlow(false);
        }
        if (up && !usbLinkWasUp) {
            usbLinkWasUp = true;
            if (PocketPrefs.usbAutoConnect(this)) {
                startUsbFlow();
            }
        }
        else if (!up && usbLinkWasUp) {
            usbLinkWasUp = false;
            UsbLink.clearActiveHost();
            cancelUsbFlow(false);
        }
        // PC states change as the link comes and goes
        if (pcGridAdapter != null && carouselAdapter != null) {
            updateHero(carouselAdapter.getFocusedPosition());
        }
    }

    private void startUsbFlow() {
        if (usbThread != null) {
            return;
        }
        if (isBooting()) {
            runAfterBoot(this::startUsbFlow);
            return;
        }
        usbState = USB_SEARCHING;
        usbComputer = null;
        renderUsbOverlay();

        usbThread = new Thread(() -> {
            String found = null;
            if (UsbBridge.ensureConnected()) {
                // ADB bridge: the PC is reachable at 127.0.0.1 through the cable
                found = UsbBridge.HOST_IP;
            }
            // Windows needs a few seconds to get its address from the handheld after plug-in
            for (int attempt = 0; found == null && attempt < 30 && !Thread.currentThread().isInterrupted(); attempt++) {
                UsbLink.TetherInfo info = UsbLink.findTetherInterface();
                if (info == null) {
                    if (UsbBridge.ensureConnected()) {
                        found = UsbBridge.HOST_IP;
                    }
                    break;
                }
                LimeLog.info("USB link up on " + info.describe() + ", scanning (attempt " + (attempt + 1) + ")");
                found = UsbLink.scanForHost(info, PocketPrefs.getLastUsbIp(PcView.this));
                if (found != null) {
                    break;
                }
                if (attempt == 2) {
                    runOnUiThread(() -> {
                        if (usbState == USB_SEARCHING) {
                            usbState = USB_NOT_FOUND;
                            renderUsbOverlay();
                        }
                    });
                }
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (found == null || Thread.currentThread().isInterrupted()) {
                final boolean linkGone = !UsbLink.isLinkUp();
                runOnUiThread(() -> {
                    usbThread = null;
                    if (linkGone) {
                        cancelUsbFlow(false);
                    }
                    else if (usbState != USB_IDLE) {
                        usbState = USB_NOT_FOUND;
                        renderUsbOverlay();
                    }
                });
                return;
            }

            // Wait for the computer manager, then add/refresh the PC at its USB address
            ComputerManagerService.ComputerManagerBinder binder = null;
            for (int i = 0; i < 50 && binder == null; i++) {
                binder = managerBinder;
                if (binder == null) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
            if (binder == null) {
                runOnUiThread(() -> {
                    usbThread = null;
                    cancelUsbFlow(false);
                });
                return;
            }

            ComputerDetails details = new ComputerDetails();
            details.manualAddress = new ComputerDetails.AddressTuple(found, UsbLink.HTTP_PORT);
            boolean ok;
            try {
                ok = binder.addComputerBlocking(details);
            } catch (InterruptedException e) {
                return;
            } catch (RuntimeException e) {
                e.printStackTrace();
                ok = false;
            }

            final boolean success = ok && details.uuid != null;
            final String usbIp = found;
            if (success) {
                UsbLink.setActiveHost(PcView.this, usbIp, details.uuid);
                // Make sure this session talks to the PC over the cable
                details.activeAddress = new ComputerDetails.AddressTuple(usbIp, UsbLink.HTTP_PORT);
            }
            runOnUiThread(() -> {
                usbThread = null;
                if (usbState == USB_IDLE) {
                    return;
                }
                if (!success) {
                    usbState = USB_NOT_FOUND;
                    renderUsbOverlay();
                    return;
                }
                usbComputer = details;
                if (details.pairState != PairState.PAIRED) {
                    usbState = USB_NEEDS_PAIR;
                    renderUsbOverlay();
                    return;
                }
                if (PocketPrefs.ON_CONNECT_NOTHING.equals(PocketPrefs.usbOnConnect(PcView.this))) {
                    Toast.makeText(PcView.this, getString(R.string.pocket_usb_found, details.name), Toast.LENGTH_SHORT).show();
                    cancelUsbFlow(false);
                    return;
                }
                usbState = USB_COUNTDOWN;
                usbCountdown = 3;
                renderUsbOverlay();
                usbHandler.removeCallbacks(usbCountdownTick);
                usbHandler.postDelayed(usbCountdownTick, 1000);
            });
        });
        usbThread.setName("USB link scan");
        usbThread.start();
    }

    private final Runnable usbCountdownTick = new Runnable() {
        @Override
        public void run() {
            if (usbState != USB_COUNTDOWN) {
                return;
            }
            usbCountdown--;
            if (usbCountdown > 0) {
                renderUsbOverlay();
                usbHandler.postDelayed(this, 1000);
                return;
            }
            ComputerDetails target = usbComputer;
            cancelUsbFlow(false);
            if (target != null) {
                launchUsbTarget(target);
            }
        }
    };

    private void launchUsbTarget(ComputerDetails computer) {
        if (managerBinder == null) {
            return;
        }
        String action = PocketPrefs.usbOnConnect(this);
        if (computer.runningGameId != 0) {
            // A game is already running on the PC: jump right back in (with the virtual display
            // setting, in case the PC's session has ended and this turns into a fresh launch)
            ServerHelper.doStart(this, new NvApp("app", null, computer.runningGameId, false), computer, managerBinder,
                    PreferenceConfiguration.readPreferences(this).useVirtualDisplay);
            return;
        }
        if (PocketPrefs.ON_CONNECT_LAST_GAME.equals(action)) {
            int lastAppId = PocketPrefs.getLastAppId(this, computer.uuid);
            if (lastAppId != 0) {
                String lastName = PocketPrefs.getLastAppName(this, computer.uuid);
                PreferenceConfiguration prefs = PreferenceConfiguration.readPreferences(this);
                ServerHelper.doStart(this, new NvApp(lastName != null ? lastName : "app", null, lastAppId, false),
                        computer, managerBinder, prefs.useVirtualDisplay);
                return;
            }
        }
        doAppList(computer, false, false);
    }

    private void cancelUsbFlow(boolean userCancelled) {
        usbHandler.removeCallbacks(usbCountdownTick);
        if (usbThread != null) {
            usbThread.interrupt();
            usbThread = null;
        }
        usbState = USB_IDLE;
        usbComputer = null;
        renderUsbOverlay();
        if (userCancelled && pcCarousel != null) {
            pcCarousel.post(this::focusFirstPcIfNeeded);
        }
    }

    private void renderUsbOverlay() {
        View overlay = findViewById(R.id.pocketUsbOverlay);
        if (overlay == null) {
            return;
        }
        PocketUi.setBackdropBlur(findViewById(R.id.pocketMain), usbState != USB_IDLE);
        if (usbState == USB_IDLE) {
            if (overlay.getVisibility() == View.VISIBLE) {
                overlay.animate().alpha(0f).setDuration(220)
                        .withEndAction(() -> overlay.setVisibility(View.GONE)).start();
            }
            if (usbPulse != null) {
                usbPulse.cancel();
                usbPulse = null;
            }
            return;
        }

        if (overlay.getVisibility() != View.VISIBLE) {
            overlay.setAlpha(0f);
            overlay.setVisibility(View.VISIBLE);
            overlay.animate().alpha(1f).setDuration(180).start();
            View panel = findViewById(R.id.pocketUsbPanel);
            if (panel != null && PocketPrefs.animationsEnabled(this)) {
                panel.setScaleX(0.97f);
                panel.setScaleY(0.97f);
                panel.animate().scaleX(1f).scaleY(1f).setDuration(200)
                        .setInterpolator(PocketUi.EASE).start();
            }
        }

        View pulse = findViewById(R.id.pocketUsbPulse);
        if (usbPulse == null && pulse != null && PocketPrefs.animationsEnabled(this)) {
            usbPulse = ObjectAnimator.ofPropertyValuesHolder(pulse,
                    PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.55f),
                    PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.55f),
                    PropertyValuesHolder.ofFloat(View.ALPHA, 0.9f, 0f));
            usbPulse.setDuration(1400);
            usbPulse.setRepeatCount(ValueAnimator.INFINITE);
            usbPulse.start();
        }

        TextView title = findViewById(R.id.pocketUsbTitle);
        TextView subtitle = findViewById(R.id.pocketUsbSubtitle);
        ViewGroup hints = findViewById(R.id.pocketUsbHints);
        String name = usbComputer != null ? usbComputer.name : "";
        switch (usbState) {
            case USB_SEARCHING:
                title.setText(R.string.pocket_usb_detected);
                subtitle.setText(R.string.pocket_usb_searching);
                fillHintBar(this, hints, hintB(this, R.string.pocket_hint_cancel));
                break;
            case USB_NOT_FOUND:
                title.setText(R.string.pocket_usb_not_found);
                subtitle.setText(R.string.pocket_usb_not_found_help);
                fillHintBar(this, hints, hintB(this, R.string.pocket_hint_close));
                break;
            case USB_NEEDS_PAIR:
                title.setText(getString(R.string.pocket_usb_found, name));
                subtitle.setText(R.string.pocket_usb_needs_pair);
                fillHintBar(this, hints, hintA(this, R.string.pocket_hint_pair), hintB(this, R.string.pocket_hint_cancel));
                break;
            case USB_NEEDS_TETHER:
                title.setText(R.string.pocket_usb_cable_title);
                subtitle.setText(UsbLink.getTetheringSupport(this) == UsbLink.TETHER_UNSUPPORTED ?
                        R.string.pocket_usb_cable_unsupported : R.string.pocket_usb_cable_sub);
                fillHintBar(this, hints, hintA(this, R.string.pocket_hint_turn_on), hintB(this, R.string.pocket_hint_not_now));
                break;
            case USB_COUNTDOWN: {
                title.setText(getString(R.string.pocket_usb_found, name));
                String action = PocketPrefs.usbOnConnect(this);
                String what;
                if (usbComputer != null && usbComputer.runningGameId != 0) {
                    what = getString(R.string.pocket_usb_resuming);
                }
                else if (PocketPrefs.ON_CONNECT_LAST_GAME.equals(action) && usbComputer != null &&
                        PocketPrefs.getLastAppId(this, usbComputer.uuid) != 0) {
                    String last = PocketPrefs.getLastAppName(this, usbComputer.uuid);
                    what = getString(R.string.pocket_usb_starting, last != null ? last : "");
                }
                else {
                    what = getString(R.string.pocket_usb_opening_library);
                }
                subtitle.setText(getString(R.string.pocket_usb_countdown, what, usbCountdown));
                fillHintBar(this, hints, hintA(this, R.string.pocket_hint_go), hintB(this, R.string.pocket_hint_cancel));
                break;
            }
        }
    }

    public static class ComputerObject {
        public ComputerDetails details;

        public ComputerObject(ComputerDetails details) {
            if (details == null) {
                throw new IllegalArgumentException("details must not be null");
            }
            this.details = details;
        }

        @Override
        public String toString() {
            return details.name;
        }
        public String guessManagementUrl() {
            if (details.activeAddress == null) return null;
            return "https://" + details.activeAddress.address + ":" + (details.guessExternalPort() + 1);
        }
    }
}
