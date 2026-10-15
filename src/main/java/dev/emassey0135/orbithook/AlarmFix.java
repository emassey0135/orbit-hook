package dev.emassey0135.orbithook;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/**
 * Fixes two bugs in com.orbit.alarmclock.
 *
 * <p>"Dismiss" acts like snooze. The app schedules every alarm's next repeat under request code 1, while Dismiss
 * cancels only the alarm's own request code, so the repeat survives and the alarm rings again. Here each repeat gets
 * its own request code (the negative of the alarm id; ids are positive epoch seconds); cancelling an alarm also
 * cancels its repeat; and the app's own boot-time reschedule is run afterwards, because Dismiss also leaves the
 * alarm's daily or weekly schedule cancelled.
 *
 * <p>Daily and weekly alarms often don't ring. The app sets an exact alarm and then, with the same PendingIntent, an
 * inexact repeating one, which replaces it; Android may deliver that late, and holds it back while the device is idle,
 * but the app rings only if the clock still reads the alarm's hour and minute. Here the repeating alarm becomes an
 * alarm clock alarm for its next time, which is exact and wakes the device, and the app's reschedule runs a minute
 * after it to set the time after that.
 */
final class AlarmFix {
    static final String TAG = "OrbitHook.Alarm";
    static final String PACKAGE = "com.orbit.alarmclock";
    static final String RECEIVER = "com.orbit.alarmclock.receiver.AlarmReceiver";
    static final String RESCHEDULE_RECEIVER = "com.orbit.alarmclock.receiver.MyBroadcastReceiver";
    static final String RESCHEDULE_ACTION = "com.orbit.ALARM_DATA";
    /** The app's own code for every repeat; repeats are the only alarm intents it makes without "fromWhere". */
    static final int APP_REPEAT_CODE = 1;
    /**
     * Request code of the reschedule broadcast after a cancel. The reschedule after each daily or weekly alarm uses the
     * alarm's id instead, so that each alarm has its own; these never clash with the alarms' own codes, since they are
     * for another receiver.
     */
    static final int RESCHEDULE_CODE = Integer.MIN_VALUE;

    /** Set while this class cancels a repeat itself, so that cancel isn't handled as the app's. */
    final ThreadLocal<Boolean> busy = ThreadLocal.withInitial(() -> false);

    /** Alarm id behind each main-entry PendingIntent the app has made, so a cancel can find the alarm's repeat. */
    final Map<PendingIntent, Integer> alarmIds = Collections.synchronizedMap(new WeakHashMap<>());

    void install() throws NoSuchMethodException {
        Method getBroadcast = PendingIntent.class.getDeclaredMethod("getBroadcast", Context.class, int.class, Intent.class, int.class);
        XposedBridge.hookMethod(getBroadcast, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                Intent intent = (Intent) param.args[2];
                if (!targetsReceiver(intent)) return;
                int id = intent.getIntExtra("id", -1);
                if (id > 0 && !intent.hasExtra("fromWhere") && (int) param.args[1] == APP_REPEAT_CODE) {
                    param.args[1] = repeatCode(id);
                    Log.i(TAG, "repeat of alarm " + id + " given its own request code");
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Intent intent = (Intent) param.args[2];
                PendingIntent result = (PendingIntent) param.getResult();
                int id = targetsReceiver(intent) ? intent.getIntExtra("id", -1) : -1;
                // Only an alarm's main entry: cancelling its repeat slot must not look like cancelling the alarm.
                if (result != null && id > 0 && (int) param.args[1] > 0) alarmIds.put(result, id);
            }
        });

        Method cancel = AlarmManager.class.getDeclaredMethod("cancel", PendingIntent.class);
        XposedBridge.hookMethod(cancel, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (busy.get()) return;
                busy.set(true);
                try {
                    PendingIntent cancelled = (PendingIntent) param.args[0];
                    Integer id = cancelled == null ? null : alarmIds.get(cancelled);
                    if (id == null) return;
                    Context context = currentApplication();
                    if (context == null) return;
                    cancelRepeat(context, (AlarmManager) param.thisObject, id);
                    scheduleReschedule(context, (AlarmManager) param.thisObject);
                } catch (Throwable t) {
                    Log.e(TAG, "cancel follow-up failed", t);
                } finally {
                    busy.set(false);
                }
            }
        });
        Method setRepeating = AlarmManager.class.getDeclaredMethod("setRepeating", int.class, long.class, long.class,
                PendingIntent.class);
        XposedBridge.hookMethod(setRepeating, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    PendingIntent alarm = (PendingIntent) param.args[3];
                    Integer id = alarm == null ? null : alarmIds.get(alarm);
                    if (id == null) return; // not one of the app's alarms
                    Context context = currentApplication();
                    if (context == null) return;
                    setExact(context, (AlarmManager) param.thisObject, id, alarm, (long) param.args[1], (long) param.args[2]);
                    param.setResult(null);
                } catch (Throwable t) {
                    Log.e(TAG, "exact alarm not set; leaving the app's repeating alarm", t);
                }
            }
        });
        Log.i(TAG, "installed");
    }

    /**
     * Instead of a repeating alarm from {@code first} every {@code interval}: an alarm clock alarm at its next time,
     * and the app's reschedule a minute after, which sets the time after that the same way.
     */
    static void setExact(Context context, AlarmManager alarms, int id, PendingIntent alarm, long first, long interval) {
        long now = System.currentTimeMillis();
        long next = first;
        if (next <= now) next += ((now - next) / interval + 1) * interval;
        Intent open = context.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        PendingIntent show = open == null ? null
                : PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        alarms.setAlarmClock(new AlarmManager.AlarmClockInfo(next, show), alarm);
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 61_000, reschedule(context, id));
        Log.i(TAG, "alarm " + id + " set exactly for " + new java.util.Date(next));
    }

    /** The app's boot-time reschedule, which sets every alarm again, as a broadcast under {@code code}. */
    static PendingIntent reschedule(Context context, int code) {
        Intent intent = new Intent(RESCHEDULE_ACTION).setClassName(PACKAGE, RESCHEDULE_RECEIVER)
                .putExtra("isDeviceRestarted", false);
        return PendingIntent.getBroadcast(context, code, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    static int repeatCode(int alarmId) {
        return -alarmId;
    }

    static boolean targetsReceiver(Intent intent) {
        ComponentName c = intent == null ? null : intent.getComponent();
        return c != null && RECEIVER.equals(c.getClassName());
    }

    static Context currentApplication() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            return (Context) at.getMethod("currentApplication").invoke(null);
        } catch (Throwable t) {
            return null;
        }
    }

    static void cancelRepeat(Context context, AlarmManager alarms, int id) {
        Intent intent = new Intent().setClassName(PACKAGE, RECEIVER);
        PendingIntent repeat = PendingIntent.getBroadcast(context, repeatCode(id), intent,
                PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE);
        if (repeat == null) return;
        alarms.cancel(repeat);
        repeat.cancel();
        Log.i(TAG, "cancelled pending repeat of alarm " + id);
    }

    /**
     * Runs the app's boot-time reschedule just after the current minute ends. Run sooner, an alarm dismissed in its
     * own minute would be re-armed for a time that has just passed, fire at once, and match the clock again.
     */
    static void scheduleReschedule(Context context, AlarmManager alarms) {
        long now = System.currentTimeMillis();
        long nextMinute = now - now % 60_000 + 60_000 + 2_000;
        alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMinute, reschedule(context, RESCHEDULE_CODE));
        Log.i(TAG, "alarm reschedule set for " + (nextMinute - now) / 1000 + "s from now");
    }
}
