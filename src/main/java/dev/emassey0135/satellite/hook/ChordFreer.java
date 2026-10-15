package dev.emassey0135.satellite.hook;

import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;

/**
 * Gives Backtalk's Orbit braille display ({@link OrbitDisplayService}) the keypad while Backtalk reads the app in front,
 * including the command chords that Orbit's keypad router otherwise swallows.
 *
 * <p>While the app in front is one Backtalk drives ({@link Hook#backtalkForeground}: a third-party app, Backtalk
 * enabled, not self-voicing) and the Orbit display is registered with Orbit's keypad, each key first moves the keypad
 * focus (the router state's focused-package field) to the display, so Orbit's own routing sends it the keys Orbit doesn't
 * keep. Orbit's keypad router handles every combination chord (action 3) inline and returns before the
 * chord reaches an app ("Screen reader combination handled, not routing to apps"), so chords are sent to the display
 * here instead, skipping Orbit's handling. Orbit's own apps and self-voicing apps take the focus back themselves, and
 * for them the flag is false, so Orbit keeps handling their review chords exactly as before.
 *
 * <p>On the Orbit Player (phone-style keypad, codes 1000-1022) the display also gets two keys Orbit never lets apps
 * have: HOME and the press of 0. Without the display registered they stay with Orbit, so HOME can't go dead. The
 * Player's Menu and volume combos stay with Orbit.
 *
 * <p>It also keeps hold of Orbit's keypad registry, so that {@link Hook} can tell whether the Orbit display is
 * registered.
 *
 * <p>The router is an obfuscated synthetic method with no stable name, so it is located with DexKit by a log string
 * that only it contains, as is the method that registers keypad callbacks. Everything read off it afterwards is found by field <em>type</em> (each is unique on its
 * class), not by obfuscated field name, so an Orbit update that only renames members does not break it.
 */
final class ChordFreer {
    private static final String TAG = Hook.TAG;
    private static final String ANCHOR = "handleScreenReaderCombination() called with 111";
    private static final String REGISTER_ANCHOR = "Registered keypad callback for: ";
    /** Orbit's ways of dropping a keypad callback: an app unregistering, and an app's process dying. */
    private static final String[] UNREGISTER_ANCHORS = {"Unregistering keypad callback for package: ", " died, removing"};
    /** Orbit Player keypad codes: digits are 1000-1009, then star, pound, arrows, OK, HOME, MENU, BACK, RECORD, volume. */
    private static final int PLAYER_0 = 1000, PLAYER_HOME = 1017, PLAYER_LAST = 1022;

    /** Cache of the single-Bundle callback method per callback implementation class. */
    private static final ConcurrentHashMap<Class<?>, Method> CALLBACK = new ConcurrentHashMap<>();

    /** Orbit's keypad registry (package name to keypad callback), kept from the first registration. */
    private static volatile ConcurrentHashMap<?, ?> registry;
    /** Whether the Orbit display was registered when last looked at. */
    private static volatile boolean wasRegistered;

    private ChordFreer() { }

