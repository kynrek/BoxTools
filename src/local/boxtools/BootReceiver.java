package local.boxtools;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.util.Log;

/**
 * Second way Box Tools brings ADB up at boot, independent of its accessibility service. Android doesn't restart an
 * accessibility service whose process crashed (it turns accessibility off), and that service was the only thing
 * that set sys.setadb.mode=1: one crash meant no ADB, no touch fix and an SD-card recovery (2026-11-06). At every
 * boot this receiver:
 * <ol>
 * <li>asks the firmware for adbd on :5555 (sys.setadb.mode=1) and switches Wireless debugging on;</li>
 * <li>puts NavGestureService back in the enabled accessibility services and turns accessibility on, if a crash
 * took it out, so the service (gestures, touch fix, radio, Wi-Fi) starts again.</li>
 * </ol>
 * MiniMap (the home screen) also sets sys.setadb.mode=1 at every start: three independent paths to ADB.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "BoxTools";
    static final String SERVICE = "local.boxtools/local.boxtools.NavGestureService";

    @Override
    public void onReceive(Context c, Intent intent) {
        Log.i(TAG, "boot receiver: " + intent.getAction());
        AdbSwitch.request(c, "boot receiver");
        restoreAccessibility(c);
    }

    static void restoreAccessibility(Context c) {
        try {
            String services = Settings.Secure.getString(c.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean listed = services != null && (services.contains(SERVICE)
                    || services.contains("local.boxtools/.NavGestureService"));
            if (!listed) {
                String value = services == null || services.isEmpty() ? SERVICE : services + ":" + SERVICE;
                Settings.Secure.putString(c.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        value);
                Log.w(TAG, "accessibility service was off (crash?): switched it back on");
            }
            if (Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 0) != 1) {
                Settings.Secure.putInt(c.getContentResolver(), Settings.Secure.ACCESSIBILITY_ENABLED, 1);
                Log.w(TAG, "accessibility was off: switched it on");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "couldn't restore the accessibility service", e);
        }
    }
}
