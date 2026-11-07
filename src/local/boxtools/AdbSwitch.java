package local.boxtools;

import android.content.Context;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;

/**
 * Brings the box's adbd up on :5555. On the MMB firmware adbd only listens there (Box Tools' local channel, and
 * ADB from a computer) once sys.setadb.mode=1 is set, which makes init run wifi_adb.sh as root. Also switches
 * Wireless debugging on (Android turns it off at every boot). Safe to call any number of times.
 */
final class AdbSwitch {

    private static final String TAG = "BoxTools";

    private AdbSwitch() { }

    private static boolean watching;
    private static android.database.ContentObserver usbDebugObserver;

    /**
     * Switches USB debugging straight back on whenever anything turns it off. At every boot Android's AdbService
     * resets it from persist.sys.usb.config, which the CarPlay firmware rewrites without "adb" (seen ~6 s after the
     * service starts), and a second later Android turns Wireless debugging off if Wi-Fi isn't connected yet: with
     * both off, AdbService stops adbd and :5555 is gone. Reacting to the change itself closes that race.
     */
    static synchronized void keepUsbDebuggingOn(Context c) {
        if (usbDebugObserver != null) return;
        Context app = c.getApplicationContext();
        android.os.HandlerThread ht = new android.os.HandlerThread("usb-debug-guard");
        ht.start();
        usbDebugObserver = new android.database.ContentObserver(new android.os.Handler(ht.getLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                try {
                    if (Settings.Global.getInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                        Settings.Global.putInt(app.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                        Log.i(TAG, "USB debugging was switched off: switched it back on");
                    }
                } catch (RuntimeException e) {
                    Log.w(TAG, "couldn't switch USB debugging back on", e);
                }
            }
        };
        app.getContentResolver().registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false,
                usbDebugObserver);
        usbDebugObserver.onChange(false);
    }

    /**
     * Every 20 s: adbd running, port 5555 set, a TCP connection to 127.0.0.1:5555 accepted. After two failed checks
     * in a row, sys.setadb.mode 0 -> 1 makes init run wifi_adb.sh again (restarts adbd on :5555). The firmware has
     * rules that stop adbd and never start it again (sys.usb.configxq=accessory), and ADB over Wi-Fi is the only way
     * into the box. MiniMap runs the same check (BoxAdbWatch), so either app alone keeps ADB up.
     */
    static synchronized void startWatch() {
        if (watching) return;
        watching = true;
        Thread t = new Thread(() -> {
            SystemClock.sleep(15_000);
            String last = null;
            int failures = 0;
            while (true) {
                String svc = prop("init.svc.adbd"), port = prop("service.adb.tcp.port");
                boolean accepts = accepts();
                boolean ok = "running".equals(svc) && "5555".equals(port) && accepts;
                String state = (ok ? "ok" : "DOWN") + " adbd=" + svc + " port=" + port + " accepts=" + accepts
                        + " usb=" + prop("sys.usb.config") + " xq=" + prop("sys.usb.configxq");
                if (!state.equals(last)) {
                    Log.i(TAG, "adb watch: " + state);
                    last = state;
                }
                if (usbDebugObserver != null) usbDebugObserver.onChange(false);   // belt and braces
                failures = ok ? 0 : failures + 1;
                if (failures >= 2) {
                    Log.w(TAG, "adb watch: adbd not reachable on :5555 twice, re-running wifi_adb");
                    setProp("sys.setadb.mode", "0");
                    SystemClock.sleep(500);
                    setProp("sys.setadb.mode", "1");
                    failures = 0;
                    last = null;
                    SystemClock.sleep(5000);
                }
                SystemClock.sleep(20_000);
            }
        }, "adb-watch");
        t.setDaemon(true);
        t.start();
    }

    private static boolean accepts() {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", 5555), 1500);
            return true;
        } catch (java.io.IOException e) {
            return false;
        }
    }

    private static String prop(String key) {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, key);
            return v == null ? "" : (String) v;
        } catch (Exception e) {
            return "?";
        }
    }

    private static void setProp(String key, String value) {
        try {
            Class.forName("android.os.SystemProperties").getMethod("set", String.class, String.class)
                    .invoke(null, key, value);
        } catch (Exception e) {
            Log.w(TAG, "couldn't set " + key, e);
        }
    }

    static void request(Context c, String who) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String port = (String) sp.getMethod("get", String.class).invoke(null, "service.adb.tcp.port");
            if (!"5555".equals(port)) {
                sp.getMethod("set", String.class, String.class).invoke(null, "sys.setadb.mode", "1");
                Log.i(TAG, who + ": requested adbd on :5555 (sys.setadb.mode=1; port was '" + port + "')");
            } else {
                Log.i(TAG, who + ": adbd already on :5555");
            }
        } catch (Throwable t) {
            Log.w(TAG, who + ": couldn't request adbd on :5555", t);
        }
        try {
            // USB debugging on: the root cause of every lost-ADB episode (found 2026-11-07). With it off, Android's
            // AdbService stops adbd outright whenever Wireless debugging goes off, which Android does by itself on
            // every Wi-Fi drop / network change and at boot if Wi-Fi isn't connected yet; :5555 dies with adbd.
            // With it on, adbd keeps running (tested: Wireless debugging off, :5555 stayed open).
            if (Settings.Global.getInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1) {
                Settings.Global.putInt(c.getContentResolver(), Settings.Global.ADB_ENABLED, 1);
                Log.i(TAG, who + ": switched USB debugging on (keeps adbd running when Wireless debugging goes off)");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, who + ": couldn't switch USB debugging on", e);
        }
        try {
            if (Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) != 1) {
                Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", 1);
                Log.i(TAG, who + ": switched Wireless debugging on");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, who + ": couldn't switch Wireless debugging on", e);
        }
    }
}
