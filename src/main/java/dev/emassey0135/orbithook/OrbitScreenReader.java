package dev.emassey0135.orbithook;

import android.content.ComponentName;
import android.content.Intent;

/** Orbit's screen reader service, which the Orbit display and the updater fix both connect to. */
final class OrbitScreenReader {
    private OrbitScreenReader() { }

    /** The intent that binds Orbit's screen reader service. */
    static Intent bindIntent() {
        return new Intent("com.orbit.screenreader.IScreenReaderService").setComponent(new ComponentName(
                "com.orbit.screenreader", "com.orbit.screenreader.service.ScreenReaderBoundService"));
    }
}
