package com.limelight.pocket.retro;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Process;
import android.provider.DocumentsContract;

import androidx.core.content.FileProvider;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts a game with one of its system's ES-DE commands: RetroArch with the core, or a
 * standalone emulator, with the game as a path, a document URI or a FileProvider URI
 * (access granted for the launch), exactly as the command says.
 */
public final class RetroLauncher {
    private RetroLauncher() {}

    public static final int OK = 0;
    /** No emulator for this system on Android. */
    public static final int NO_EMULATOR = 1;
    /** The command's emulator isn't installed. */
    public static final int NOT_INSTALLED = 2;
    public static final int FAILED = 3;
    /** The game file isn't there any more. */
    public static final int NO_FILE = 4;

    public static final class Result {
        public final int status;
        /** For NOT_INSTALLED: the emulator's package (to look it up), else null. */
        public final String packageName;
        public final String detail;

        Result(int status, String packageName, String detail) {
            this.status = status;
            this.packageName = packageName;
            this.detail = detail;
        }
    }

    private static final Object NONE = new Object();
    private static final Map<String, Object> FOUND = new HashMap<>();

    /** Looks for the emulators again (after one was installed or removed). */
    public static synchronized void forget() {
        FOUND.clear();
    }

    /** The first installed "package/activity" of a find rule, or null. */
    public static synchronized ComponentName component(Context context, EsdeConfig cfg, String emulator) {
        if (emulator == null) {
            return null;
        }
        Object known = FOUND.get(emulator);
        if (known != null) {
            return known == NONE ? null : (ComponentName) known;
        }
        ComponentName found = find(context.getPackageManager(), cfg.emulatorEntries(emulator));
        FOUND.put(emulator, found != null ? found : NONE);
        return found;
    }

