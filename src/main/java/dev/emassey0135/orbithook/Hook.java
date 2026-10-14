package dev.emassey0135.orbithook;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import org.luckypray.dexkit.DexKitBridge;

/**
 * Keeps Orbit's screen reader out of apps that something else speaks for (and loads {@link AlarmFix} into the alarm clock):
 * apps that declare the self-voicing meta-data, and third-party apps while another screen reader is enabled.
 * Only Android API names are hooked, so obfuscation in Orbit's builds doesn't affect it.
 */
public class Hook implements IXposedHookLoadPackage {
    static final String TAG = "OrbitHook";
    static final String TARGET = "com.orbit.screenreader";
    static final String SERVICE = "com.orbit.screenreader.ScreenReaderAccessibilityService";
    /** Package name Orbit sees for windows it should leave alone; any com.orbit. name puts it in native mode. */
    static final String ALIAS = "com.orbit.external";
    static final String SELF_VOICING = "orbit.self_voicing";
    static final String[] OTHER_READERS = {"fyi.quin.backtalk/", "com.google.android.marvin.talkback/"};
    static final long CACHE_MS = 10_000;

    /**
     * True while the foreground app is one BackTalk handles: a third-party app, with another reader enabled, that is
     * NOT self-voicing. Only then does {@link ChordFreer} divert Orbit's command chords. Self-voicing and com.orbit
     * apps leave this false, so Orbit keeps handling their review chords as usual.
     */
    static volatile boolean backtalkForeground = false;

    /** The screen reader service's context, kept for {@link RemoteMode} (set on the first accessibility event). */
    static volatile Context context;

    final Map<String, long[]> selfVoicing = new HashMap<>();

    @Override public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lp) throws Throwable {
        if (AlarmFix.PACKAGE.equals(lp.packageName)) {
            try { new AlarmFix().install(); } catch (Throwable t) { Log.e(TAG, "alarm fix not installed", t); }
            return;
        }
        if (!TARGET.equals(lp.packageName)) return;
        Class<?> c = Class.forName(SERVICE, false, lp.classLoader);
        Method m = null;
        for (Class<?> k = c; k != null && m == null; k = k.getSuperclass()) {
            try { m = k.getDeclaredMethod("onAccessibilityEvent", AccessibilityEvent.class); } catch (NoSuchMethodException ignored) { }
        }
        if (m == null) { Log.e(TAG, "onAccessibilityEvent not found"); return; }
        XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try { filter(param); } catch (Throwable t) { Log.e(TAG, "filter failed", t); }
            }
        });
        Log.i(TAG, "hooked " + m);
        // The keypad router and the remote mode report handler are obfuscated; find both in one DexKit scan.
        try {
            System.loadLibrary("dexkit");
            try (DexKitBridge bridge = DexKitBridge.create(lp.classLoader, true)) {
                ChordFreer.install(bridge, lp.classLoader);
                RemoteMode.install(bridge, lp.classLoader);
            }
        } catch (Throwable t) {
            Log.e(TAG, "DexKit unavailable; chord pass-through and remote mode shortcuts disabled", t);
        }
    }

    void filter(XC_MethodHook.MethodHookParam param) {
        AccessibilityService service = (AccessibilityService) param.thisObject;
        context = service;
        AccessibilityEvent e = (AccessibilityEvent) param.args[0];
        if (e == null) return;
        int type = e.getEventType();
        String pkg;
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            AccessibilityNodeInfo root = service.getRootInActiveWindow();
            if (root == null) return;
            pkg = root.getPackageName() == null ? null : root.getPackageName().toString();
            root.recycle();
        } else {
            pkg = e.getPackageName() == null ? null : e.getPackageName().toString();
        }
        // A keyboard window doesn't change which app is in front.
        if ((type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
                && (pkg == null || !pkg.contains("inputmethod"))) {
            backtalkForeground = pkg != null && backtalkHandled(service, pkg);
        }
        if (pkg == null || !handledElsewhere(service, pkg)) return;
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Delivered events are sealed, so hand Orbit an unsealed copy under the alias instead.
            AccessibilityEvent alias = AccessibilityEvent.obtain(type);
            alias.setPackageName(ALIAS);
            alias.setClassName(e.getClassName());
            alias.setEventTime(e.getEventTime());
            alias.getText().addAll(e.getText());
            param.args[0] = alias;
            Log.d(TAG, "window of " + pkg + " shown to Orbit as " + ALIAS);
        } else {
            param.setResult(null);
        }
    }

    boolean handledElsewhere(Context context, String pkg) {
        if (pkg.startsWith("com.orbit.") || pkg.contains("inputmethod")) return false;
        return isSelfVoicing(context, pkg) || otherReaderEnabled(context);
    }

    /** A third-party app BackTalk drives (another reader enabled, not self-voicing): the only case where chords are diverted. */
    boolean backtalkHandled(Context context, String pkg) {
        if (pkg.startsWith("com.orbit.") || pkg.contains("inputmethod")) return false;
        return otherReaderEnabled(context) && !isSelfVoicing(context, pkg);
    }

    boolean otherReaderEnabled(Context context) {
        String enabled = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        for (String r : OTHER_READERS) if (enabled.contains(r)) return true;
        return false;
    }

    boolean isSelfVoicing(Context context, String pkg) {
        long now = SystemClock.uptimeMillis();
        long[] cached = selfVoicing.get(pkg);
        if (cached != null && now - cached[1] < CACHE_MS) return cached[0] != 0;
        boolean result = false;
        try {
            ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, PackageManager.GET_META_DATA);
            Bundle md = ai.metaData;
            result = md != null && md.getBoolean(SELF_VOICING, false);
        } catch (PackageManager.NameNotFoundException ignored) { }
        selfVoicing.put(pkg, new long[] {result ? 1 : 0, now});
        return result;
    }
}
