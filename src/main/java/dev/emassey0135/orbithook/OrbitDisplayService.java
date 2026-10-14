package dev.emassey0135.orbithook;

import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.orbit.olympus_sdk.IOlympusSDKCallback;
import com.orbit.screenreader.IScreenReaderService;
import com.orbit.screenreader.IUARTKeypadCallback;

import fyi.quin.backtalk.braille.IBrailleDisplay;
import fyi.quin.backtalk.braille.IBrailleDisplayHost;

/**
 * The Orbit's built-in keys as a braille display for Backtalk, through Backtalk's braille display API.
 *
 * <p>Backtalk binds this service once the user has granted this app Backtalk's braille display permission. It then
 * registers with Orbit's screen reader for keypad events under this app's package name ({@link #KEYPAD_NAME}); the
 * hook in Orbit's screen reader ({@link ChordFreer}) hands the keypad to that name while the app in front is one
 * Backtalk reads, and Orbit's own apps and self-voicing apps take it back themselves. Keys are turned into braille keys
 * and commands here, so Backtalk knows nothing about Orbit.
 *
 * <p>The Orbit Speak and Orbit Player have no braille cells, so the display has none and {@code showCells} is never
 * called.
 */
public final class OrbitDisplayService extends Service {
    static final String TAG = "OrbitDisplay";
    /** The name this app registers with Orbit's keypad under: its own app ID. */
    static final String KEYPAD_NAME = BuildConfig.APPLICATION_ID;
    private static final String DISPLAY_NAME = "Orbit";

    private final NumpadBrailleKeyboard numpad = new NumpadBrailleKeyboard();
    private volatile IBrailleDisplayHost host;
    private volatile IScreenReaderService orbit;
    private boolean bound;

    private final IBrailleDisplay.Stub display = new IBrailleDisplay.Stub() {
        @Override public void onHostConnected(IBrailleDisplayHost h) {
            host = h;
            if (orbit != null) startDisplay(); // Orbit was already connected
        }
        @Override public void showCells(byte[] cells) { }
    };

    private final IOlympusSDKCallback sdkCallback = new IOlympusSDKCallback.Stub() {
        @Override public void onScreenReaderReady() { register(); }
        @Override public void onUtteranceStart(String utteranceId) { }
        @Override public void onUtteranceDone(String utteranceId) { }
        @Override public void onUtteranceError(String utteranceId) { }
    };

    private final IUARTKeypadCallback keypadCallback = new IUARTKeypadCallback.Stub() {
        @Override public boolean onInternalKeyEvent(Bundle event) {
            try { onKey(event); } catch (RuntimeException e) { Log.e(TAG, "key failed", e); }
            return true;
        }
        @Override public void onAdjustVolume(boolean increase) { }
    };

