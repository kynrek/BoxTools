package local.boxtools;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Switches the MMB box's vendor launcher (com.android.launcher3) off after every boot. It can't be removed: the
 * firmware's TinkerInstall reinstalls and re-enables it while Android starts. Once Android has finished booting,
 * Box Tools disables it again (its process stops until the next boot), takes back runtime permissions it has no
 * use for here, and makes sure the remembered home screen is still the default. Box Tools draws Recent apps itself
 * ({@link RecentsOverlay}), and the home screen is MiniMap, so nothing needs the vendor launcher.
 * Undo for a boot: {@code adb shell pm enable com.android.launcher3}. Not needed while the stand-in from
 * {@code launcher3-stub/} is installed over it.
 */
final class VendorLauncher {
    private static final String TAG = "BoxTools";
    static final String PKG = "com.android.launcher3";
    private static final String[] PERMISSIONS = {
            "android.permission.ACCESS_BACKGROUND_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION", "android.permission.BLUETOOTH_SCAN",
            "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_ADVERTISE",
            "android.permission.CALL_PHONE", "android.permission.READ_PHONE_STATE",
            "android.permission.POST_NOTIFICATIONS", "android.permission.READ_MEDIA_AUDIO",
            "android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO",
            "android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
    };

    private VendorLauncher() { }

    /** Call after sys.boot_completed, off the main thread. */
    static void switchOff(Context c) {
        try {
            // Our stand-in (launcher3-stub/, signed with the same public AOSP key) replaces it as an update; then
            // there's nothing to switch off. This is the fallback if the firmware puts the stock one back.
            String version = ShellBridge.exec(c, "dumpsys package " + PKG + " | grep -m1 versionName", 5000);
            if (version.contains("stub")) {
                keepHome(c);
                return;
            }
            StringBuilder cmd = new StringBuilder();
            for (String p : PERMISSIONS) cmd.append("pm revoke ").append(PKG).append(' ').append(p).append(" 2>/dev/null; ");
            ShellBridge.exec(c, cmd.toString(), 15_000);
            String enabled = ShellBridge.exec(c, "pm list packages -e " + PKG, 5000);
            if (enabled.contains("package:" + PKG)) {
                String out = ShellBridge.exec(c, "pm disable-user --user 0 " + PKG, 10_000).trim();
                Log.i(TAG, "vendor launcher: " + out);
            }
            keepHome(c);
        } catch (Exception e) {
            Log.w(TAG, "couldn't switch the vendor launcher off", e);
        }
    }

    /** Disabling or re-enabling a home app can make Android forget the chosen home screen; set it back. */
    private static void keepHome(Context c) throws Exception {
        SharedPreferences prefs = c.getSharedPreferences("home_keeper", Context.MODE_PRIVATE);
        String home = prefs.getString("home", null);
        if (home == null || home.startsWith(PKG + "/")) return;
        String now = ShellBridge.exec(c,
                "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME", 5000);
        if (!now.contains(home)) {
            String out = ShellBridge.exec(c, "cmd package set-home-activity " + home, 5000).trim();
            Log.i(TAG, "home screen set back to " + home + ": " + out);
        }
    }
}
