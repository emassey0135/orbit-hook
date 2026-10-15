package dev.emassey0135.orbithook;

import android.util.Log;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Makes sure Orbit's updater restarts the device after an update.
 *
 * <p>The updater asks Orbit's screen reader to restart the device ({@code OlympusApplication.reboot()}). When the update
 * has just replaced the screen reader, the updater's connection to it is gone, the request fails, and the device isn't
 * restarted. Here a failed request is tried again for a while, since the screen reader comes back within seconds, and
 * then the device is restarted through root, which the updater already uses to install updates.
 */
final class UpdaterFix {
    static final String TAG = "OrbitHook.Updater";
    static final String PACKAGE = "com.orbit.updater";
    private static final String APPLICATION = "com.orbit.olympus_sdk.OlympusApplication";
    private static final int TRIES = 5;
    private static final long TRY_INTERVAL_MS = 3_000;

    private UpdaterFix() { }

    static void install(ClassLoader cl) throws ClassNotFoundException, NoSuchMethodException {
        Method reboot = Class.forName(APPLICATION, false, cl).getDeclaredMethod("reboot");
        XposedBridge.hookMethod(reboot, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult())) return;
                Object application = param.thisObject;
                Log.w(TAG, "restart through the screen reader failed; trying again");
                // Report success, as the restart is now certain; the retries run off the caller's thread.
                param.setResult(true);
                new Thread(() -> retry(reboot, application), "updater-restart").start();
            }
        });
        Log.i(TAG, "installed");
    }

    private static void retry(Method reboot, Object application) {
        for (int i = 0; i < TRIES; i++) {
            try {
                Thread.sleep(TRY_INTERVAL_MS);
                if (Boolean.TRUE.equals(XposedBridge.invokeOriginalMethod(reboot, application, new Object[0]))) {
                    Log.i(TAG, "restarted through the screen reader");
                    return;
                }
            } catch (Throwable t) {
                Log.w(TAG, "restart through the screen reader failed again", t);
            }
        }
        Log.w(TAG, "restarting through root");
        try {
            new ProcessBuilder("su", "-c", "svc power reboot").redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            Log.e(TAG, "restart through root failed", e);
        }
    }
}
