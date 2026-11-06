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
                default:
                    throw new IllegalArgumentException("usage: mode TASK MODE L T R B | remove TASK");
            }
            System.out.println("ok");
        } catch (Throwable t) {
            Throwable cause = t instanceof java.lang.reflect.InvocationTargetException ? t.getCause() : t;
            System.out.println("error: " + cause);
            System.exit(1);
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
