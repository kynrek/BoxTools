package local.boxtools;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Box Tools' own Recent apps screen (swipe up from the bottom and pause), so the vendor launcher, which drew
 * Android's, isn't needed. Built for speed:
 * <ul>
 *   <li>It's an accessibility overlay created once and kept hidden; showing it only makes it visible.</li>
 *   <li>The list is refreshed when a finger lands on the bottom edge ({@link #prepare}), from
 *       {@code dumpsys activity recents} over Box Tools' open adbd connection (~0.1 s), well before the pause that
 *       opens the screen (350 ms).</li>
 *   <li>The app being left is screenshotted at that moment with the accessibility screenshot API and cropped to
 *       its window; other apps keep the screenshot from the last time they were left. Apps with none yet (after a
 *       boot) get Android's own task snapshots once, in the background, through {@link TaskWindow} (~1.5 s).</li>
 *   <li>Switching starts the task's own launch intent from here (no shell); closing runs
 *       {@code cmd activity stack remove} in the background.</li>
 * </ul>
 */
final class RecentsOverlay {
    private static final String TAG = "BoxTools";
    private static final int MAX = 12;
    private static final int CARD_DP = 300, THUMB_H_DP = 122, GAP_DP = 14;
    private static final int BG = 0xF20B0F12, CARD = 0xFF1B2428, TEXT = 0xFFE8EEF0, DIM = 0xFF9AA8AD;

    static final class Task {
        final int id;
        final ComponentName component;
        final Intent intent;
        final long lastActive;

        Task(int id, ComponentName component, Intent intent, long lastActive) {
            this.id = id;
            this.component = component;
            this.intent = intent;
            this.lastActive = lastActive;
        }

        String pkg() { return component.getPackageName(); }
    }

    private final AccessibilityService service;
    private final WindowManager wm;
    private final PackageManager pm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final FrameLayout root;
    private final LinearLayout row;
    private final HorizontalScrollView scroll;
    private final TextView empty;
    private final Map<String, Bitmap> thumbs = Collections.synchronizedMap(new HashMap<>());   // by package
    private final Map<String, Drawable> icons = new HashMap<>();
    private final Set<String> excluded = new HashSet<>();
    private volatile List<Task> tasks = new ArrayList<>();
    private boolean shown;
    private boolean backfilled;   // Android's task snapshots fetched once this boot

    RecentsOverlay(AccessibilityService service, WindowManager wm) {
        this.service = service;
        this.wm = wm;
        this.pm = service.getPackageManager();

        root = new FrameLayout(service);
        root.setBackgroundColor(BG);
        root.setOnClickListener(v -> hide());   // a tap beside the cards goes back to the app

        LinearLayout column = new LinearLayout(service);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout header = new LinearLayout(service);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(20), dp(10), dp(12), 0);
        TextView title = text("Recent apps", 20, TEXT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView clear = text("Clear all", 16, TEXT);
        clear.setPadding(dp(16), dp(8), dp(16), dp(8));
        clear.setBackground(rounded(CARD, 18));
        clear.setOnClickListener(v -> clearAll());
        header.addView(clear);
        column.addView(header);

        scroll = new HorizontalScrollView(service);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        row = new LinearLayout(service);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(GAP_DP), 0, dp(GAP_DP), 0);
        row.setOnClickListener(v -> hide());
        scroll.addView(row, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(column);

        empty = text("No recent apps", 18, DIM);
        empty.setGravity(Gravity.CENTER);
        root.addView(empty, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("Box Tools recents");
        root.setVisibility(View.GONE);   // a GONE window takes no touches and draws nothing
        wm.addView(root, lp);
        loadExcluded();   // the list is first read when a finger lands on the bottom edge (adbd isn't up at boot)
    }

    boolean isShown() { return shown; }

    /** A finger landed on the bottom edge: refresh the list and screenshot the app being left, in case. */
    void prepare() {
        if (shown) return;
        captureCurrentApp();
        bg.execute(this::refreshList);
    }

    void show() {
        if (shown) return;
        shown = true;
        rebuild();
        scroll.scrollTo(0, 0);
        root.setVisibility(View.VISIBLE);
        bg.execute(() -> {
            refreshList();
            main.post(() -> { if (shown) rebuild(); });
            backfillThumbs();
        });
    }

    void hide() {
        if (!shown) return;
        shown = false;
        root.setVisibility(View.GONE);
    }

    void destroy() {
        try { wm.removeView(root); } catch (RuntimeException ignored) { }
        bg.shutdownNow();
    }

    // ---- data ----

    private void loadExcluded() {
        excluded.add(service.getPackageName());
        excluded.add("com.android.systemui");
        excluded.add("android");
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : pm.queryIntentActivities(home, PackageManager.MATCH_ALL)) {
            if (r.activityInfo != null) excluded.add(r.activityInfo.packageName);   // home apps aren't "recent apps"
        }
    }

    private static final Pattern RECENT = Pattern.compile("^\\s*\\* Recent #\\d+: Task\\{\\S+ #(\\d+) ");
    private static final Pattern INTENT = Pattern.compile("^\\s*intent=\\{(.*)\\}\\s*$");
    private static final Pattern COMPONENT = Pattern.compile("^\\s*mActivityComponent=(\\S+)");
    private static final Pattern TYPE = Pattern.compile("activityType=(\\d+)");
    private static final Pattern LAST = Pattern.compile("^\\s*lastActiveTime=(\\d+)");

    /** Recent tasks, newest first, one per app, from dumpsys (fast: no app_process). */
    private void refreshList() {
        String out;
        try {
            out = ShellBridge.exec(service, "dumpsys activity recents", 4000);
        } catch (Exception e) {
            Log.w(TAG, "recents: couldn't list tasks: " + e);
            return;
        }
        List<Task> all = new ArrayList<>();
        int id = -1, type = 0;
        ComponentName comp = null;
        Intent intent = null;
        boolean available = true;
        long last = 0;
        String[] lines = out.split("\n");
        for (int n = 0; n <= lines.length; n++) {
            String line = n < lines.length ? lines[n] : null;
            Matcher m = line == null ? null : RECENT.matcher(line);
            if (line == null || m.find()) {   // a new task starts (or the end): keep the previous one
                if (id >= 0 && comp != null && available && type != 2 && type != 3) {   // not home or recents
                    all.add(new Task(id, comp, intent != null ? intent : launchIntent(comp), last));
                }
                if (line == null) break;
                id = Integer.parseInt(m.group(1));
                type = 0; comp = null; intent = null; available = true; last = 0;
                continue;
            }
            if ((m = COMPONENT.matcher(line)).find()) comp = ComponentName.unflattenFromString(m.group(1));
            else if ((m = INTENT.matcher(line)).find()) intent = parseIntent(m.group(1));
            else if ((m = LAST.matcher(line)).find()) last = Long.parseLong(m.group(1));
            if ((m = TYPE.matcher(line)).find()) type = Integer.parseInt(m.group(1));
            if (line.contains("isAvailable=false")) available = false;
        }
        Collections.sort(all, (a, b) -> Long.compare(b.lastActive, a.lastActive));
        List<Task> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Task t : all) {
            if (excluded.contains(t.pkg()) || !seen.add(t.pkg())) continue;
            if (t.intent.getComponent() == null) t.intent.setComponent(t.component);
            list.add(t);
            if (list.size() == MAX) break;
        }
        tasks = list;
    }

    /** "act=... cat=[...] flg=... cmp=pkg/.Cls ..." from dumpsys; extras aren't shown, so they're not kept. */
    private static Intent parseIntent(String s) {
        Intent i = new Intent();
        Matcher m = Pattern.compile("act=(\\S+)").matcher(s);
        if (m.find()) i.setAction(m.group(1));
        m = Pattern.compile("cat=\\[([^\\]]*)\\]").matcher(s);
        if (m.find()) for (String c : m.group(1).split(",")) if (!c.trim().isEmpty()) i.addCategory(c.trim());
        m = Pattern.compile("cmp=(\\S+)").matcher(s);
        if (m.find()) i.setComponent(ComponentName.unflattenFromString(m.group(1).replace("}", "")));
        if (i.getAction() == null) i.setAction(Intent.ACTION_MAIN);
        return i;
    }

    private static Intent launchIntent(ComponentName c) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(c);
    }

    /** Screenshot the app in front, cropped to its window, for its card. */
    private void captureCurrentApp() {
        String pkg = null;
        Rect bounds = new Rect();
        try {
            for (AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive()) continue;
                AccessibilityNodeInfo r = w.getRoot();
                if (r != null && r.getPackageName() != null) pkg = r.getPackageName().toString();
                w.getBoundsInScreen(bounds);
                break;
            }
        } catch (RuntimeException e) {
            return;
        }
        if (pkg == null || excluded.contains(pkg) || bounds.isEmpty()) return;
        final String app = pkg;
        final Rect crop = new Rect(bounds);
        service.takeScreenshot(Display.DEFAULT_DISPLAY, bg, new AccessibilityService.TakeScreenshotCallback() {
            @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                try {
                    Bitmap hw = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace());
                    if (hw == null) return;
                    Bitmap full = hw.copy(Bitmap.Config.ARGB_8888, false);
                    result.getHardwareBuffer().close();
                    Rect r = new Rect(crop);
                    if (!r.intersect(0, 0, full.getWidth(), full.getHeight())) return;
                    Bitmap cut = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height());
                    thumbs.put(app, scaleForCard(cut));
                } catch (RuntimeException e) {
                    Log.w(TAG, "recents: screenshot failed", e);
                }
            }
            @Override public void onFailure(int errorCode) { }
        });
    }

    /** Apps with no screenshot yet (after a boot): Android's own task snapshots, once, in the background. */
    private void backfillThumbs() {
        if (backfilled) return;
        boolean missing = false;
        for (Task t : tasks) if (!thumbs.containsKey(t.pkg())) missing = true;
        if (!missing) return;
        backfilled = true;
        try {
            String out = ShellBridge.taskWindow(service, "recents " + (MAX * 2) + " " + dp(CARD_DP));
            Map<Integer, String> pkgOf = new HashMap<>();
            for (String line : out.split("\n")) {
                String[] f = line.split("\t");
                if (f.length >= 3 && f[0].equals("task")) {
                    ComponentName c = ComponentName.unflattenFromString(f[2]);
                    if (c != null) pkgOf.put(Integer.parseInt(f[1]), c.getPackageName());
                } else if (f.length >= 3 && f[0].equals("thumb")) {
                    String p = pkgOf.get(Integer.parseInt(f[1]));
                    if (p == null || thumbs.containsKey(p)) continue;   // a fresher screenshot wins
                    byte[] jpg = Base64.decode(f[2], Base64.DEFAULT);
                    Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                    if (b != null) thumbs.put(p, b);
                }
            }
            main.post(() -> { if (shown) rebuild(); });
        } catch (Exception e) {
            Log.w(TAG, "recents: couldn't get task snapshots", e);
        }
    }

    private Bitmap scaleForCard(Bitmap b) {
        int w = dp(CARD_DP);
        int h = Math.max(1, Math.round(b.getHeight() * (w / (float) b.getWidth())));
        return Bitmap.createScaledBitmap(b, w, h, true);
    }

    // ---- actions ----

    private void open(Task t) {
        hide();
        try {
            service.startActivity(new Intent(t.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException e) {
            Log.w(TAG, "recents: launch intent failed for " + t.component + "; restoring the task instead", e);
            bg.execute(() -> {
                try { ShellBridge.taskWindow(service, "open " + t.id); } catch (Exception ex) { Log.w(TAG, "recents: open failed", ex); }
            });
        }
    }

    private void remove(Task t) {
        List<Task> list = new ArrayList<>(tasks);
        list.remove(t);
        tasks = list;
        thumbs.remove(t.pkg());
        bg.execute(() -> closeTask(t.id));
        if (list.isEmpty()) {
            hide();
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    }

    private void clearAll() {
        List<Task> list = tasks;
        tasks = new ArrayList<>();
        thumbs.clear();
        hide();
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        bg.execute(() -> { for (Task t : list) closeTask(t.id); });
    }

    private void closeTask(int id) {
        try {
            ShellBridge.exec(service, "cmd activity stack remove " + id, 4000);
        } catch (Exception e) {
            Log.w(TAG, "recents: couldn't close task " + id, e);
        }
    }

    // ---- views ----

    private void rebuild() {
        row.removeAllViews();
        List<Task> list = tasks;
        empty.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        for (Task t : list) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_DP), ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(GAP_DP);
            row.addView(card(t), lp);
        }
    }

    private View card(Task t) {
        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        FrameLayout picture = new FrameLayout(service);
        picture.setBackground(rounded(CARD, 12));
        picture.setClipToOutline(true);
        Bitmap thumb = thumbs.get(t.pkg());
        ImageView image = new ImageView(service);
        if (thumb != null) {
            image.setImageBitmap(thumb);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            picture.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            image.setImageDrawable(icon(t.component));
            picture.addView(image, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        }
        TextView close = text("✕", 18, TEXT);
        close.setGravity(Gravity.CENTER);
        close.setBackground(rounded(0x99000000, 16));
        close.setOnClickListener(v -> { remove(t); if (shown) rebuild(); });
        FrameLayout.LayoutParams cp = new FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP | Gravity.END);
        cp.setMargins(0, dp(6), dp(6), 0);
        picture.addView(close, cp);
        card.addView(picture, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(THUMB_H_DP)));

        LinearLayout labelRow = new LinearLayout(service);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        labelRow.setPadding(dp(4), dp(8), dp(4), 0);
        ImageView small = new ImageView(service);
        small.setImageDrawable(icon(t.component));
        labelRow.addView(small, new LinearLayout.LayoutParams(dp(26), dp(26)));
        TextView name = text(label(t.component), 15, TEXT);
        name.setPadding(dp(8), 0, 0, 0);
        labelRow.addView(name);
        card.addView(labelRow);

        card.setOnTouchListener(new CardTouch(t, card));
        return card;
    }

    /** Tap: switch to the app. Drag up: close it (the row still scrolls sideways). */
    private final class CardTouch implements View.OnTouchListener {
        private final Task task;
        private final View card;
        private float downX, downY;
        private boolean dragging;

        CardTouch(Task task, View card) { this.task = task; this.card = card; }

        @Override public boolean onTouch(View v, MotionEvent e) {
            float slop = dp(10);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (!dragging && dy < -slop && Math.abs(dy) > Math.abs(dx)) {
                        dragging = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        card.setTranslationY(Math.min(0, dy));
                        card.setAlpha(1f - Math.min(1f, -dy / dp(200)));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (dragging && -dy > dp(90)) {
                        card.animate().translationY(-dp(400)).alpha(0).setDuration(120)
                                .withEndAction(() -> { remove(task); if (shown) rebuild(); }).start();
                    } else if (!dragging && Math.hypot(dx, dy) < slop) {
                        open(task);
                    } else {
                        card.animate().translationY(0).alpha(1).setDuration(120).start();
                    }
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    card.animate().translationY(0).alpha(1).setDuration(120).start();
                    return true;
                default:
                    return true;
            }
        }
    }

    private Drawable icon(ComponentName c) {
        Drawable d = icons.get(c.getPackageName());
        if (d == null) {
            try {
                d = pm.getActivityIcon(c);
            } catch (PackageManager.NameNotFoundException e) {
                try { d = pm.getApplicationIcon(c.getPackageName()); } catch (PackageManager.NameNotFoundException e2) { d = pm.getDefaultActivityIcon(); }
            }
            icons.put(c.getPackageName(), d);
        }
        return d.getConstantState() != null ? d.getConstantState().newDrawable() : d;
    }

    private String label(ComponentName c) {
        try {
            return pm.getApplicationInfo(c.getPackageName(), 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return c.getPackageName();
        }
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(service);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int v) {
        return Math.round(v * service.getResources().getDisplayMetrics().density);
    }
}
