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
 */
public class NavGestureService extends AccessibilityService {
    private static final int EDGE_DP = 14;         // touch strip thickness
    private static final int TRIGGER_DP = 36;      // distance the finger must travel
    private static final long HOLD_MS = 350;       // pause before release that turns Home into Recents
    private static final int HOLD_SLOP_DP = 10;    // movement allowed while pausing

    private WindowManager wm;
    private final View[] strips = new View[3];

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

    private void tap(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        dispatchGesture(new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build(), null, null);
    }

    @Override
    protected void onServiceConnected() {
        ShizukuStarter.startInBackground(this);   // this service starts with every boot
        registerReceiver(closeReceiver, new IntentFilter(ACTION_CLOSE_WINDOW), "local.boxtools.permission.CONTROL", null,
                Context.RECEIVER_EXPORTED);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int edge = Math.round(EDGE_DP * dm.density);
        strips[0] = addStrip(Gravity.START | Gravity.CENTER_VERTICAL, edge, WindowManager.LayoutParams.MATCH_PARENT, Edge.LEFT);
        strips[1] = addStrip(Gravity.END | Gravity.CENTER_VERTICAL, edge, WindowManager.LayoutParams.MATCH_PARENT, Edge.RIGHT);
        strips[2] = addStrip(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, WindowManager.LayoutParams.MATCH_PARENT, edge, Edge.BOTTOM);
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
                            performGlobalAction(paused ? GLOBAL_ACTION_RECENTS : GLOBAL_ACTION_HOME);
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
        try { unregisterReceiver(closeReceiver); } catch (RuntimeException ignored) { }
        for (View v : strips) {
            if (v != null) try { wm.removeView(v); } catch (RuntimeException ignored) { }
        }
        super.onDestroy();
    }
}