    /**
     * Find Orbit's keypad router and hook it so chords reach the Orbit display, and hook keypad registration to keep
     * the registry. Safe to fail: without both, the display is never seen as registered, so Orbit keeps speaking.
     */
    static void install(DexKitBridge bridge, ClassLoader cl) {
        Method router = locate(bridge, cl, "keypad router", ANCHOR, 0);
        if (router == null) { Hook.status("keypad router not found; chord pass-through disabled"); return; }
        Method register = locate(bridge, cl, "keypad registration", REGISTER_ANCHOR, 2);
        if (register == null) { Hook.status("keypad registration not found; chord pass-through disabled"); return; }
        XposedBridge.hookMethod(register, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (registry == null) {
                    try { registry = registryOf(param.thisObject); } catch (Throwable t) { Log.e(TAG, "keypad registry not read", t); }
                }
                registrationMaybeChanged();
            }
        });
        XC_MethodHook dropped = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) { registrationMaybeChanged(); }
        };
        for (String anchor : UNREGISTER_ANCHORS) {
            for (Method m : locateAll(bridge, cl, anchor)) XposedBridge.hookMethod(m, dropped);
        }
        XposedBridge.hookMethod(router, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try { route(param); } catch (Throwable t) { Log.e(TAG, "chord pass-through failed", t); }
            }
        });
        Hook.status("chord pass-through installed on " + router.getDeclaringClass().getName() + "." + router.getName());
    }

    /** Whether the Orbit display is registered with Orbit's keypad. Orbit drops a callback whose process has died. */
    static boolean displayRegistered() {
        ConcurrentHashMap<?, ?> r = registry;
        return r != null && r.containsKey(OrbitDisplayService.KEYPAD_NAME);
    }

    /**
     * When the Orbit display comes or goes, Orbit's screen reader is told again about the window in front. Orbit decides
     * whether to read it, and where its keys go, only when a window appears, so otherwise one shown to it as Orbit's own
     * while Backtalk read it would stay that way after the display went, with nothing reading it and the keys going
     * nowhere.
     */
    private static void registrationMaybeChanged() {
        boolean registered = displayRegistered();
        if (registered == wasRegistered) return;
        wasRegistered = registered;
        Hook.replayForeground();
    }

    /** Every method that uses {@code anchor}, which may be none. */
    private static java.util.List<Method> locateAll(DexKitBridge bridge, ClassLoader cl, String anchor) {
        return find(bridge, cl, "keypad callback removal", MethodMatcher.create().usingStrings(anchor).returnType("void"));
    }

    /** The method with {@code paramCount} parameters that uses {@code anchor}, or null. */
    private static Method locate(DexKitBridge bridge, ClassLoader cl, String what, String anchor, int paramCount) {
        java.util.List<Method> found = find(bridge, cl, what,
                MethodMatcher.create().usingStrings(anchor).returnType("void").paramCount(paramCount));
        return found.isEmpty() ? null : found.get(0);
    }

    private static java.util.List<Method> find(DexKitBridge bridge, ClassLoader cl, String what, MethodMatcher matcher) {
        java.util.List<Method> methods = new java.util.ArrayList<>();
        try {
            for (MethodData md : bridge.findMethod(FindMethod.create().matcher(matcher))) {
                Log.i(TAG, what + " = " + md.getClassName() + "." + md.getName());
                methods.add(md.getMethodInstance(cl));
            }
        } catch (Throwable t) {
            Hook.status("DexKit lookup of " + what + " failed", t);
        }
        return methods;
    }

    /** The keypad router state's registry: its only ConcurrentHashMap field. */
    private static ConcurrentHashMap<?, ?> registryOf(Object owner) throws IllegalAccessException {
        for (Field f : owner.getClass().getDeclaredFields()) {
            if (f.getType() == ConcurrentHashMap.class) { f.setAccessible(true); return (ConcurrentHashMap<?, ?>) f.get(owner); }
        }
        return null;
    }

    private static void route(XC_MethodHook.MethodHookParam param) throws Exception {
        Object router = param.thisObject;      // a synthetic class: fields are (int selector, Object, Object)
        Bundle bundle = null;
        Object owner = null;                   // the keypad router's state
        for (Field f : router.getClass().getDeclaredFields()) {
            if (f.getType() != Object.class) continue;
            f.setAccessible(true);
            Object v = f.get(router);
            if (v instanceof Bundle) bundle = (Bundle) v;
            else if (v != null) owner = v;
        }
        if (bundle == null || owner == null) return;
        if (RemoteMode.onKey(bundle)) { param.setResult(null); return; } // any app: remote mode shortcuts
        if (!Hook.backtalkForeground) return;            // only when the foreground app is one Backtalk handles (not self-voicing or Orbit's)

        ConcurrentHashMap<?, ?> registry = null;
        PowerManager power = null;
        Field focusedField = null;
        boolean locked = false;                                         // the Player's keyboard lock (Menu+star)
        for (Field f : owner.getClass().getDeclaredFields()) {
            Class<?> t = f.getType();
            if (t == ConcurrentHashMap.class) { f.setAccessible(true); registry = (ConcurrentHashMap<?, ?>) f.get(owner); }
            else if (t == PowerManager.class) { f.setAccessible(true); power = (PowerManager) f.get(owner); }
            else if (t == String.class) { f.setAccessible(true); focusedField = f; }
            else if (t == boolean.class) { f.setAccessible(true); locked = f.getBoolean(owner); }
        }
        if (registry == null || focusedField == null) return;
        if (power != null && !power.isInteractive()) return;            // screen off: let Orbit's own block apply
        Object display = registry.get(OrbitDisplayService.KEYPAD_NAME);
        if (display == null) return;                                    // Backtalk isn't using the Orbit display

        // Give the Orbit display the keypad, as notifyAppForeground would, so that Orbit's own routing sends it every
        // key it doesn't keep for itself. Orbit's apps and self-voicing apps take it back when they come to the front.
        if (!OrbitDisplayService.KEYPAD_NAME.equals(focusedField.get(owner))) {
            focusedField.set(owner, OrbitDisplayService.KEYPAD_NAME);
        }

        int key = bundle.getInt("keyCode", -1);
        int action = bundle.getInt("action", -1);
        if (action == 3) {
            // Speak command chords go to the display. The Player's Menu and volume combos (keyboard lock, speech rate,
            // ...) are device controls rather than review commands, so Orbit keeps them.
            if (isPlayerKey(key) || isPlayerKey(bundle.getInt("modifierKeyCode", -1))) return;
        } else if (key == PLAYER_HOME) {
            // Orbit goes home on HOME's press (and again at the long-press point) and swallows the release, so apps
            // never see it. Hand the whole press to the display, which goes home on release or opens recents on a long press.
        } else if (key == PLAYER_0 && action == 0 && !bundle.getBoolean("isLongPress", false)) {
            // Orbit throws away 0's press (only the release reaches apps), so 0 couldn't be part of a chord. Hand the
            // display the press; the long-press point still goes to Orbit, which opens its temporary sleep dialog.
            if (locked) return;                                         // keyboard locked: Orbit discards 0 as usual
        } else {
            return;                                     // everything else: Orbit's normal routing, now to the display
        }
        callbackMethod(display).invoke(display, bundle);
        param.setResult(null);                                          // consume: skip Orbit's own handling
    }

    private static boolean isPlayerKey(int code) {
        return code >= PLAYER_0 && code <= PLAYER_LAST;
    }

    /** The AIDL keypad callback's delivery method ({@code void C(Bundle)} after obfuscation): the one taking a single Bundle. */
    private static Method callbackMethod(Object callback) throws NoSuchMethodException {
        Class<?> k = callback.getClass();
        Method cached = CALLBACK.get(k);
        if (cached != null) return cached;
        for (Method m : k.getMethods()) {
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == Bundle.class) { CALLBACK.put(k, m); return m; }
        }
        throw new NoSuchMethodException("no single-Bundle callback method on " + k.getName());
    }
}
