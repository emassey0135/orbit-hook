package dev.emassey0135.orbithook;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import com.orbit.screenreader.IScreenReaderService;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Makes sure Orbit's updater restarts the device after an update.
 *
 * <p>The updater asks Orbit's screen reader to restart the device ({@code OlympusApplication.reboot()}). When the update
 * has just replaced the screen reader, the updater's connection to it is gone, the request fails, and the device isn't
 * restarted. Here a failed request is made again over a new connection, once the new screen reader is up, so that the
 * restart is still Orbit's own, which tells the keypad's microcontroller first. If that can't be done, the device is
 * restarted through root, which the updater already uses to install updates.
 */
final class UpdaterFix {
    static final String TAG = "OrbitHook.Updater";
    static final String PACKAGE = "com.orbit.updater";
    private static final String APPLICATION = "com.orbit.olympus_sdk.OlympusApplication";
    private static final int TRIES = 10;
    private static final long TRY_INTERVAL_MS = 3_000;

    private UpdaterFix() { }

    static void install(ClassLoader cl) throws ClassNotFoundException, NoSuchMethodException {
        Method reboot = Class.forName(APPLICATION, false, cl).getDeclaredMethod("reboot");
        XposedBridge.hookMethod(reboot, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (Boolean.TRUE.equals(param.getResult())) return;
                Context context = (Context) param.thisObject;
                Log.w(TAG, "restart through the screen reader failed; trying again over a new connection");
                // Report success, as the restart is now certain; the retries run off the caller's thread.
                param.setResult(true);
                new Thread(() -> retry(context), "updater-restart").start();
            }
        });
        Log.i(TAG, "installed");
    }

    private static void retry(Context context) {
        for (int i = 0; i < TRIES; i++) {
            if (rebootThroughScreenReader(context)) {
                Log.i(TAG, "restarted through the screen reader");
                return;
            }
            try {
                Thread.sleep(TRY_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
        Log.w(TAG, "restarting through root");
        try {
            new ProcessBuilder("su", "-c", "svc power reboot").redirectErrorStream(true).start().waitFor();
        } catch (Exception e) {
            Log.e(TAG, "restart through root failed", e);
        }
    }

    /** Asks Orbit's screen reader to restart the device over a new connection; false if it can't be reached. */
    private static boolean rebootThroughScreenReader(Context context) {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] asked = {false};
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                try {
                    IScreenReaderService.Stub.asInterface(binder).reboot();
                    asked[0] = true;
                } catch (Exception e) {
                    Log.w(TAG, "screen reader refused the restart", e);
                }
                done.countDown();
            }
            @Override public void onServiceDisconnected(ComponentName name) { }
        };
        try {
            if (!context.bindService(OrbitScreenReader.bindIntent(), connection, Context.BIND_AUTO_CREATE)) return false;
            done.await(TRY_INTERVAL_MS, TimeUnit.MILLISECONDS);
            return asked[0];
        } catch (Exception e) {
            return false;
        } finally {
            try { context.unbindService(connection); } catch (Exception ignored) { }
        }
    }
}
