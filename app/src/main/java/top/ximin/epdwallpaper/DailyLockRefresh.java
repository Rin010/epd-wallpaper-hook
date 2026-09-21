package top.ximin.epdwallpaper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;
import android.view.View;
import android.view.ViewGroup;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Calendar;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Rebuilds the vendor calendar view around midnight without waking the display.
 *
 * <p>The alarm is deliberately inexact and runs only once per day. RTC_WAKEUP wakes the CPU so
 * an already sleeping reader can refresh its persistent e-ink panel, but it does not make the
 * display interactive or turn on the front light. If the reader is in use, the refresh is skipped;
 * the next ordinary lock will create an up-to-date calendar view anyway.</p>
 */
final class DailyLockRefresh {
    private static final String ACTION_REFRESH =
            "top.ximin.epdwallpaper.action.DAILY_LOCK_REFRESH";
    private static final String DEVICE_POWER_PERMISSION = "android.permission.DEVICE_POWER";
    private static final int REQUEST_CODE = 0x455044;
    private static final long WINDOW_MILLIS = 2L * 60L * 60L * 1000L;

    private static boolean initialized;
    private static Object powerManagerService;
    private static Context systemContext;
    private static AlarmManager alarmManager;
    private static PendingIntent refreshIntent;
    private static BroadcastReceiver receiver;
    private static Class<?> lunarCalendarViewClass;
    private static Constructor<?> lunarCalendarConstructor;

    private DailyLockRefresh() {
    }

    static void install(ClassLoader classLoader) throws Throwable {
        final Class<?> serviceClass = Class.forName(
                "com.android.server.power.PowerManagerService", false, classLoader);
        Class<?> appOpsClass = Class.forName(
                "com.android.internal.app.IAppOpsService", false, classLoader);
        lunarCalendarViewClass = Class.forName(
                "android.widget.LunarCalendarView", false, classLoader);
        lunarCalendarConstructor = lunarCalendarViewClass.getConstructor(
                Context.class, boolean.class, int.class, boolean.class);
        lunarCalendarConstructor.setAccessible(true);

        XposedHelpers.findAndHookMethod(serviceClass, "systemReady", appOpsClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            initialize(param.thisObject);
                        } catch (Throwable error) {
                            EpdWallpaperHook.log("unable to schedule daily lock refresh", error);
                        }
                    }
                });
    }

    private static synchronized void initialize(Object service) throws Throwable {
        if (initialized) {
            return;
        }
        Context context = (Context) readField(service, "mContext");
        Handler handler = (Handler) readField(service, "mHandler");
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) {
            throw new IllegalStateException("AlarmManager is unavailable");
        }

        Intent alarm = new Intent(ACTION_REFRESH).setPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, alarm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        BroadcastReceiver alarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_REFRESH.equals(action)) {
                    try {
                        refreshIfSleeping();
                    } catch (Throwable error) {
                        EpdWallpaperHook.log("daily lock refresh failed", error);
                    }
                }
                try {
                    scheduleNext();
                } catch (Throwable error) {
                    EpdWallpaperHook.log("unable to reschedule daily lock refresh", error);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REFRESH);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        context.registerReceiver(alarmReceiver, filter, DEVICE_POWER_PERMISSION, handler);

        powerManagerService = service;
        systemContext = context;
        alarmManager = alarms;
        refreshIntent = pendingIntent;
        receiver = alarmReceiver;
        initialized = true;
        scheduleNext();
    }

    private static void scheduleNext() {
        Calendar next = Calendar.getInstance();
        next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        alarmManager.cancel(refreshIntent);
        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(),
                WINDOW_MILLIS, refreshIntent);
        EpdWallpaperHook.log("next daily lock refresh scheduled near "
                + next.getTimeInMillis());
    }

    private static void refreshIfSleeping() throws Throwable {
        if (!EpdWallpaperHook.isHookCategoryEnabled(WallpaperConfig.LOCK)) {
            EpdWallpaperHook.log("daily lock refresh skipped: lock hook disabled");
            return;
        }
        PowerManager power = (PowerManager) systemContext.getSystemService(
                Context.POWER_SERVICE);
        if (power == null || power.isInteractive()) {
            EpdWallpaperHook.log("daily lock refresh skipped: display is interactive");
            return;
        }

        View root = (View) readField(powerManagerService, "mBackGroundImg");
        View oldCalendar = findCalendarView(root);
        if (root == null || root.getWindowToken() == null || oldCalendar == null
                || !(oldCalendar.getParent() instanceof ViewGroup)) {
            EpdWallpaperHook.log("daily lock refresh skipped: suspend logo is not attached");
            return;
        }

        boolean charging = (Boolean) readField(oldCalendar, "mCharging");
        int resourceId = (Integer) readField(oldCalendar, "mResId");
        boolean blackText = (Boolean) readField(oldCalendar, "blackTextView");

        // Construct first so a reflection or resource failure leaves the current lock screen intact.
        View replacement = (View) lunarCalendarConstructor.newInstance(
                systemContext, charging, resourceId, blackText);

        ViewGroup parent = (ViewGroup) oldCalendar.getParent();
        int index = parent.indexOfChild(oldCalendar);
        ViewGroup.LayoutParams layoutParams = oldCalendar.getLayoutParams();
        parent.removeViewAt(index);
        parent.addView(replacement, index, layoutParams);
        parent.requestLayout();
        EpdWallpaperHook.forceEinkRefresh(root);
        EpdWallpaperHook.log("daily lock screen refreshed without waking the display");
    }

    private static View findCalendarView(View view) {
        if (view == null) {
            return null;
        }
        if (lunarCalendarViewClass.isInstance(view)) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = findCalendarView(group.getChildAt(index));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static Object readField(Object instance, String name) throws Throwable {
        Class<?> type = instance.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
