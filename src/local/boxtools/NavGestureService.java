package local.boxtools;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Path;
import android.graphics.Rect;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Edge-swipe navigation to replace the vendor floating ball:
 *   swipe in from the left or right edge -> Back
 *   swipe up from the bottom edge        -> Home
 *   swipe up from the bottom and pause   -> Recent apps
 * Thin invisible accessibility overlays catch the swipes; everything else passes through to apps.
 * Recent apps is Box Tools' own screen ({@link RecentsOverlay}), not Android's (drawn by the vendor launcher).
 */
public class NavGestureService extends AccessibilityService {
    private static final int EDGE_DP = 14;         // touch strip thickness
    private static final int TRIGGER_DP = 36;      // distance the finger must travel
    private static final long HOLD_MS = 350;       // pause before release that turns Home into Recents
    private static final int HOLD_SLOP_DP = 10;    // movement allowed while pausing

    private WindowManager wm;
    private final View[] strips = new View[3];
    private RecentsOverlay recents;

    /**
     * MiniCarHome sends this (signature permission) with a package name to close that app's freeform window,
     * e.g. before reopening Maps at a new size: find the window, then tap its caption's close button.
     */
    private static final String TAG = "BoxTools";
    static final String ACTION_CLOSE_WINDOW = "local.boxtools.action.CLOSE_WINDOW";
    private static final int CLOSE_FROM_RIGHT_DP = 23, CLOSE_FROM_TOP_DP = 20;   // measured on the JoyeBox
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            String pkg = i.getStringExtra("package");
            if (pkg == null) return;
            for (AccessibilityWindowInfo w : getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                CharSequence owner = root == null ? null : root.getPackageName();
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                Log.i(TAG, "window type=" + w.getType() + " owner=" + owner + " title=" + w.getTitle() + " bounds=" + r);
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || owner == null || !pkg.contentEquals(owner)) continue;
                float d = getResources().getDisplayMetrics().density;
                float x = r.right - CLOSE_FROM_RIGHT_DP * d, y = r.top + CLOSE_FROM_TOP_DP * d;
                Log.i(TAG, "closing " + pkg + " window: tap " + x + "," + y);
                tap(x, y);
                return;
            }
            Log.w(TAG, "no window found for " + pkg);
        }
    };

    // An app was updated: if it's the home screen, HomeKeeper sets it back (Android forgets the choice).
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            if (i.getData() != null) HomeKeeper.onPackageReplaced(c, i.getData().getSchemeSpecificPart());
        }
    };

    private void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    @Override
    protected void onServiceConnected() {
        CarAudioKeepAwake.startIfEnabled(this);   // runs from boot with this service
        enableWirelessDebugging();   // before the warm-up: on this firmware adbd only listens on :5555 with it
        requestLocalAdb();           // make the firmware bring adbd up on 127.0.0.1:5555 (wifi_adb.sh)
        ShellBridge.warmUpInBackground(this);   // this service starts with every boot
        registerReceiver(closeReceiver, new IntentFilter(ACTION_CLOSE_WINDOW), "local.boxtools.permission.CONTROL", null,
                Context.RECEIVER_EXPORTED);
        IntentFilter replaced = new IntentFilter(Intent.ACTION_PACKAGE_REPLACED);
        replaced.addDataScheme("package");
        registerReceiver(packageReceiver, replaced);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        recents = new RecentsOverlay(this, wm);   // before the strips, so they stay on top of it
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int edge = Math.round(EDGE_DP * dm.density);
        strips[0] = addStrip(Gravity.START | Gravity.CENTER_VERTICAL, edge, WindowManager.LayoutParams.MATCH_PARENT, Edge.LEFT);
        strips[1] = addStrip(Gravity.END | Gravity.CENTER_VERTICAL, edge, WindowManager.LayoutParams.MATCH_PARENT, Edge.RIGHT);
        strips[2] = addStrip(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, WindowManager.LayoutParams.MATCH_PARENT, edge, Edge.BOTTOM);
    }

    /**
     * Android turns Wireless debugging off at every boot, and on the MMB firmware adbd listens on 127.0.0.1:5555
     * (which Box Tools uses for the touch fix, the cellular radio and window control) only while it's on. Shizuku's
     * start-on-boot used to switch it on; without that, nothing works after a reboot. Box Tools holds
     * WRITE_SECURE_SETTINGS, so it switches it on itself.
     */
    /**
     * On the MMB firmware adbd listens on 127.0.0.1:5555 (Box Tools' own channel) only once
     * {@code sys.setadb.mode=1} is set, which makes init run wifi_adb.sh as root (sets service.adb.tcp.port 5555
     * and restarts adbd). Something external used to set this each boot; Box Tools now sets it itself so its
     * local ADB, and therefore the touch fix, the radio and window control, always come up.
     */
    private void requestLocalAdb() {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            if (!"5555".equals(sp.getMethod("get", String.class).invoke(null, "service.adb.tcp.port"))) {
                sp.getMethod("set", String.class, String.class).invoke(null, "sys.setadb.mode", "1");
                Log.i(TAG, "requested adbd on :5555 (sys.setadb.mode=1)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "couldn't request local adb", t);
        }
    }

    private void enableWirelessDebugging() {
        try {
            if (android.provider.Settings.Global.getInt(getContentResolver(), "adb_wifi_enabled", 0) != 1) {
                android.provider.Settings.Global.putInt(getContentResolver(), "adb_wifi_enabled", 1);
                Log.i(TAG, "switched Wireless debugging on");
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "couldn't switch Wireless debugging on", e);
        }
    }

    private enum Edge { LEFT, RIGHT, BOTTOM }

    private View addStrip(int gravity, int width, int height, Edge edge) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(width, height,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = gravity;
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        View v = new View(this);
        v.setOnTouchListener(new SwipeListener(edge));
        wm.addView(v, lp);
        return v;
    }

    private class SwipeListener implements View.OnTouchListener {
        private final Edge edge;
        private float downX, downY, holdX, holdY;
        private long holdSince;
        private boolean armed;

        SwipeListener(Edge edge) { this.edge = edge; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            float d = getResources().getDisplayMetrics().density;
            float x = e.getRawX(), y = e.getRawY();
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = holdX = x; downY = holdY = y;
                    holdSince = SystemClock.uptimeMillis();
                    armed = false;
                    if (edge == Edge.BOTTOM) recents.prepare();   // list + screenshot ready before the pause
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(x - holdX, y - holdY) > HOLD_SLOP_DP * d) {
                        holdX = x; holdY = y; holdSince = SystemClock.uptimeMillis();
                    }
                    armed = travelled(x, y) >= TRIGGER_DP * d;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (armed || travelled(x, y) >= TRIGGER_DP * d) {
                        if (edge == Edge.BOTTOM) {
                            boolean paused = SystemClock.uptimeMillis() - holdSince >= HOLD_MS;
                            if (paused) {
                                recents.show();
                            } else {
                                recents.hide();
                                performGlobalAction(GLOBAL_ACTION_HOME);
                                HomeKeeper.rememberInBackground(NavGestureService.this);
                            }
                        } else if (recents.isShown()) {
                            recents.hide();   // Back closes Recent apps
                        } else {
                            performGlobalAction(GLOBAL_ACTION_BACK);
                        }
                    }
                    return true;
                default:
                    return true;
            }
        }

        private float travelled(float x, float y) {
            switch (edge) {
                case LEFT: return x - downX;
                case RIGHT: return downX - x;
                default: return downY - y;
            }
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { }

    @Override
    public void onInterrupt() { }

    @Override
    public void onDestroy() {
        CarAudioKeepAwake.stop();
        try { unregisterReceiver(closeReceiver); } catch (RuntimeException ignored) { }
        try { unregisterReceiver(packageReceiver); } catch (RuntimeException ignored) { }
        for (View v : strips) {
            if (v != null) try { wm.removeView(v); } catch (RuntimeException ignored) { }
        }
        if (recents != null) recents.destroy();
        super.onDestroy();
    }
}