    private final ServiceConnection orbitConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            orbit = IScreenReaderService.Stub.asInterface(binder);
            try { orbit.registerSDKCallback(sdkCallback); } catch (RemoteException e) { Log.e(TAG, "registerSDKCallback", e); }
            register();
            startDisplay();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            orbit = null;
            stopDisplay();
        }
        /** Orbit's screen reader was updated or stopped; this binding won't come back, so bind again. */
        @Override public void onBindingDied(ComponentName name) {
            orbit = null;
            stopDisplay();
            unbindOrbit();
            bindOrbit();
        }
    };

    @Override public IBinder onBind(Intent intent) {
        bindOrbit();
        return display;
    }

    @Override public void onDestroy() {
        IScreenReaderService s = orbit;
        if (s != null) {
            try { s.unregisterKeypadCallback(KEYPAD_NAME); } catch (RemoteException ignored) { }
        }
        unbindOrbit();
        host = null;
        super.onDestroy();
    }

    private void bindOrbit() {
        if (bound) return;
        Intent intent = new Intent("com.orbit.screenreader.IScreenReaderService").setComponent(new ComponentName(
                "com.orbit.screenreader", "com.orbit.screenreader.service.ScreenReaderBoundService"));
        bound = bindService(intent, orbitConnection, BIND_AUTO_CREATE);
        if (!bound) Log.e(TAG, "couldn't bind Orbit's screen reader");
    }

    private void unbindOrbit() {
        if (!bound) return;
        bound = false;
        try { unbindService(orbitConnection); } catch (IllegalArgumentException ignored) { }
        orbit = null;
    }

    private void register() {
        IScreenReaderService s = orbit;
        if (s == null) return;
        try { s.registerKeypadCallback(KEYPAD_NAME, keypadCallback); } catch (RemoteException e) { Log.e(TAG, "registerKeypadCallback", e); }
    }

    private void startDisplay() {
        IBrailleDisplayHost h = host;
        if (h == null) return;
        try { h.start(DISPLAY_NAME, 0); } catch (RemoteException e) { Log.e(TAG, "start", e); }
    }

    private void stopDisplay() {
        IBrailleDisplayHost h = host;
        if (h == null) return;
        try { h.stop(); } catch (RemoteException e) { Log.e(TAG, "stop", e); }
    }

    // ---- Keys ------------------------------------------------------------------------------------------------------

    private void onKey(Bundle event) {
        IBrailleDisplayHost h = host;
        if (h == null) return;
        int action = event.getInt("action", -1);
        String keyName = event.getString("keyName", "");
        boolean longPress = event.getBoolean("isLongPress", false);
        try {
            if (NumpadBrailleKeyboard.brailleKeyFor(keyName) != null) {
                NumpadBrailleKeyboard.Cell cell = numpad.onKey(keyName, action, longPress, SystemClock.uptimeMillis());
                if (cell == null) return;
                if (cell.keys.size() == 1) singleKey(h, cell.keys.first(), cell.longPress);
                else chord(h, String.join("_", cell.keys));
            } else if (action == 1) {
                // A held key is pressed again with the long press flag set, so act on the release, when it is
                // known whether the key was held.
                singleKey(h, keyName, longPress);
            } else if (action == 3) {
                chord(h, keyName); // keys pressed together arrive as one chord, such as SPACE_DOT1_DOT2
            }
        } catch (RemoteException e) {
            Log.e(TAG, "couldn't send key " + keyName, e);
        }
    }

    /**
     * One key on its own. The arrows and OK work as on an Orbit Writer: left and right move by item (held: to the
     * first or last), up and down move by the reading control (held: choose another one), OK activates (held: long
     * press). Dots 7 and 8 are backspace and enter.
     */
    private static void singleKey(IBrailleDisplayHost h, String keyName, boolean longPress) throws RemoteException {
        switch (keyName) {
            case "OK": case "SELECT": h.command(longPress ? "LONG_PRESS_CURRENT" : "ACTIVATE_CURRENT", 0); return;
            case "LEFT": h.command(longPress ? "NAV_TOP" : "NAV_ITEM_PREVIOUS", 0); return;
            case "RIGHT": h.command(longPress ? "NAV_BOTTOM" : "NAV_ITEM_NEXT", 0); return;
            case "UP":
                h.command(longPress ? "PREVIOUS_READING_CONTROL"
                        : "NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_BACKWARD", 0);
                return;
            case "DOWN":
                h.command(longPress ? "NEXT_READING_CONTROL"
                        : "NAVIGATE_BY_READING_GRANULARITY_OR_ADJUST_READING_CONTROL_FORWARD", 0);
                return;
            case "DOT7": h.command(longPress ? "DEL_WORD" : "KEY_DEL", 0); return;
            case "DOT8": h.command("ACTIVATE_CURRENT", 0); return;
            case "SPACE": h.brailleKeys(0, true); return;
            // The Orbit Player's keys. Orbit's screen reader keeps HOME unless the hook hands it over.
            case "BACK": h.command("GLOBAL_BACK", 0); return;
            case "HOME": h.command(longPress ? "GLOBAL_RECENTS" : "GLOBAL_HOME", 0); return;
            case "MENU": h.command("OPEN_TALKBACK_MENU", 0); return;
            default:
                if (keyName.length() == 4 && keyName.startsWith("DOT")) h.brailleKeys(dots(keyName), false);
        }
    }

    /** Keys pressed together, named like {@code SPACE_DOT1_DOT2}: Backtalk runs the command bound to them, or types. */
    private static void chord(IBrailleDisplayHost h, String keyName) throws RemoteException {
        boolean space = false;
        int dots = 0;
        for (String token : keyName.split("_")) {
            if (token.equals("SPACE")) space = true;
            else if (token.startsWith("DOT")) dots |= dots(token);
        }
        if (dots != 0 || space) h.brailleKeys(dots, space);
    }

    /** The dot bit for {@code DOTn}: bit 0 is dot 1. */
    private static int dots(String token) {
        int dot = token.charAt(3) - '0';
        return dot >= 1 && dot <= 8 ? 1 << (dot - 1) : 0;
    }
}
