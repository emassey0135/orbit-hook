package dev.emassey0135.satellite.hook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Tells Backtalk to look for braille displays again, in case it hasn't bound {@link OrbitDisplayService} since this
 * app was allowed. Send it with {@code adb shell am broadcast -f 0x20 -n dev.emassey0135.satellite.hook/.ConnectReceiver}.
 * Backtalk uses the Orbit display once the user allows it in Backtalk, which asks when it finds this app, or with
 * {@code adb shell am start -a fyi.quin.backtalk.action.ASK_BRAILLE_DISPLAY -p fyi.quin.backtalk
 * --es fyi.quin.backtalk.extra.PACKAGE dev.emassey0135.satellite.hook}.
 */
public final class ConnectReceiver extends BroadcastReceiver {
    static final String PERMISSION = "fyi.quin.backtalk.permission.BRAILLE_DISPLAY";

    @Override public void onReceive(Context context, Intent intent) {
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            Log.w(OrbitDisplayService.TAG, "Backtalk's braille display permission isn't granted yet");
            return;
        }
        context.sendBroadcast(new Intent("fyi.quin.backtalk.action.BRAILLE_DISPLAYS_CHANGED")
                .setPackage("fyi.quin.backtalk"));
    }
}
