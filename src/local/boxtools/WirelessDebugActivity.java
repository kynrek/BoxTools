package local.boxtools;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

/**
 * Turns on Wireless debugging (Settings.Global "adb_wifi_enabled").
 * Needs WRITE_SECURE_SETTINGS granted once over ADB; without it, opens Developer options instead.
 */
public class WirelessDebugActivity extends Activity {
    private static final String ADB_WIFI_ENABLED = "adb_wifi_enabled";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Context app = getApplicationContext();
        try {
            boolean wasOn = Settings.Global.getInt(getContentResolver(), ADB_WIFI_ENABLED, 0) == 1;
            Settings.Global.putInt(getContentResolver(), ADB_WIFI_ENABLED, 1);
            String ip = wifiIp(app);
            String msg = (wasOn ? "Wireless debugging already on" : "Wireless debugging turned on")
                    + (ip != null ? " · " + ip : " · not on Wi-Fi");
            Toast.makeText(app, msg, Toast.LENGTH_LONG).show();
        } catch (SecurityException e) {
            Toast.makeText(app, "No permission to switch it directly - opening Developer options", Toast.LENGTH_LONG).show();
            Intent dev = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .setComponent(new ComponentName("com.android.settings",
                            "com.android.settings.Settings$DevelopmentSettingsDashboardActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(dev);
            } catch (RuntimeException ignored) {
            }
        }
        // Stay alive (invisibly) long enough for the toast to show; finishing at once lets the process die first.
        new Handler(Looper.getMainLooper()).postDelayed(this::finish, 3500);
    }

    @SuppressWarnings("deprecation")
    private static String wifiIp(Context c) {
        WifiManager wm = (WifiManager) c.getSystemService(Context.WIFI_SERVICE);
        if (wm == null) return null;
        WifiInfo info = wm.getConnectionInfo();
        int a = info == null ? 0 : info.getIpAddress();
        if (a == 0) return null;
        return (a & 0xff) + "." + ((a >> 8) & 0xff) + "." + ((a >> 16) & 0xff) + "." + ((a >> 24) & 0xff);
    }
}
