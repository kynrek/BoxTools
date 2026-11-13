package local.boxtools;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * Runs commands as the shell user through the box's own adbd (see {@link LocalAdb}), for MiniCarHome's window
 * handling (through {@link ShellProvider}). Keeps one ADB connection open and reconnects when it drops.
 */
final class ShellBridge {
    private static final String TAG = "BoxTools";
    static final int ADB_PORT = 5555;
    private static final long AUTH_WAIT_MS = 2 * 60_000;   // time to answer "Allow USB debugging?"
    private static final long CALL_AUTH_WAIT_MS = 5000;    // a call shouldn't hang on that prompt
    private static final long WARM_UP_GIVE_UP_MS = 3 * 60_000;   // adbd may not be listening yet right after boot
    private static final long WARM_UP_RETRY_MS = 1000;   // adbd starts ~at boot_completed; the radio waits on this
    private static final long BOOT_WAIT_MS = 2 * 60_000;   // touch check: wait for sys.boot_completed at most this
    private static final long TOUCH_GRACE_MS = 1000;        // then give the windows this long to appear on their own
                                                            // (on a good boot they are there at once; an extra call is harmless)

    private static LocalAdb adb;
    private static boolean warming;

    private ShellBridge() { }

    /** Run a shell command and return its output. Throws if the box's adbd can't be reached. */
    static synchronized String exec(Context c, String command, long timeoutMs) throws Exception {
        for (int attempt = 0; ; attempt++) {
            try {
                if (adb == null) adb = LocalAdb.connect(c, ADB_PORT, CALL_AUTH_WAIT_MS);
                return adb.exec(command, timeoutMs);
            } catch (Exception e) {
                drop();
                if (attempt > 0 || e instanceof LocalAdb.Unauthorized) throw e;
                // A kept connection can go stale (adbd restarted, Wi-Fi changed): try once more on a new one.
            }
        }
    }

    /**
     * Run {@link TaskWindow} from this app's own APK as the shell user, e.g. {@code mode 12 6 0 0 833 480}.
     * Returns its output: "ok", or "error: ...".
     */
    static String taskWindow(Context c, String args) throws Exception {
        String apk = c.getApplicationInfo().sourceDir;
        return exec(c, "CLASSPATH=" + apk + " app_process /system/bin " + TaskWindow.class.getName() + " " + args,
                15_000).trim();
    }

    /**
     * Start the cellular radio. The MMB firmware starts its radio daemon (qcrild, through init.class_main.sh)
     * only when sys.qcom.main.start is 1, which it sets only in its car modes (CarPlay, Android Auto, reverse
     * camera). Otherwise there's no LTE, and Android's phone service waits for the radio forever, freezing and
     * restarting about once a minute.
     */
    private static void startRadio(Context c) {
        try {
            String out = exec(c, "[ \"$(getprop sys.qcom.main.start)\" = 1 ] && echo on"
                    + " || { setprop sys.qcom.main.start 1 && echo started; }", 10_000).trim();
            if (!out.equals("on")) Log.i(TAG, "cellular radio: " + out);
        } catch (Exception e) {
            Log.w(TAG, "couldn't start the cellular radio", e);
        }
    }

