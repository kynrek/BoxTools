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
    private static final long WARM_UP_RETRY_MS = 5000;

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

    private static void drop() {
        if (adb != null) adb.close();
        adb = null;
    }

    /**
     * Connect once in the background after boot, so the box's "Allow USB debugging?" prompt (only ever shown the
     * first time) comes up then rather than in the middle of a drive, and the first window change is quick.
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
