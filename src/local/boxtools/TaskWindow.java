package local.boxtools;

import android.graphics.Rect;
import android.os.IBinder;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Window changes Android only allows the shell user to make, run as a separate command:
 * {@code CLASSPATH=<Box Tools apk> app_process /system/bin local.boxtools.TaskWindow <verb> ...}, started by
 * {@link ShellBridge} through the box's own adbd. Not part of the app: it runs in its own short-lived process,
 * with no Context, and talks to Android's activity task manager directly.
 *
 * <pre>
 *   mode   TASK_ID WINDOWING_MODE LEFT TOP RIGHT BOTTOM   switch a task's windowing mode and set its bounds
 *   remove TASK_ID                                        close a task and its screens
 *   recents MAX THUMB_WIDTH                               list recent tasks, newest first, one per line:
 *                                                         "task<TAB>id<TAB>component<TAB>lastActive<TAB>label", each
 *                                                         followed (THUMB_WIDTH > 0) by "thumb<TAB>id<TAB>base64 JPEG"
 *   open   TASK_ID                                        bring a recent task back (restarting it if needed)
 * </pre>
 * Prints "ok", or "error: ..." and exits with status 1.
 */
public final class TaskWindow {
    private static final int WINDOWING_MODE_FREEFORM = 5;

    private TaskWindow() { }

    public static void main(String[] args) {
        try {
            Object atm = activityTasks();
            switch (args.length > 0 ? args[0] : "") {
                case "mode":
                    setMode(atm, Integer.parseInt(args[1]), Integer.parseInt(args[2]), new Rect(
                            Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                            Integer.parseInt(args[5]), Integer.parseInt(args[6])));
                    break;
                case "remove":
                    atm.getClass().getMethod("removeTask", int.class).invoke(atm, Integer.parseInt(args[1]));
                    break;
                case "recents":
                    recents(atm, Integer.parseInt(args[1]), Integer.parseInt(args[2]));
                    return;
                case "open":
                    atm.getClass().getMethod("startActivityFromRecents", int.class, android.os.Bundle.class)
                            .invoke(atm, Integer.parseInt(args[1]), null);
                    break;
                default:
                    throw new IllegalArgumentException(
                            "usage: mode TASK MODE L T R B | remove TASK | recents MAX THUMB_W | open TASK");
            }
            System.out.println("ok");
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
            System.out.println("error: " + cause);
            System.exit(1);
        }
    }

    /** Recent tasks for the Recent apps screen (see the class comment for the output). */
    private static void recents(Object atm, int max, int thumbWidth) throws Exception {
        Object slice = atm.getClass().getMethod("getRecentTasks", int.class, int.class, int.class)
                .invoke(atm, max, 0x0002 /* RECENT_IGNORE_UNAVAILABLE */, 0);
        List<?> list = (List<?>) slice.getClass().getMethod("getList").invoke(slice);
        Class<?> info = Class.forName("android.app.TaskInfo");
        StringBuilder out = new StringBuilder();
        for (Object t : list) {
            int id = info.getField("taskId").getInt(t);
            android.content.Intent base = (android.content.Intent) info.getField("baseIntent").get(t);
            android.content.ComponentName c = (android.content.ComponentName) info.getField("realActivity").get(t);
            if (c == null && base != null) c = base.getComponent();
            if (c == null) c = (android.content.ComponentName) info.getField("baseActivity").get(t);
            if (c == null) continue;
            long last = info.getField("lastActiveTime").getLong(t);
            String label = "";
            Object td = info.getField("taskDescription").get(t);
            if (td != null) {
                Object l = td.getClass().getMethod("getLabel").invoke(td);
                if (l != null) label = l.toString().replace('\t', ' ').replace('\n', ' ');
            }
            out.append("task\t").append(id).append('\t').append(c.flattenToString()).append('\t').append(last)
                    .append('\t').append(label).append('\n');
            if (thumbWidth > 0) {
                String thumb = thumbnail(atm, id, thumbWidth);
                if (thumb != null) out.append("thumb\t").append(id).append('\t').append(thumb).append('\n');
            }
        }
        System.out.print(out);
    }