    /**
     * Make sure touch works after boot. On the MMB box, SurfaceFlinger sometimes never gets the window manager's
     * "boot finished" call (seen on every boot on 2026-11-06, with MiniMap as the home screen; it didn't log
     * "Boot is finished"). Until it does, it never sends the window list to the input dispatcher: the screen
     * draws, but every touch is dropped ("no touchable window"). Sending BOOT_FINISHED (transaction 1) ourselves
     * fixes it; if it already had it, SurfaceFlinger just logs "Extra call to bootFinished".
     */
    private static void ensureTouch(Context c) {
        // Stock Android tells SurfaceFlinger the boot finished the moment the window manager ends the boot animation
        // (event wm_boot_animation_done). This firmware never does, and until then every touch is dropped. Up to
        // 1.28 Box Tools waited for sys.boot_completed plus 1 s: about 2 s after the animation ended on every boot
        // measured (2026-11-13: 1.6-2.3 s). Now a logcat on its own connection waits for that event and the call
        // goes out in the same command, with no polling delay (an extra call is ignored by SurfaceFlinger:
        // "Extra call to bootFinished"). If that fails, the old check below still runs.
        LocalAdb own = null;
        try {
            own = LocalAdb.connect(c, ADB_PORT, CALL_AUTH_WAIT_MS);
            String at = own.exec("logcat -b events -m 1 -s wm_boot_animation_done >/dev/null 2>&1;"
                    + " service call SurfaceFlinger 1 >/dev/null 2>&1; cut -d' ' -f1 /proc/uptime", BOOT_WAIT_MS).trim();
            Log.i(TAG, "boot animation ended: told SurfaceFlinger the boot finished, " + at + " s after boot");
        } catch (Exception e) {
            Log.w(TAG, "couldn't wait for the end of the boot animation; checking touch after boot instead", e);
        } finally {
            if (own != null) own.close();
        }
        try {
            long until = SystemClock.elapsedRealtime() + BOOT_WAIT_MS;   // first, for Android to finish booting
            while (!exec(c, "getprop sys.boot_completed", 5000).trim().equals("1")) {
                if (SystemClock.elapsedRealtime() > until) return;
                SystemClock.sleep(1000);
            }
            until = SystemClock.elapsedRealtime() + TOUCH_GRACE_MS;   // then a moment for windows on their own
            while (SystemClock.elapsedRealtime() < until) {
                if (inputHasDisplay(c)) {
                    Log.i(TAG, "touch works, " + SystemClock.elapsedRealtime() / 1000.0 + " s after boot");
                    return;
                }
                SystemClock.sleep(1000);
            }
            if (inputHasDisplay(c)) return;
            Log.w(TAG, "no touchable windows after boot; telling SurfaceFlinger the boot finished");
            exec(c, "service call SurfaceFlinger 1", 5000);
            SystemClock.sleep(1000);
            Log.i(TAG, "touch " + (inputHasDisplay(c) ? "restored" : "still not working"));
        } catch (Exception e) {
            Log.w(TAG, "touch check failed", e);
        }
    }

    /**
     * If the box isn't on Wi-Fi and a {@code boxtools_wifi.txt} file (SSID on line 1, password on line 2) sits in
     * the root of a mounted SD card, join that network. For recovery when the box can't be reached otherwise; the
     * file holds the password, so nothing secret lives in the app or the repo. Harmless when there's no file.
     */
    private static void recoverWifi(Context c) {
        try {
            // Credentials: remembered in the app the first time, else read from boxtools_wifi.txt on an SD card
            // (and then remembered). So the box rejoins Wi-Fi on every boot with no card after the first recovery.
            android.content.SharedPreferences prefs = c.getSharedPreferences("boxtools", Context.MODE_PRIVATE);
            String ssid = prefs.getString("wifi_ssid", null), pass = prefs.getString("wifi_pass", null);
            if (ssid == null) {
                String file = exec(c, "ls /mnt/media_rw/*/boxtools_wifi.txt 2>/dev/null | head -1", 6000).trim();
                if (file.isEmpty()) return;
                String[] lines = exec(c, "cat " + file, 6000).replace("\r", "").split("\n");
                if (lines.length < 2 || lines[0].trim().isEmpty()) return;
                ssid = lines[0].trim();
                pass = lines[1];
                prefs.edit().putString("wifi_ssid", ssid).putString("wifi_pass", pass).apply();
                Log.i(TAG, "remembered Wi-Fi " + ssid + " for future boots");
            }
            // Join only if the box isn't on any Wi-Fi network: one it joined itself (home, phone hotspot) stays.
            // (Up to 1.19 this read only the first line of "cmd wifi status", "Wifi is enabled", so it never saw
            // a connection and switched the box to the remembered network at every boot.) Retry for a weak signal.
            exec(c, "svc wifi enable", 8000);
            for (int attempt = 0; attempt < 4; attempt++) {
                if (attempt == 0) SystemClock.sleep(15_000);   // give Android's own auto-join a chance first
                String status = exec(c, "cmd wifi status 2>/dev/null", 6000);
                if (status.contains("Wifi is connected to")) {
                    Log.i(TAG, "recoverWifi: already on Wi-Fi, " + firstLineWith(status, "connected to"));
                    return;
                }
                String security = securityOf(c, ssid);
                String out = exec(c, "cmd wifi connect-network " + quote(ssid) + " " + security + " " + quote(pass), 20_000);
                Log.i(TAG, "recoverWifi " + ssid + " (try " + (attempt + 1) + "): " + out.trim());
                SystemClock.sleep(5000);
            }
        } catch (Exception e) {
            Log.w(TAG, "recoverWifi failed", e);
        }
    }

