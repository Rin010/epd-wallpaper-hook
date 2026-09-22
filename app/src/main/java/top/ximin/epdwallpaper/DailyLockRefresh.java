package top.ximin.epdwallpaper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.PowerManager;

import java.lang.reflect.Field;
import java.util.Calendar;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/** Rebuilds the lock screen through one ordinary, front-light-dark wake/sleep cycle. */
final class DailyLockRefresh {
    private static final String ACTION_REFRESH =
            "top.ximin.epdwallpaper.action.DAILY_LOCK_REFRESH";
    static final String ACTION_TEST_REFRESH =
            "top.ximin.epdwallpaper.action.TEST_DAILY_LOCK_REFRESH";
    private static final String DEVICE_POWER_PERMISSION = "android.permission.DEVICE_POWER";
    private static final int REQUEST_CODE = 0x455044;
    private static final long WINDOW_MILLIS = 2L * 60L * 60L * 1000L;
    private static final long SESSION_TIMEOUT_MILLIS = 20L * 1000L;
    private static final long FINAL_SLEEP_TIMEOUT_MILLIS = 5L * 1000L;
    private static final long CPU_WAKE_TIMEOUT_MILLIS = 30L * 1000L;
    private static final int WAKEFULNESS_ASLEEP = 0;
    private static final int WAKEFULNESS_AWAKE = 1;
    private static final int PHASE_IDLE = 0;
    private static final int PHASE_PREPARING = 1;
    private static final int PHASE_WAKING = 2;
    private static final int PHASE_SLEEPING = 3;
    private static final int PHASE_RESTORING = 4;

    private static boolean initialized;
    private static Object powerManagerService;
    private static Context systemContext;
    private static AlarmManager alarmManager;
    private static PendingIntent refreshIntent;
    private static BroadcastReceiver receiver;
    private static Handler serviceHandler;
    private static PowerManager.WakeLock cpuWakeLock;
    private static SystemSleepCycle sleepCycle;
    private static FrontLightSettings frontLightSettings;

    private static int phase = PHASE_IDLE;
    private static long sessionId;
    private static boolean issuingPowerRequest;
    private static boolean suspendLogoShown;
    private static boolean finalSleepRequested;
    private static FrontLightSettings.Snapshot lightSnapshot;
    private static Runnable sessionTimeout;
    private static Runnable finalSleepTimeout;

    private DailyLockRefresh() {
    }

