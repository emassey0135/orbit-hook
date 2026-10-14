package orbithook;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

/**
 * Tells Backtalk to look for braille displays again, so it binds {@link OrbitDisplayService} straight after this app
 * is given Backtalk's braille display permission (Android doesn't announce a granted permission). Send it with
 * {@code adb shell am broadcast -n io.github.orbithook/orbithook.ConnectReceiver} after
 * {@code adb shell pm grant io.github.orbithook fyi.quin.backtalk.permission.BRAILLE_DISPLAY}.
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