    /** The task's last screenshot, scaled to the width, as base64 JPEG; null if there's none. */
    private static String thumbnail(Object atm, int taskId, int width) {
        try {
            Object snap = null;
            for (Method m : atm.getClass().getMethods()) {
                if (!m.getName().equals("getTaskSnapshot")) continue;
                Class<?>[] p = m.getParameterTypes();
                Object[] a = new Object[p.length];
                a[0] = taskId;
                for (int k = 1; k < p.length; k++) a[k] = Boolean.FALSE;   // full resolution; don't take a new one
                snap = m.invoke(atm, a);
                break;
            }
            if (snap == null) return null;
            android.hardware.HardwareBuffer hb = (android.hardware.HardwareBuffer)
                    snap.getClass().getMethod("getHardwareBuffer").invoke(snap);
            if (hb == null) return null;
            android.graphics.ColorSpace cs = (android.graphics.ColorSpace)
                    snap.getClass().getMethod("getColorSpace").invoke(snap);
            android.graphics.Bitmap hw = android.graphics.Bitmap.wrapHardwareBuffer(hb, cs);
            if (hw == null) return null;
            android.graphics.Bitmap sw = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
            int h = Math.max(1, Math.round(sw.getHeight() * (width / (float) sw.getWidth())));
            android.graphics.Bitmap small = android.graphics.Bitmap.createScaledBitmap(sw, width, h, true);
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, bytes);
            hb.close();
            return android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP);
        } catch (Throwable e) {
            return null;
        }
    }

    private static Object activityTasks() throws ReflectiveOperationException {
        IBinder b = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class)
                .invoke(null, "activity_task");
        return Class.forName("android.app.IActivityTaskManager$Stub").getMethod("asInterface", IBinder.class)
                .invoke(null, b);
    }

    /** Set the windowing mode and bounds in one WindowContainerTransaction, then resize if it's freeform. */
    private static void setMode(Object atm, int taskId, int mode, Rect bounds) throws ReflectiveOperationException {
        Object token = null;
        for (Object task : tasks(atm)) {
            if (Class.forName("android.app.TaskInfo").getField("taskId").getInt(task) == taskId) {
                token = Class.forName("android.app.TaskInfo").getField("token").get(task);
                break;
            }
        }
        if (token == null) throw new IllegalStateException("no task " + taskId);
        Class<?> tokenClass = Class.forName("android.window.WindowContainerToken");
        Class<?> wctClass = Class.forName("android.window.WindowContainerTransaction");
        Object wct = wctClass.getConstructor().newInstance();
        wctClass.getMethod("setWindowingMode", tokenClass, int.class).invoke(wct, token, mode);
        wctClass.getMethod("setBounds", tokenClass, Rect.class).invoke(wct, token, bounds);
        Object controller = atm.getClass().getMethod("getWindowOrganizerController").invoke(atm);
        controller.getClass().getMethod("applyTransaction", wctClass).invoke(controller, wct);
        // Switching to freeform keeps the old size and moves it below the status bar; resize it after.
        if (mode == WINDOWING_MODE_FREEFORM) {
            atm.getClass().getMethod("resizeTask", int.class, Rect.class, int.class).invoke(atm, taskId, bounds, 0);
        }
    }

    private static List<?> tasks(Object atm) throws ReflectiveOperationException {
        for (Method m : atm.getClass().getMethods()) {
            if (!m.getName().equals("getTasks")) continue;
            // getTasks(int maxNum, boolean filterOnlyVisibleRecents, boolean keepIntentExtra, int displayId)
            Class<?>[] types = m.getParameterTypes();
            Object[] args = new Object[types.length];
            for (int k = 0; k < types.length; k++) {
                args[k] = types[k] == boolean.class ? (Object) false : (Object) (k == 0 ? 50 : 0);
            }
            return (List<?>) m.invoke(atm, args);
        }
        throw new NoSuchMethodException("getTasks");
    }
}
