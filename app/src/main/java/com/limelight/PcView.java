package com.limelight;

import java.io.FileNotFoundException;
import java.io.IOException;
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

public class PcView extends ConsoleActivity implements CarouselAdapter.Listener {
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
        pcCarousel.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        pcCarousel.setItemAnimator(null);
        carouselAdapter = new CarouselAdapter(pcGridAdapter, this);
        pcCarousel.setAdapter(carouselAdapter);

        setHints(hintA(this, R.string.pocket_hint_open),
                hintY(this, R.string.pocket_hint_options),
                hintX(this, R.string.pocket_hint_add_pc),
                hintStart(this, R.string.pocket_hint_settings));
        ConsoleActivity.reveal(findViewById(R.id.pocketHero), 60);
        ConsoleActivity.reveal(pcCarousel, 140);

        // Setup the list view
        ImageButton settingsButton = findViewById(R.id.settingsButton);
        ImageButton addComputerButton = findViewById(R.id.manuallyAddPc);
        ImageButton helpButton = findViewById(R.id.helpButton);
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);

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
        playSplashIfNeeded();
    }

    private void playSplashIfNeeded() {
        final View splash = findViewById(R.id.pocketSplash);
        if (splash == null) {
            return;
        }
        if (splashShownThisProcess || !PocketPrefs.animationsEnabled(this)) {
            splashShownThisProcess = true;
            splash.setVisibility(View.GONE);
            return;
        }
        splashShownThisProcess = true;
        View logo = findViewById(R.id.pocketSplashLogo);
        final TextView text = findViewById(R.id.pocketSplashText);
        splash.setVisibility(View.VISIBLE);
        splash.setAlpha(1f);
        logo.setAlpha(0f);
        logo.setScaleX(0.8f);
        logo.setScaleY(0.8f);
        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(650).setInterpolator(PocketUi.EASE).start();
        text.setAlpha(0f);
        ValueAnimator spacing = ValueAnimator.ofFloat(0.9f, 0.45f);
        spacing.setDuration(900);
        spacing.setStartDelay(150);
        spacing.setInterpolator(PocketUi.EASE);
        spacing.addUpdateListener(a -> {
            text.setLetterSpacing((Float) a.getAnimatedValue());
            text.setAlpha(Math.min(1f, a.getAnimatedFraction() * 1.6f));
        });
        spacing.start();
        splash.animate().alpha(0f).scaleX(1.06f).scaleY(1.06f).setStartDelay(1250).setDuration(450)
                .setInterpolator(PocketUi.EASE)
                .withEndAction(() -> splash.setVisibility(View.GONE)).start();
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
        ExtendedFloatingActionButton profilesButton = findViewById(R.id.profilesButton);
        // User report Samsung and Xiaomi devices have this problem
        // Why just these two brands have the most problems?
        if (profilesButton == null) {
            return;
        }
        String activeProfileName = ProfilesManager.getInstance().getActiveName();
        if (activeProfileName.isEmpty()) {
            profilesButton.shrink();
        } else {
            profilesButton.setText(activeProfileName);
            profilesButton.extend();
        }
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

                ServerHelper.doStart(this, new NvApp("app", null, computer.details.runningGameId, false), computer.details, managerBinder, false);
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
        if (!carouselAnimated && pcCarousel != null && pcGridAdapter.getCount() > 0) {
            carouselAnimated = true;
            if (PocketPrefs.animationsEnabled(this)) {
                pcCarousel.scheduleLayoutAnimation();
            }
        }
        updateHero(carouselAdapter != null ? carouselAdapter.getFocusedPosition() : -1);
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
        if (computer.details.state == ComputerDetails.State.UNKNOWN ||
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
        registerForContextMenu(itemView);
        openContextMenu(itemView);
        unregisterForContextMenu(itemView);
    }

    @Override
    public void onItemFocused(int pos, View itemView) {
        updateHero(pos);
    }

    private void focusFirstPcIfNeeded() {
        if (pcCarousel == null || pcGridAdapter.getCount() == 0 || usbState != USB_IDLE) {
            return;
        }
        View focused = getCurrentFocus();
        if (focused != null && focused != pcCarousel && isDescendant(pcCarousel, focused)) {
            return;
        }
        if (focused != null && focused != getWindow().getDecorView() && !(focused instanceof RecyclerView)) {
            // Something else (e.g. a toolbar button) has focus, leave it alone
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
        int count = pcGridAdapter.getCount();
        if (count == 0) {
            title.setText(R.string.pocket_brand_title);
            subtitle.setText(R.string.pocket_hero_no_pc);
            return;
        }
        if (position < 0 || position >= count) {
            position = 0;
        }
        ComputerObject computer = (ComputerObject) pcGridAdapter.getItem(position);
        PocketUi.swapText(title, computer.details.name);
        String sub = describeComputer(computer.details, true);
        if (latencyText != null && computer.details.uuid.equals(latencyForUuid)
                && computer.details.state == ComputerDetails.State.ONLINE) {
            sub += " · " + latencyText;
        }
        subtitle.setText(sub);
        probeLatency(computer.details);
    }

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
        startActivity(new Intent(PcView.this, AddComputerManually.class));
        return true;
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
            // A game is already running on the PC: jump right back in
            ServerHelper.doStart(this, new NvApp("app", null, computer.runningGameId, false), computer, managerBinder, false);
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
            overlay.animate().alpha(1f).setDuration(260).start();
            View panel = findViewById(R.id.pocketUsbPanel);
            if (panel != null && PocketPrefs.animationsEnabled(this)) {
                panel.setScaleX(0.9f);
                panel.setScaleY(0.9f);
                panel.setAlpha(0f);
                panel.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(420)
                        .setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).start();
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