    static void install(ClassLoader classLoader) throws Throwable {
        final Class<?> serviceClass = Class.forName(
                "com.android.server.power.PowerManagerService", false, classLoader);
        Class<?> appOpsClass = Class.forName(
                "com.android.internal.app.IAppOpsService", false, classLoader);

        XposedHelpers.findAndHookMethod(serviceClass, "systemReady", appOpsClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            initialize(param.thisObject);
                        } catch (Throwable error) {
                            EpdWallpaperHook.log("unable to initialize daily lock refresh", error);
                        }
                    }
                });

        XposedHelpers.findAndHookMethod(serviceClass,
                "finishWakefulnessChangeIfNeededLocked",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        reportSettledPowerState(param.thisObject);
                    }
                });

        XposedHelpers.findAndHookMethod(serviceClass, "showSuspendLogo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        reportSuspendLogoShown();
                    }
                });

        XposedHelpers.findAndHookMethod(serviceClass, "wakeUpInternal",
                long.class, String.class, int.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        reportExternalWakeRequest();
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
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (alarms == null || power == null) {
            throw new IllegalStateException("required power services are unavailable");
        }

        Intent alarm = new Intent(ACTION_REFRESH).setPackage(context.getPackageName());
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, REQUEST_CODE, alarm,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        BroadcastReceiver alarmReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (ACTION_REFRESH.equals(action)) {
                    startAutomaticRefresh();
                    try {
                        scheduleNext();
                    } catch (Throwable error) {
                        EpdWallpaperHook.log("unable to reschedule daily lock refresh", error);
                    }
                } else if (ACTION_TEST_REFRESH.equals(action)) {
                    startManualRefresh();
                } else {
                    try {
                        scheduleNext();
                    } catch (Throwable error) {
                        EpdWallpaperHook.log("unable to update daily lock refresh alarm", error);
                    }
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_REFRESH);
        filter.addAction(ACTION_TEST_REFRESH);
        filter.addAction(SystemWallpaperStore.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        context.registerReceiver(alarmReceiver, filter, DEVICE_POWER_PERMISSION, handler);

        powerManagerService = service;
        systemContext = context;
        alarmManager = alarms;
        refreshIntent = pendingIntent;
        receiver = alarmReceiver;
        serviceHandler = handler;
        sleepCycle = new SystemSleepCycle(power);
        frontLightSettings = new FrontLightSettings(context);
        cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "EpdWallpaper:DailyRefresh");
        cpuWakeLock.setReferenceCounted(false);
        initialized = true;

        try {
            if (frontLightSettings.recoverPending()) {
                EpdWallpaperHook.log(
                        "recovered front-light settings from an interrupted refresh");
            }
        } catch (Throwable error) {
            EpdWallpaperHook.log("unable to recover interrupted front-light settings", error);
        }
        scheduleNext();
    }

    private static void startAutomaticRefresh() {
        try {
            if (EpdWallpaperHook.isDailyLockRefreshEnabled()) {
                refreshIfSleeping();
            } else {
                EpdWallpaperHook.log("daily lock refresh skipped: automatic refresh disabled");
            }
        } catch (Throwable error) {
            EpdWallpaperHook.log("daily lock refresh failed", error);
            failSession("start failed");
        }
    }

    private static void startManualRefresh() {
        try {
            EpdWallpaperHook.log("manual daily lock refresh test requested");
            refreshIfSleeping();
        } catch (Throwable error) {
            EpdWallpaperHook.log("manual daily lock refresh test failed", error);
            failSession("manual start failed");
        }
    }

    private static void scheduleNext() {
        alarmManager.cancel(refreshIntent);
        if (!EpdWallpaperHook.isDailyLockRefreshEnabled()) {
            EpdWallpaperHook.log("daily lock refresh alarm cancelled: feature disabled");
            return;
        }

        Calendar next = Calendar.getInstance();
        next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);

        alarmManager.setWindow(AlarmManager.RTC_WAKEUP, next.getTimeInMillis(),
                WINDOW_MILLIS, refreshIntent);
        EpdWallpaperHook.log("next daily lock refresh scheduled near "
                + next.getTimeInMillis());
    }

    private static synchronized void refreshIfSleeping() throws Throwable {
        if (!EpdWallpaperHook.isHookCategoryEnabled(WallpaperConfig.LOCK)) {
            EpdWallpaperHook.log("daily lock refresh skipped: lock hook disabled");
            return;
        }
        if (phase != PHASE_IDLE) {
            EpdWallpaperHook.log("daily lock refresh skipped: refresh already in progress");
            return;
        }
        if (sleepCycle.isInteractive()) {
            EpdWallpaperHook.log("daily lock refresh skipped: display is interactive");
            return;
        }

        phase = PHASE_PREPARING;
        sessionId++;
        suspendLogoShown = false;
        finalSleepRequested = false;
        cpuWakeLock.acquire(CPU_WAKE_TIMEOUT_MILLIS);
        try {
            lightSnapshot = frontLightSettings.capture();
            frontLightSettings.prepareRecovery(lightSnapshot);
            frontLightSettings.applyZero();
            EpdWallpaperHook.log("daily lock refresh saved front light and set both levels to 0");
            phase = PHASE_WAKING;
            scheduleSessionTimeout(sessionId);
            issueWake();
        } catch (Throwable error) {
            failSession("unable to prepare dark wake");
            throw error;
        }
    }

    private static void issueWake() throws Throwable {
        issuingPowerRequest = true;
        try {
            sleepCycle.wake();
            EpdWallpaperHook.log("daily lock refresh requested a normal system wake");
        } finally {
            issuingPowerRequest = false;
        }
    }

    private static void issueSleep() throws Throwable {
        issuingPowerRequest = true;
        try {
            sleepCycle.sleep();
            EpdWallpaperHook.log("daily lock refresh requested a normal system sleep");
        } finally {
            issuingPowerRequest = false;
        }
    }

    private static void reportSettledPowerState(final Object service) {
        if (phase == PHASE_IDLE || serviceHandler == null) {
            return;
        }
        try {
            final int wakefulness = ((Integer) readField(service, "mWakefulness")).intValue();
            final boolean changing = ((Boolean) readField(
                    service, "mWakefulnessChanging")).booleanValue();
            final boolean displayReady = ((Boolean) readField(
                    service, "mDisplayReady")).booleanValue();
            final long expectedSession = sessionId;
            serviceHandler.post(new Runnable() {
                @Override
                public void run() {
                    handleSettledPowerState(expectedSession, wakefulness, changing, displayReady);
                }
            });
        } catch (Throwable error) {
            EpdWallpaperHook.log("unable to inspect daily refresh power state", error);
        }
    }

    private static synchronized void handleSettledPowerState(long expectedSession,
            int wakefulness, boolean changing, boolean displayReady) {
        if (expectedSession != sessionId || phase == PHASE_IDLE || changing || !displayReady) {
            return;
        }
        try {
            if (phase == PHASE_WAKING && wakefulness == WAKEFULNESS_AWAKE) {
                phase = PHASE_SLEEPING;
                issueSleep();
            } else if (phase == PHASE_SLEEPING && wakefulness == WAKEFULNESS_ASLEEP
                    && suspendLogoShown) {
                finishSession("normal wake/sleep cycle completed");
            }
        } catch (Throwable error) {
            EpdWallpaperHook.log("daily lock refresh state transition failed", error);
            failSession("state transition failed");
        }
    }

    private static void reportSuspendLogoShown() {
        if (phase == PHASE_IDLE || serviceHandler == null) {
            return;
        }
        final long expectedSession = sessionId;
        serviceHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (DailyLockRefresh.class) {
                    if (expectedSession != sessionId || phase == PHASE_IDLE) {
                        return;
                    }
                    suspendLogoShown = true;
                    EpdWallpaperHook.log("daily lock refresh observed the rebuilt lock screen");
                    tryFinishSleepingSession();
                }
            }
        });
    }

    private static synchronized void tryFinishSleepingSession() {
        if (phase != PHASE_SLEEPING || !suspendLogoShown) {
            return;
        }
        try {
            int wakefulness = ((Integer) readField(
                    powerManagerService, "mWakefulness")).intValue();
            boolean changing = ((Boolean) readField(
                    powerManagerService, "mWakefulnessChanging")).booleanValue();
            boolean displayReady = ((Boolean) readField(
                    powerManagerService, "mDisplayReady")).booleanValue();
            if (wakefulness == WAKEFULNESS_ASLEEP && !changing && displayReady) {
                finishSession("normal wake/sleep cycle completed");
            }
        } catch (Throwable error) {
            EpdWallpaperHook.log("unable to verify completed daily lock sleep", error);
        }
    }

    private static void reportExternalWakeRequest() {
        if (phase == PHASE_IDLE || issuingPowerRequest || serviceHandler == null) {
            return;
        }
        final long expectedSession = sessionId;
        serviceHandler.post(new Runnable() {
            @Override
            public void run() {
                synchronized (DailyLockRefresh.class) {
                    if (expectedSession != sessionId || phase == PHASE_IDLE) {
                        return;
                    }
                    EpdWallpaperHook.log("daily lock refresh yielded to an external wake request");
                    finishSession("external wake request");
                }
            }
        });
    }

    private static void scheduleSessionTimeout(final long expectedSession) {
        cancelTimeouts();
        sessionTimeout = new Runnable() {
            @Override
            public void run() {
                synchronized (DailyLockRefresh.class) {
                    if (expectedSession != sessionId || phase == PHASE_IDLE) {
                        return;
                    }
                    if (sleepCycle.isInteractive() && !finalSleepRequested) {
                        try {
                            finalSleepRequested = true;
                            phase = PHASE_SLEEPING;
                            issueSleep();
                            EpdWallpaperHook.log(
                                    "daily lock refresh timeout requested a final system sleep");
                            scheduleFinalSleepTimeout(expectedSession);
                            return;
                        } catch (Throwable error) {
                            EpdWallpaperHook.log("daily refresh timeout sleep failed", error);
                        }
                    }
                    failSession("session timed out");
                }
            }
        };
        serviceHandler.postDelayed(sessionTimeout, SESSION_TIMEOUT_MILLIS);
    }

    private static void scheduleFinalSleepTimeout(final long expectedSession) {
        finalSleepTimeout = new Runnable() {
            @Override
            public void run() {
                synchronized (DailyLockRefresh.class) {
                    if (expectedSession == sessionId && phase != PHASE_IDLE) {
                        failSession("final sleep timed out");
                    }
                }
            }
        };
        serviceHandler.postDelayed(finalSleepTimeout, FINAL_SLEEP_TIMEOUT_MILLIS);
    }

    private static synchronized void finishSession(String reason) {
        if (phase == PHASE_IDLE) {
            releaseCpuWakeLock();
            return;
        }
        phase = PHASE_RESTORING;
        cancelTimeouts();
        boolean restored = false;
        try {
            restored = frontLightSettings.restoreOwnedChanges(lightSnapshot);
            if (restored) {
                frontLightSettings.clearRecovery();
            }
        } catch (Throwable error) {
            EpdWallpaperHook.log("unable to restore front-light settings", error);
        } finally {
            lightSnapshot = null;
            suspendLogoShown = false;
            finalSleepRequested = false;
            phase = PHASE_IDLE;
            releaseCpuWakeLock();
        }
        EpdWallpaperHook.log("daily lock refresh finished: " + reason
                + ", frontLightRestored=" + restored);
    }

    private static synchronized void failSession(String reason) {
        finishSession(reason);
    }

    private static void cancelTimeouts() {
        if (serviceHandler == null) {
            return;
        }
        if (sessionTimeout != null) {
            serviceHandler.removeCallbacks(sessionTimeout);
            sessionTimeout = null;
        }
        if (finalSleepTimeout != null) {
            serviceHandler.removeCallbacks(finalSleepTimeout);
            finalSleepTimeout = null;
        }
    }

    private static void releaseCpuWakeLock() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
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