    /**
     * The security to join {@code ssid} with, from the last scan: "wpa3" for a WPA3-only (SAE) network, "wpa2"
     * otherwise. Up to 1.20 this was always "wpa2", which can't join a WPA3-only network (home "ubiquity" is
     * [WPA2-SAE+FT/SAE-CCMP]): the box left its working network for one it could never connect to.
     */
    private static String securityOf(Context c, String ssid) {
        try {
            String scan = exec(c, "cmd wifi list-scan-results 2>/dev/null", 8000);
            boolean sae = false, psk = false;
            for (String line : scan.split("\n")) {
                String t = line.trim();
                if (!(t.endsWith(" " + ssid) || t.contains(" " + ssid + " "))) continue;
                if (t.contains("PSK")) psk = true;
                if (t.contains("SAE")) sae = true;
            }
            return sae && !psk ? "wpa3" : "wpa2";
        } catch (Exception e) {
            return "wpa2";
        }
    }

    private static String firstLineWith(String text, String part) {
        for (String line : text.split("\n")) if (line.contains(part)) return line.trim();
        return "";
    }

    /**
     * Keep every app ahead-of-time compiled for speed. Out of the box most apps run as "verify" (interpreted/JIT)
     * or "speed-profile" (only the hot parts compiled): on this slow SoC that made MiniMap's search 3-5x slower.
     * The compile mode per reason is a pm.dexopt.* property, reset at every boot, so: set the reasons used by
     * installs, updates and Android's background/idle compiling to speed ("inactive" too, which otherwise
     * downgrades apps not used for a while), then, 3 minutes after boot, compile anything not yet at speed, one
     * app at a time on this app's own thread and its own adb connection (a detached shell job didn't survive the
     * exec stream closing; a private connection keeps window control free meanwhile). Without -f an app already
     * at speed is skipped in a moment. Box Tools itself is never touched. Progress: logcat tag BoxTools.
     */
    private static void keepAppsCompiled(Context c) {
        try {
            exec(c, "for r in install install-bulk install-fast bg-dexopt inactive cmdline; do"
                    + " setprop pm.dexopt.$r speed; done", 6000);
        } catch (Exception e) {
            Log.w(TAG, "couldn't set the compile mode", e);
        }
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            SystemClock.sleep(3 * 60_000);
            LocalAdb own = null;
            try {
                own = LocalAdb.connect(app, ADB_PORT, CALL_AUTH_WAIT_MS);
                String[] pkgs = own.exec("pm list packages | cut -d: -f2", 30_000).trim().split("\\s+");
                long start = SystemClock.elapsedRealtime();
                int compiled = 0;
                for (String p : pkgs) {
                    if (p.isEmpty() || p.equals(app.getPackageName())) continue;
                    long t0 = SystemClock.elapsedRealtime();
                    String out = own.exec("cmd package compile -m speed " + p + " 2>&1", 20 * 60_000).trim();
                    long ms = SystemClock.elapsedRealtime() - t0;
                    if (ms > 2000 || !out.equals("Success")) {
                        compiled++;
                        Log.i(TAG, "compiled " + p + " in " + ms + " ms: " + out);
                    }
                }
                Log.i(TAG, "apps: all at speed (" + pkgs.length + " checked, " + compiled + " compiled) in "
                        + (SystemClock.elapsedRealtime() - start) / 1000 + " s");
                // Downloaded code an app loads itself (Play services' modules in app_chimera: Maps core,
                // Mdocstore, ...) isn't part of its APK, so the loop above doesn't compile it: it ran in the JIT.
                // Up to date, this takes ~3 s; after a module update, ~30 s (1.28).
                long t1 = SystemClock.elapsedRealtime();
                own.exec("cmd package compile -m speed --secondary-dex -a >/dev/null 2>&1", 20 * 60_000);
                Log.i(TAG, "apps: downloaded app code (secondary dex) at speed in "
                        + (SystemClock.elapsedRealtime() - t1) / 1000 + " s");
            } catch (Exception e) {
                Log.w(TAG, "background compile stopped", e);
            } finally {
                if (own != null) own.close();
            }
        }, "compile-apps");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
        Log.i(TAG, "apps: compile mode speed; background compile starts in 3 min");
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Whether the input dispatcher has any display with windows ("  Display: 0" in dumpsys input). */
    private static boolean inputHasDisplay(Context c) throws Exception {
        return exec(c, "dumpsys input | grep -c '^  Display: '", 10_000).trim().matches("[1-9][0-9]*");
    }

    private static void drop() {
        if (adb != null) adb.close();
        adb = null;
    }

    /** Waits until 20 s after boot, USB debugging is on, and a command runs over the local adb (2 min at most). */
    private static void waitForAdbSettled(Context c) {
        SystemClock.sleep(20_000);   // called just after sys.boot_completed (ensureTouch waits for it)
        long until = SystemClock.elapsedRealtime() + 60_000;
        while (SystemClock.elapsedRealtime() < until) {
            try {
                if (android.provider.Settings.Global.getInt(c.getContentResolver(),
                        android.provider.Settings.Global.ADB_ENABLED, 0) == 1) break;
            } catch (RuntimeException e) {
                break;
            }
            SystemClock.sleep(2000);
        }
        waitUntilReachable(c, 60_000);
    }

    /** Waits (up to ms) until a command runs over the local adb. */
    private static void waitUntilReachable(Context c, long ms) {
        long until = SystemClock.elapsedRealtime() + ms;
        while (true) {
            try {
                if (exec(c, "echo ok", 5000).contains("ok")) return;
            } catch (Exception e) {
                // adbd not back yet
            }
            if (SystemClock.elapsedRealtime() > until) {
                Log.w(TAG, "after boot: local adb still not reachable, running the boot jobs anyway");
                return;
            }
            SystemClock.sleep(3000);
        }
    }

    /**
     * Connect once in the background after boot, so the box's "Allow USB debugging?" prompt (only ever shown the
     * first time) comes up then rather than in the middle of a drive, and the first window change is quick.
     * Then start the cellular radio (see startRadio).
     */
    static synchronized void warmUpInBackground(Context c) {
        if (warming) return;
        warming = true;
        Context app = c.getApplicationContext();
        BootThrottle.startIfBooting(app);   // only within 2 min of boot
        new Thread(() -> {
            long until = SystemClock.elapsedRealtime() + WARM_UP_GIVE_UP_MS;
            try {
                while (true) {
                    try {
                        LocalAdb fresh = LocalAdb.connect(app, ADB_PORT, AUTH_WAIT_MS);
                        synchronized (ShellBridge.class) {
                            if (adb == null) adb = fresh; else fresh.close();
                        }
                        startRadio(app);
                        HomeKeeper.rememberInBackground(app);
                        new Thread(() -> {
                            ensureTouch(app);   // waits for sys.boot_completed
                            NoAutostart.watchInBackground(app);
                            // adbd can go down for ~20 s just after boot: Android turns USB debugging off, and Box
                            // Tools switches it back on only 10 s after boot (see AdbSwitch.keepUsbDebuggingOn).
                            // The one-shot jobs need adb, so they wait until that's over.
                            waitForAdbSettled(app);
                            VendorLauncher.switchOff(app);
                            recoverWifi(app);
                            keepAppsCompiled(app);
                        }, "after-boot").start();
                        return;
                    } catch (LocalAdb.Unauthorized e) {
                        Log.w(TAG, "USB debugging wasn't allowed for Box Tools; MiniCarHome can't resize windows");
                        return;
                    } catch (Exception e) {
                        if (SystemClock.elapsedRealtime() > until) {
                            Log.w(TAG, "local adb not reachable", e);
                            return;
                        }
                        SystemClock.sleep(WARM_UP_RETRY_MS);
                    }
                }
            } finally {
                synchronized (ShellBridge.class) { warming = false; }
            }
        }, "adb-warm-up").start();
    }
}
