package com.limelight;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.widget.Toast;

import com.limelight.pocket.ConsoleActivity;

import com.limelight.profiles.ProfilesManager;

public class ArtemisApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Artemis Pocket: keep the stack trace of any crash so the next launch can show it
        com.limelight.pocket.CrashReporter.install(this);
        // Pocket Nexus: the interface typeface (Inter)
        com.limelight.pocket.ui.PocketFonts.init(this);
        ProfilesManager profilesManager = ProfilesManager.getInstance();
        if (!profilesManager.load(this)) {
            Toast.makeText(this, R.string.profile_manager_failed_to_load, Toast.LENGTH_LONG).show();
        }

        // Artemis Pocket: keep every screen full screen (the stream manages its own window)
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) {}
            @Override public void onActivityStarted(Activity activity) {}
            @Override public void onActivityResumed(Activity activity) {
                if (!(activity instanceof Game)) {
                    try {
                        ConsoleActivity.hideSystemBars(activity);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            @Override public void onActivityPaused(Activity activity) {}
            @Override public void onActivityStopped(Activity activity) {}
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
            @Override public void onActivityDestroyed(Activity activity) {}
        });
    }
}