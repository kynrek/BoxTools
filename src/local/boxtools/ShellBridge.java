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
    private static final int ADB_PORT = 5555;
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
        try {
            long until = SystemClock.elapsedRealtime() + BOOT_WAIT_MS;   // first, for Android to finish booting
            while (!exec(c, "getprop sys.boot_completed", 5000).trim().equals("1")) {
                if (SystemClock.elapsedRealtime() > until) return;
                SystemClock.sleep(1000);
            }
            until = SystemClock.elapsedRealtime() + TOUCH_GRACE_MS;   // then a moment for windows on their own
            while (SystemClock.elapsedRealtime() < until) {
                if (inputHasDisplay(c)) return;
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

    /** Whether the input dispatcher has any display with windows ("  Display: 0" in dumpsys input). */
    private static boolean inputHasDisplay(Context c) throws Exception {
        return exec(c, "dumpsys input | grep -c '^  Display: '", 10_000).trim().matches("[1-9][0-9]*");
    }

    private static void drop() {
        if (adb != null) adb.close();
        adb = null;
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
                        new Thread(() -> ensureTouch(app), "touch-check").start();
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
