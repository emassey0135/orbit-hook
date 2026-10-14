package orbithook;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/**
 * Remote mode from any app, and back to that app afterwards.
 *
 * <p>Orbit's Remote Mode app only sends the keypad's microcontroller one command (sendData, command 0x0B) naming the
 * channel; the microcontroller then does all the Bluetooth/USB work and reports the mode back. The user guide's
 * channel shortcuts (Space + Left Arrow + Dot 1/2/3 for Bluetooth channels 1-3, Space + Right Arrow + Dot 4/5 for
 * channels 4-5, Space + Right Arrow + Dot 6 for USB) reach the screen reader as chords nothing handles, so this sends
 * that same command for them, whatever app is in front. Space + Right Arrow + Dot 8 is added for USB Braille-HID, the
 * one Remote Mode menu choice the guide gives no shortcut.
 *
 * <p>When the microcontroller reports remote mode ended (the user pressed OK + Left Arrow), Orbit's screen reader goes
 * to the home screen. This skips that, so the user lands back in the app they left, and drops the stray key releases
 * the microcontroller sends for the exit chord, which would otherwise act in that app.
 */
final class RemoteMode {
    private static final String TAG = Hook.TAG;
    /** Logged only by the screen reader's handler for microcontroller system events, in its remote-mode-ended branch. */
    private static final String EXIT_ANCHOR = "onSystemEvent: Remote disabled redirecting to Home";
    /** That handler's event type for remote mode reports. */
    private static final int EVENT_REMOTE_MODE = 12;
    /** The sendData command the microcontroller takes to enter remote mode. */
    private static final byte CMD_REMOTE_MODE = 0x0B;
    /** Key releases this soon after remote mode ends belong to the exit chord. */
    private static final long STRAY_RELEASE_MS = 600;

    /** Shortcut chord key code -> remote mode payload (as Orbit's Remote Mode app sends it). */
    private static final Map<Integer, byte[]> SHORTCUTS = new HashMap<>();
    static {
        SHORTCUTS.put(3905, new byte[] {3, 1}); // Space + Left Arrow + Dot 1: Bluetooth channel 1
        SHORTCUTS.put(3906, new byte[] {3, 2}); // Space + Left Arrow + Dot 2: Bluetooth channel 2
        SHORTCUTS.put(3908, new byte[] {3, 3}); // Space + Left Arrow + Dot 3: Bluetooth channel 3
        SHORTCUTS.put(3848, new byte[] {3, 4}); // Space + Right Arrow + Dot 4: Bluetooth channel 4
        SHORTCUTS.put(3856, new byte[] {3, 5}); // Space + Right Arrow + Dot 5: Bluetooth channel 5
        SHORTCUTS.put(3872, new byte[] {1});    // Space + Right Arrow + Dot 6: USB (Orbit-HID, the USB default)
        SHORTCUTS.put(3968, new byte[] {4});    // Space + Right Arrow + Dot 8: USB (Braille-HID)
    }

    /** True while the screen reader handles a remote mode report; its home action is skipped then. */
    private static volatile boolean inRemoteReport;
    private static volatile long remoteEndedAt = -STRAY_RELEASE_MS;
    private static volatile Method sendData;

    private RemoteMode() { }

    static void install(DexKitBridge bridge, ClassLoader cl) {
        try {
            MethodDataList found = bridge.findMethod(FindMethod.create().matcher(
                    MethodMatcher.create().usingStrings(EXIT_ANCHOR)));
            if (found.isEmpty()) { Log.w(TAG, "remote mode report handler not found; remote mode returns home"); return; }
            // Look the method up by exact name and parameters: its (obfuscated, merged) class also has methods using
            // types this Android version lacks, so listing all its methods (as getMethodInstance does) fails.
            MethodData md = found.get(0);
            Class<?>[] params = new Class<?>[md.getParamTypeNames().size()];
            for (int i = 0; i < params.length; i++) params[i] = typeOf(md.getParamTypeNames().get(i), cl);
            Method handler = Class.forName(md.getClassName(), false, cl).getDeclaredMethod(md.getName(), params);
            XposedBridge.hookMethod(handler, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    Object type = param.args.length > 0 ? param.args[0] : null;
                    inRemoteReport = type instanceof Integer && (Integer) type == EVENT_REMOTE_MODE;
                }
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    inRemoteReport = false;
                }
            });
            XposedBridge.hookMethod(AccessibilityService.class.getDeclaredMethod("performGlobalAction", int.class),
                    new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            if (!inRemoteReport || (Integer) param.args[0] != AccessibilityService.GLOBAL_ACTION_HOME) return;
                            remoteEndedAt = SystemClock.uptimeMillis();
                            Log.i(TAG, "remote mode ended: staying in the current app");
                            param.setResult(true);
                        }
                    });
            Log.i(TAG, "remote mode return installed on " + handler.getDeclaringClass().getName() + "." + handler.getName());
        } catch (Throwable t) {
            Log.e(TAG, "remote mode return not installed", t);
        }
    }

    /**
     * Called by the keypad router hook before Orbit sees a key event. Returns true if the event was a remote mode
     * shortcut (sent to the microcontroller) or a stray release from leaving remote mode, and must not go further.
     */
    static boolean onKey(Bundle event) {
        int action = event.getInt("action", -1);
        if (action == 1 && SystemClock.uptimeMillis() - remoteEndedAt < STRAY_RELEASE_MS) return true;
        if (action != 3) return false;
        byte[] payload = SHORTCUTS.get(event.getInt("keyCode", -1));
        if (payload == null) return false;
        Context service = Hook.context;
        if (service == null) return false;
        try {
            Method m = sendData(service.getClass());
            if (m == null) { Log.e(TAG, "screen reader sendData not found"); return false; }
            m.invoke(service, CMD_REMOTE_MODE, payload.clone());
            Log.i(TAG, "remote mode shortcut sent: " + payload.length + " byte(s), first " + payload[0]);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "remote mode shortcut failed", t);
            return false;
        }
    }

    private static Class<?> typeOf(String name, ClassLoader cl) throws ClassNotFoundException {
        switch (name) {
            case "int": return int.class;
            case "long": return long.class;
            case "boolean": return boolean.class;
            case "byte": return byte.class;
            case "short": return short.class;
            case "char": return char.class;
            case "float": return float.class;
            case "double": return double.class;
            default: return Class.forName(name, false, cl);
        }
    }

    /** The screen reader service's write-to-microcontroller method: the only {@code void (byte, byte[])} on it. */
    private static Method sendData(Class<?> service) {
        Method cached = sendData;
        if (cached != null) return cached;
        for (Class<?> k = service; k != null && k != AccessibilityService.class; k = k.getSuperclass()) {
            for (Method m : k.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (m.getReturnType() == void.class && p.length == 2 && p[0] == byte.class && p[1] == byte[].class) {
                    m.setAccessible(true);
                    sendData = m;
                    return m;
                }
            }
        }
        return null;
    }
}
