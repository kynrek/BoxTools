package local.boxtools;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

/**
 * Holds Google Play services back for the first 2 minutes after boot, so the home screen (MiniMap) and the car apps
 * get the CPU first. Measured 2026-11-13: Play services used ~50 s of CPU in the first minute after boot (about one
 * of the box's cores), then ~1% of a core. Nothing is switched off: every 3 s each thread of the Play services uid
 * (com.google.android.gms, shared with com.google.android.gsf) gets nice 10 and the background cpuset (cores 0-3),
 * because Android moves them back whenever their process state changes. On this firmware the cpuctl "background"
 * group has the same cpu.shares as foreground, so it wouldn't help; nice does. At 2 minutes after boot each thread
 * still at our values gets its own nice and cpuset back (recorded in RECORD as "tid nice cpuset", newest last).
 * Only runs when Box Tools starts within 2 minutes of boot. Uses its own local adb connection (root adbd).
 * Progress: logcat tag BoxTools, "boot throttle: ...".
 */
final class BootThrottle {
    private static final String TAG = "BoxTools";
    private static final String PKG = "com.google.android.gms";
    private static final long UNTIL_UPTIME_MS = 120_000, EVERY_MS = 3000;
    private static final int NICE = 10;
    private static final String RECORD = "/data/local/tmp/boxtools-boot-throttle";

    private BootThrottle() { }

    static void startIfBooting(Context c) {
        if (SystemClock.elapsedRealtime() >= UNTIL_UPTIME_MS - 10_000) return;
        Context app = c.getApplicationContext();
        Thread t = new Thread(() -> run(app), "boot-throttle");
        t.setPriority(Thread.MAX_PRIORITY);
        t.start();
    }

    private static void run(Context c) {
        LocalAdb adb = null;
        String uid = null;
        int ticks = 0, held = 0;
        boolean cleared = false;
        try {
            while (SystemClock.elapsedRealtime() < UNTIL_UPTIME_MS) {
                try {
                    if (adb == null) adb = LocalAdb.connect(c, ShellBridge.ADB_PORT, 2000);
                    if (uid == null) {
                        String out = adb.exec("cmd package list packages -U " + PKG, 10_000);
                        int i = out.indexOf("uid:");
                        if (i < 0) {
                            Log.i(TAG, "boot throttle: " + PKG + " not installed");
                            return;
                        }
                        uid = out.substring(i + 4).trim().split("[^0-9]")[0];
                    }
                    if (!cleared) {
                        adb.exec("rm -f " + RECORD, 5000);
                        cleared = true;
                        Log.i(TAG, "boot throttle: holding back Google Play services (uid " + uid + ") until "
                                + UNTIL_UPTIME_MS / 1000 + " s after boot");
                    }
                    int n = parseInt(adb.exec(holdScript(uid), 10_000).trim());
                    held += n;
                    if (ticks++ == 0 || n > 20) Log.i(TAG, "boot throttle: held back " + n + " threads");
                } catch (Exception e) {
                    // adbd is often down for ~20 s just after boot; just try again next time
                    if (adb != null) adb.close();
                    adb = null;
                }
                SystemClock.sleep(EVERY_MS);
            }
            if (!cleared) {
                Log.w(TAG, "boot throttle: local adb never answered; nothing was held back");
                return;
            }
            String out = "";
            for (int attempt = 0; attempt < 5; attempt++) {
                try {
                    if (adb == null) adb = LocalAdb.connect(c, ShellBridge.ADB_PORT, 2000);
                    out = adb.exec(restoreScript(), 30_000).trim();
                    break;
                } catch (Exception e) {
                    if (adb != null) adb.close();
                    adb = null;
                    SystemClock.sleep(2000);
                }
            }
            Log.i(TAG, "boot throttle: done at " + SystemClock.elapsedRealtime() / 1000 + " s after boot; "
                    + held + " thread changes over " + ticks + " checks; put back " + parseInt(out) + " threads");
        } finally {
            if (adb != null) adb.close();
        }
    }

    /**
     * Shell (mksh, mostly builtins: forks are dear at boot) that gives every thread of the uid nice 10 and the
     * background cpuset, records what it had, and prints how many threads it changed. Field 19 of stat is nice;
     * the comm in parentheses can contain spaces, so it's cut off first (state is then $1, nice $17).
     */
    private static String holdScript(String uid) {
        // renice is the only fork per thread otherwise, so threads are grouped by the increment they need
        // (a handful of values) and reniced together: the first pass over ~170 threads took 4 s one by one.
        return "n=0; L=; F=" + RECORD + "; for d in /sys/fs/cgroup/uid_" + uid + "/pid_*; do p=${d##*pid_};"
                + " for td in /proc/$p/task/*; do t=${td##*/};"
                + "  [ -r $td/stat ] || continue; read -r s < $td/stat; set -- ${s##*) }; ni=${17};"
                + "  cs=?; [ -r $td/cpuset ] && read -r cs < $td/cpuset;"
                + "  [ \"$ni\" -ge " + NICE + " ] && [ \"$cs\" = /background ] && continue;"
                + "  echo \"$t $ni $cs\" >> $F;"
                + "  if [ \"$ni\" -lt " + NICE + " ]; then i=$((" + NICE + " - ni)); eval \"G$i=\\\"\\$G$i $t\\\"\";"
                + "   case \" $L \" in *\" $i \"*) ;; *) L=\"$L $i\";; esac; fi;"
                + "  [ \"$cs\" != /background ] && echo $t > /dev/cpuset/background/tasks 2>/dev/null;"
                + "  n=$((n + 1));"
                + " done; done; for i in $L; do eval \"renice -n $i -p \\$G$i\" >/dev/null 2>&1; done; echo $n";
    }

    /** Puts each recorded thread back (newest record first), if it's still at our values. Prints how many. */
    private static String restoreScript() {
        return "n=0; [ -f " + RECORD + " ] && tac " + RECORD + " | { while read -r t ni cs; do"
                + "  [ -r /proc/$t/stat ] || continue; read -r s < /proc/$t/stat; set -- ${s##*) }; cur=${17};"
                + "  ccs=?; [ -r /proc/$t/cpuset ] && read -r ccs < /proc/$t/cpuset; did=0;"
                + "  if [ \"$cur\" = " + NICE + " ] && [ \"$ni\" != " + NICE + " ]; then"
                + "   renice -n $((ni - " + NICE + ")) -p $t >/dev/null 2>&1; did=1; fi;"
                + "  if [ \"$ccs\" = /background ] && [ \"$cs\" != /background ] && [ -d /dev/cpuset$cs ]; then"
                + "   echo $t > /dev/cpuset$cs/tasks 2>/dev/null; did=1; fi;"
                + "  n=$((n + did));"
                + " done; echo $n; }; rm -f " + RECORD;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim().split("\\s+")[0]);
        } catch (Exception e) {
            return -1;
        }
    }
}
