package local.boxtools;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

/**
 * Keeps the chosen home screen. Updating the app that's the home screen (MiniMap, say) makes Android forget the
 * choice and fall back to another home app until someone picks again. Box Tools remembers the home screen (at
 * boot and on every Home swipe) and, when that app is updated, sets it back as the home screen over the box's own
 * adbd, then goes Home if another home app came up meanwhile.
 */
final class HomeKeeper {
    private static final String TAG = "BoxTools";
    private static final String PREFS = "home_keeper", KEY_HOME = "home";
    private static final String RESOLVE_HOME =
            "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME";

    private HomeKeeper() { }

    /** Note the current home screen, unless Android is between choices (its chooser) or it can't be read. */
    static void rememberInBackground(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                String home = lastLine(ShellBridge.exec(app, RESOLVE_HOME, 5000));
                if (!home.contains("/") || home.startsWith("android/")) return;   // no choice made: the chooser
                prefs(app).edit().putString(KEY_HOME, home).apply();
            } catch (Exception e) {
                Log.w(TAG, "couldn't read the home screen", e);
            }
        }, "home-remember").start();
    }

    /** An app was updated: if it's the remembered home screen, make it the home screen again. */
    static void onPackageReplaced(Context c, String pkg) {
        Context app = c.getApplicationContext();
        String home = prefs(app).getString(KEY_HOME, null);
        if (home == null || !home.startsWith(pkg + "/")) return;
        new Thread(() -> {
            try {
                SystemClock.sleep(1000);   // let the update settle
                String out = ShellBridge.exec(app, "cmd package set-home-activity " + home, 5000).trim();
                Log.i(TAG, "home screen " + home + " after update: " + out);
                // Another home app may have come up in the meantime; Home brings up the chosen one.
                String top = ShellBridge.exec(app, "dumpsys activity activities | grep -m1 topResumedActivity", 5000);
                String homes = ShellBridge.exec(app,
                        "cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 5000);
                for (String line : homes.split("\n")) {
                    String candidate = line.trim();
                    if (!candidate.contains("/") || candidate.startsWith(pkg + "/")) continue;
                    String other = candidate.substring(0, candidate.indexOf('/'));
                    if (top.contains(" " + other + "/")) {
                        ShellBridge.exec(app, "input keyevent 3", 5000);
                        break;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "couldn't restore the home screen " + home, e);
            }
        }, "home-restore").start();
    }

    private static String lastLine(String out) {
        String[] lines = out.trim().split("\n");
        return lines.length == 0 ? "" : lines[lines.length - 1].trim();
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
