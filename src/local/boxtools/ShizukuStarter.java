package local.boxtools;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * Starts Shizuku after every boot. Shizuku's own boot start needs Wireless debugging, which needs Wi-Fi, and
 * the box often has none in the car. Instead run Shizuku's starter through the box's own adbd (LocalAdb),
 * the same way it's started from a computer.
 */
final class ShizukuStarter {
    private static final String TAG = "BoxTools";
    private static final String SHIZUKU = "moe.shizuku.privileged.api";
    private static final int ADB_PORT = 5555;
    private static final long GIVE_UP_MS = 3 * 60_000;   // adbd may not be listening yet right after boot
    private static final long RETRY_MS = 5000;
    private static final long AUTH_WAIT_MS = 2 * 60_000;   // time to answer "Allow USB debugging?"
    private static final long EARLY_PERIOD_MS = 10 * 60_000, EARLY_CHECK_MS = 60_000;   // since boot
    private static final long LATE_CHECK_MS = 5 * 60_000;

    private static boolean running;

    private ShizukuStarter() { }

    /** Start Shizuku if needed, then keep it running. Runs until this process ends. */
    static synchronized void startInBackground(Context c) {
        if (running) return;
        running = true;
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                start(app);
            } finally {
                synchronized (ShizukuStarter.class) { running = false; }
            }
        }, "shizuku-start").start();
    }

    private static void start(Context c) {
        String starter;
        try {
            starter = c.getPackageManager().getApplicationInfo(SHIZUKU, 0).nativeLibraryDir + "/libshizuku.so";
        } catch (PackageManager.NameNotFoundException e) {
            return;   // Shizuku isn't installed
        }
        // Keep checking: a server started early in boot has died a minute or so later.
        while (ensure(c, starter)) {
            SystemClock.sleep(SystemClock.elapsedRealtime() < EARLY_PERIOD_MS ? EARLY_CHECK_MS : LATE_CHECK_MS);
        }
    }

    /** Start Shizuku unless it's running. Returns false when there's no point checking again. */
    private static boolean ensure(Context c, String starter) {
        long until = SystemClock.elapsedRealtime() + GIVE_UP_MS;
        while (true) {
            LocalAdb adb = null;
            try {
                adb = LocalAdb.connect(c, ADB_PORT, AUTH_WAIT_MS);
                // Starting it again while it's running would restart it and drop every app's connection.
                String out = adb.exec("pidof shizuku_server >/dev/null && echo running || " + starter, 30_000).trim();
                if (!out.equals("running")) Log.i(TAG, "Shizuku: " + out.replace('\n', ' '));
                return true;
            } catch (LocalAdb.Unauthorized e) {
                Log.w(TAG, "Shizuku start: USB debugging wasn't allowed for Box Tools");
                return false;
            } catch (Exception e) {
                if (SystemClock.elapsedRealtime() > until) {
                    Log.w(TAG, "Shizuku start failed", e);
                    return true;   // try again at the next check
                }
                Log.i(TAG, "Shizuku start: " + e + "; trying again");
                SystemClock.sleep(RETRY_MS);
            } finally {
                if (adb != null) adb.close();
            }
        }
    }
}
