package local.boxtools;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

/**
 * Lets MiniCarHome (signed with the same key) and MiniMap (its certificate is in res/values/arrays.xml), which
 * hold {@code local.boxtools.permission.CONTROL}, run commands
 * as the shell user, to see and arrange other apps' windows, which Android otherwise doesn't allow an app.
 * Called with {@code ContentResolver.call(content://local.boxtools.shell, method, arg, extras)}; calls block,
 * so make them off the main thread.
 *
 * <pre>
 *   exec        arg: a shell command                    returns "out": its output
 *   windowMode  extras: taskId, mode, bounds (a Rect)   returns "out": "ok" or "error: ..."
 *   removeTask  extras: taskId                          returns "out": "ok" or "error: ..."
 * </pre>
 * Any failure to reach the box's adbd returns "error" instead of "out".
 */
public final class ShellProvider extends ContentProvider {
    private static final String TAG = "BoxTools";
    static final String PERMISSION = "local.boxtools.permission.CONTROL";
    private static final long EXEC_TIMEOUT_MS = 15_000;

    @Override public boolean onCreate() { return true; }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        // Provider permissions don't cover call(): check the caller here.
        if (getContext().checkCallingPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("needs " + PERMISSION);
        }
        Bundle result = new Bundle();
        try {
            String out;
            switch (method) {
                case "exec":
                    out = ShellBridge.exec(getContext(), arg, EXEC_TIMEOUT_MS);
                    break;
                case "windowMode": {
                    Rect b = extras.getParcelable("bounds");
                    out = ShellBridge.taskWindow(getContext(), "mode " + extras.getInt("taskId") + " "
                            + extras.getInt("mode") + " " + b.left + " " + b.top + " " + b.right + " " + b.bottom);
                    break;
                }
                case "removeTask":
                    out = ShellBridge.taskWindow(getContext(), "remove " + extras.getInt("taskId"));
                    break;
                default:
                    throw new IllegalArgumentException("unknown method " + method);
            }
            result.putString("out", out);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            Log.w(TAG, method + " failed", e);
            result.putString("error", e.toString());
        }
        return result;
    }

    // Only call() is used.
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