    static ComponentName find(PackageManager pm, List<String> entries) {
        ComponentName fallback = null;
        for (String entry : entries) {
            int slash = entry.indexOf('/');
            String pkg = slash > 0 ? entry.substring(0, slash) : entry;
            String cls = slash > 0 ? entry.substring(slash + 1) : null;
            try {
                pm.getPackageInfo(pkg, 0);
            } catch (PackageManager.NameNotFoundException | RuntimeException e) {
                continue;
            }
            if (cls != null && cls.startsWith(".")) {
                cls = pkg + cls;
            }
            if (cls != null) {
                ComponentName cn = new ComponentName(pkg, cls);
                try {
                    pm.getActivityInfo(cn, 0);
                    return cn;
                } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
                    // This version calls its activity something else
                }
            }
            if (fallback == null) {
                Intent launch = pm.getLaunchIntentForPackage(pkg);
                if (launch != null && launch.getComponent() != null) {
                    fallback = launch.getComponent();
                }
            }
        }
        return fallback;
    }

    /** True when the command can start here (its emulator is installed, or it needs none). */
    public static boolean installed(Context context, EsdeConfig cfg, EsdeConfig.Command cmd) {
        if (cmd.isPlaceholder()) {
            return false;
        }
        return cmd.emulator == null || component(context, cfg, cmd.emulator) != null;
    }

    /** The emulator's package to look up when it's missing. */
    public static String packageToGet(EsdeConfig cfg, EsdeConfig.Command cmd) {
        List<String> entries = cfg.emulatorEntries(cmd.emulator);
        if (entries.isEmpty()) {
            return null;
        }
        // The free, stable build (not "gold", "pro", nightly or debug ones) when there's a choice
        String best = null;
        for (String e : entries) {
            int slash = e.indexOf('/');
            String pkg = slash > 0 ? e.substring(0, slash) : e;
            String p = pkg.toLowerCase(java.util.Locale.ROOT);
            boolean special = p.contains("gold") || p.contains(".pro") || p.contains("premium") || p.contains("deluxe")
                    || p.contains("nightly") || p.contains("debug") || p.contains("canary") || p.contains("antutu")
                    || p.contains("tencent") || p.contains("ludashi") || p.contains("mihoyo") || p.contains("amazon");
            if (!special) {
                return pkg;
            }
            if (best == null) {
                best = pkg;
            }
        }
        return best;
    }

    /** The game as an Android document URI (what ES-DE calls %ROMSAF%). */
    public static Uri safUri(File file) {
        String path = file.getAbsolutePath();
        String primary = Environment.getExternalStorageDirectory().getAbsolutePath();
        String docId;
        if (path.startsWith(primary + "/")) {
            docId = "primary:" + path.substring(primary.length() + 1);
        }
        else if (path.startsWith("/storage/")) {
            String rest = path.substring(9);
            int slash = rest.indexOf('/');
            docId = slash > 0 ? rest.substring(0, slash) + ":" + rest.substring(slash + 1) : rest + ":";
        }
        else {
            docId = "primary:" + path;
        }
        return DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", docId);
    }

    private static final class AndroidEnv extends EsdeCommand.FileEnv {
        final Context context;
        final File rom;
        final File root;
        final String pkg;
        Uri provider;

        AndroidEnv(Context context, File rom, File root, String pkg) {
            this.context = context;
            this.rom = rom;
            this.root = root;
            this.pkg = pkg;
        }

        @Override
        public String rom() {
            return rom.getAbsolutePath();
        }

        @Override
        public String romRoot() {
            return root.getAbsolutePath();
        }

        @Override
        public String externalData() {
            return Environment.getExternalStorageDirectory().getAbsolutePath();
        }

        @Override
        public String internalData() {
            return "/data/user/" + (Process.myUid() / 100000);
        }

        @Override
        public String androidPackage() {
            return pkg != null ? pkg : "";
        }

        @Override
        public String saf() {
            return safUri(rom).toString();
        }

        @Override
        public String provider() {
            if (provider == null) {
                provider = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", rom);
            }
            return provider.toString();
        }
    }

    /** Starts the game (on the main thread). */
    public static Result launch(Activity activity, EsdeConfig cfg, EsdeConfig.Command cmd, File rom, File root) {
        if (cmd == null || cmd.isPlaceholder()) {
            return new Result(NO_EMULATOR, null, null);
        }
        if (!rom.exists()) {
            return new Result(NO_FILE, null, rom.getAbsolutePath());
        }
        ComponentName component = null;
        if (cmd.emulator != null) {
            component = component(activity, cfg, cmd.emulator);
            if (component == null) {
                return new Result(NOT_INSTALLED, packageToGet(cfg, cmd), cmd.emulator);
            }
        }
        AndroidEnv env = new AndroidEnv(activity, rom, root, component != null ? component.getPackageName() : null);
        EsdeCommand.Spec spec;
        try {
            spec = EsdeCommand.resolve(cmd.template, env);
        } catch (RuntimeException e) {
            // e.g. the FileProvider can't share a file on this drive
            return new Result(FAILED, null, String.valueOf(e.getMessage()));
        }
        if (spec.placeholder) {
            return new Result(NO_EMULATOR, null, null);
        }
        Intent intent = new Intent();
        if (spec.androidApp != null && !spec.androidApp.isEmpty()) {
            String app = spec.androidApp.trim();
            int slash = app.indexOf('/');
            String pkg = slash > 0 ? app.substring(0, slash) : app;
            if (slash > 0) {
                String cls = app.substring(slash + 1);
                intent.setComponent(new ComponentName(pkg, cls.startsWith(".") ? pkg + cls : cls));
            }
            else {
                Intent launch = activity.getPackageManager().getLaunchIntentForPackage(pkg);
                if (launch == null) {
                    return new Result(NOT_INSTALLED, pkg, pkg);
                }
                intent = launch;
            }
        }
        else if (component != null) {
            intent.setComponent(component);
        }
        else {
            return new Result(NO_EMULATOR, null, null);
        }
        if (spec.action != null && !spec.action.isEmpty()) {
            intent.setAction(spec.action);
        }
        if (spec.category != null && !spec.category.isEmpty()) {
            intent.addCategory(spec.category);
        }
        if (spec.data != null && !spec.data.isEmpty()) {
            Uri data = Uri.parse(spec.data);
            if (spec.mimeType != null && !spec.mimeType.isEmpty()) {
                intent.setDataAndType(data, spec.mimeType);
            }
            else {
                intent.setData(data);
            }
        }
        else if (spec.mimeType != null && !spec.mimeType.isEmpty()) {
            intent.setType(spec.mimeType);
        }
        for (EsdeCommand.Extra e : spec.extras) {
            switch (e.type) {
                case EsdeCommand.EXTRA_BOOL:
                    intent.putExtra(e.key, e.bool());
                    break;
                case EsdeCommand.EXTRA_INT: {
                    Integer v = e.integer();
                    if (v != null) {
                        intent.putExtra(e.key, v.intValue());
                    }
                    break;
                }
                case EsdeCommand.EXTRA_ARRAY:
                    intent.putExtra(e.key, e.array);
                    break;
                default:
                    intent.putExtra(e.key, e.value);
                    break;
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (spec.clearTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        }
        if (spec.clearTop) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        }
        if (spec.noHistory) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY);
        }
        if (spec.usesProvider && env.provider != null) {
            // Access to just this file, for this launch
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            intent.setClipData(ClipData.newRawUri("", env.provider));
        }
        try {
            activity.startActivity(intent);
            return new Result(OK, null, null);
        } catch (ActivityNotFoundException e) {
            forget();
            return new Result(NOT_INSTALLED, packageToGet(cfg, cmd), cmd.emulator);
        } catch (RuntimeException e) {
            return new Result(FAILED, null, String.valueOf(e.getMessage()));
        }
    }
}
