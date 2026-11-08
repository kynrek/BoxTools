package local.boxtools;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps apps that have no business running at boot from starting by themselves: after every boot, for a few
 * minutes, any app on the list that's running with no window and nothing playing is force-stopped. Android also
 * marks a force-stopped app "stopped", so it gets no boot broadcasts at later boots either, until it's opened again.
 * Turning off their boot receivers isn't enough: Audible's (WorkManager) switches itself back on whenever the app
 * runs, and the shell may not change another app's components at all.
 * An app the person opened (it has a task) or one that's playing (say the car resumed it) is left alone.
 * Progress: logcat tag BoxTools, "no autostart: ...".
 */
final class NoAutostart {
    private static final String TAG = "BoxTools";
    private static final String[] PACKAGES = {
            "com.audible.application",
            "com.apkpure.aegon",
            "com.plexapp.android",
            "com.geniecompany.AladdinConnect",
            "io.homeassistant.companion.android",
            "com.google.android.youtube",
            "com.netflix.mediaclient",
            "org.videolan.vlc",
            "com.bambuna.podcastaddict",
    };
    private static final long WATCH_MS = 5 * 60_000, EVERY_MS = 15_000;   // boot broadcasts reach apps late

    private NoAutostart() { }

    /** Call after sys.boot_completed. Returns at once; the checks run on their own thread. */
    static void watchInBackground(Context c) {
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> {
            long until = SystemClock.elapsedRealtime() + WATCH_MS;
            while (SystemClock.elapsedRealtime() < until) {
                try {
                    check(app);
                } catch (Exception e) {
                    Log.w(TAG, "no autostart: check failed", e);
                }
                SystemClock.sleep(EVERY_MS);
            }
        }, "no-autostart");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void check(Context c) throws Exception {
        Set<String> running = new HashSet<>();
        for (String name : ShellBridge.exec(c, "ps -A -o NAME", 10_000).split("\n")) {
            String n = name.trim();
            int colon = n.indexOf(':');   // a:service counts as a
            running.add(colon > 0 ? n.substring(0, colon) : n);
        }
        StringBuilder stop = new StringBuilder();
        String tasks = null, media = null;
        for (String p : PACKAGES) {
            if (!running.contains(p)) continue;
            if (tasks == null) {
                tasks = ShellBridge.exec(c, "am stack list", 10_000);
                media = ShellBridge.exec(c, "dumpsys media_session", 10_000);
            }
            if (tasks.contains(" " + p + "/") || playing(media, p)) continue;
            stop.append("am force-stop ").append(p).append("; ");
            Log.i(TAG, "no autostart: stopping " + p + " (started by itself)");
        }
        if (stop.length() > 0) ShellBridge.exec(c, stop.toString(), 15_000);
    }

    /** Whether one of the app's media sessions is playing (PlaybackState 3), from dumpsys media_session. */
    private static boolean playing(String media, String pkg) {
        boolean ours = false;
        for (String line : media.split("\n")) {
            String t = line.trim();
            if (t.startsWith("package=")) ours = t.equals("package=" + pkg);
            else if (ours && t.startsWith("state=PlaybackState {state=3,")) return true;
        }
        return false;
    }
}
