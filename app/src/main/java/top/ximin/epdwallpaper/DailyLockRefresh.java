package top.ximin.epdwallpaper;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.os.Binder;
import android.os.Handler;
import android.os.PowerManager;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
    private static final long DISPLAY_START_DELAY_MILLIS = 350L;
    private static final long FIRST_ACTIVE_REFRESH_MILLIS = 650L;
    private static final long OVERLAY_SURFACE_RECYCLE_MILLIS = 1900L;
    private static final long DISPLAY_SETTLE_MILLIS = 5000L;
    private static final long CPU_WAKE_TIMEOUT_MILLIS = 9000L;
    private static final int LIGHT_ID_BACKLIGHT = 0;
    private static final int DISPLAY_POLICY_OFF = 0;
    private static final int DISPLAY_POLICY_BRIGHT = 3;

    private static boolean initialized;
    private static Object powerManagerService;
    private static Context systemContext;
    private static AlarmManager alarmManager;
    private static PendingIntent refreshIntent;
    private static BroadcastReceiver receiver;
    private static Handler serviceHandler;
    private static PowerManager.WakeLock cpuWakeLock;
    private static WindowManager windowManager;
    private static View dailyOverlay;
    private static Class<?> lunarCalendarViewClass;
    private static Constructor<?> lunarCalendarConstructor;
    private static Class<?> displayPowerRequestClass;
    private static Constructor<?> displayPowerRequestCopyConstructor;
    private static Object displayManagerInternal;
    private static Method requestPowerStateMethod;
    private static volatile boolean refreshInProgress;
    private static volatile boolean issuingDisplayPulseRequest;
    private static boolean frontLightClampLogged;
    private static boolean staleOffRequestLogged;

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

        // This reader's light HAL drives the front light through /dev/disp, not through the
        // ordinary backlight sysfs nodes.  Clamp the final LightsService call while the display
        // controller is briefly active so composition cannot illuminate the LEDs.
        Class<?> lightImplClass = Class.forName(
                "com.android.server.lights.LightsService$LightImpl", false, classLoader);
        XposedHelpers.findAndHookMethod(lightImplClass, "setLightLocked",
                int.class, int.class, int.class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!refreshInProgress
                                || (Integer) readField(param.thisObject, "mId")
                                != LIGHT_ID_BACKLIGHT) {
                            return;
                        }
                        if ((Integer) param.args[0] != 0 && !frontLightClampLogged) {
                            frontLightClampLogged = true;
                            EpdWallpaperHook.log(
                                    "daily lock refresh suppressed a front-light request");
                        }
                        param.args[0] = 0;
                    }
                });

        // PowerManagerService owns an OFF request while the device is asleep.  The display-ready
        // callback otherwise replays that stale request a few milliseconds after our ON request
        // and closes SurfaceFlinger before the e-ink frame is committed.  Ignore only those
        // replays during the pulse; requestDisplayState() marks our intentional ON/OFF calls.
        Class<?> displayPowerControllerClass = Class.forName(
                "com.android.server.display.DisplayPowerController", false, classLoader);
        Class<?> requestClass = Class.forName(
                "android.hardware.display.DisplayManagerInternal$DisplayPowerRequest",
                false, classLoader);
        XposedHelpers.findAndHookMethod(displayPowerControllerClass, "requestPowerState",
                requestClass, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        if (!refreshInProgress || issuingDisplayPulseRequest) {
                            return;
                        }
                        int policy = (Integer) readField(param.args[0], "policy");
                        if (policy != DISPLAY_POLICY_OFF) {
                            return;
                        }
                        if (!staleOffRequestLogged) {
                            staleOffRequestLogged = true;
                            EpdWallpaperHook.log(
                                    "daily lock refresh ignored the stale asleep OFF request");
                        }
                        param.setResult(true);
                    }
                });

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

        XposedHelpers.findAndHookMethod(serviceClass, "showSuspendLogo",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        removeDailyOverlay();
                    }
                });
        XposedHelpers.findAndHookMethod(serviceClass, "hideSuspendLogo",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        removeDailyOverlay();
                    }
                });
        XposedHelpers.findAndHookMethod(serviceClass, "wakeUpInternal",
                long.class, String.class, int.class, String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        abortDisplayPulseForWake();
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
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Object displayManager = readField(service, "mDisplayManagerInternal");
        Object displayRequest = readField(service, "mDisplayPowerRequest");
        if (alarms == null) {
            throw new IllegalStateException("AlarmManager is unavailable");
        }
        if (power == null) {
            throw new IllegalStateException("PowerManager is unavailable");
        }
        if (windows == null) {
            throw new IllegalStateException("WindowManager is unavailable");
        }
        if (displayManager == null || displayRequest == null) {
            throw new IllegalStateException("DisplayManagerInternal is unavailable");
        }

        Class<?> requestClass = Class.forName(
                "android.hardware.display.DisplayManagerInternal$DisplayPowerRequest",
                false, service.getClass().getClassLoader());
        Constructor<?> requestCopyConstructor = requestClass.getConstructor(requestClass);
        requestCopyConstructor.setAccessible(true);
        Method requestMethod = findCompatibleMethod(displayManager.getClass(),
                "requestPowerState", 2);

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
        serviceHandler = handler;
        windowManager = windows;
        displayPowerRequestClass = requestClass;
        displayPowerRequestCopyConstructor = requestCopyConstructor;
        displayManagerInternal = displayManager;
        requestPowerStateMethod = requestMethod;
        cpuWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "EpdWallpaper:DailyRefresh");
        cpuWakeLock.setReferenceCounted(false);
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
        if (refreshInProgress) {
            EpdWallpaperHook.log("daily lock refresh skipped: refresh already in progress");
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

        final LinearLayout overlay = new LinearLayout(systemContext);
        overlay.setBackgroundColor(Color.WHITE);
        overlay.setOrientation(LinearLayout.VERTICAL);
        overlay.setSystemUiVisibility(0x706);
        overlay.addView(replacement, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                2006,
                0x10500,
                2);
        overlayParams.setTitle("EpdDailyLockRefresh");
        overlayParams.token = new Binder();
        Method setRefreshMode = findMethod(overlayParams.getClass(),
                "setRefreshMode", int.class);
        setRefreshMode.invoke(overlayParams, 4);

        refreshInProgress = true;
        frontLightClampLogged = false;
        staleOffRequestLogged = false;
        cpuWakeLock.acquire(CPU_WAKE_TIMEOUT_MILLIS);
        try {
            removeDailyOverlay();
            dailyOverlay = overlay;
            windowManager.addView(overlay, overlayParams);
            systemContext.sendBroadcast(new Intent("android.eink.force.refresh"));
            EpdWallpaperHook.log("daily lock screen attached on a fresh overlay surface");
        } catch (Throwable error) {
            removeDailyOverlay();
            finishDisplayPulse();
            throw error;
        }

        boolean posted = serviceHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    PowerManager currentPower = (PowerManager) systemContext.getSystemService(
                            Context.POWER_SERVICE);
                    if (currentPower == null || currentPower.isInteractive()) {
                        EpdWallpaperHook.log("daily lock refresh aborted: display became interactive");
                        abortDisplayPulseForWake();
                        return;
                    }
                    requestDisplayState(DISPLAY_POLICY_BRIGHT);
                    EpdWallpaperHook.log(
                            "daily lock refresh requested ON composition with front light clamped off");

                    postActivePanelRefresh(overlay, FIRST_ACTIVE_REFRESH_MILLIS);
                    postOverlaySurfaceRecycle(overlay, overlayParams,
                            OVERLAY_SURFACE_RECYCLE_MILLIS);

                    if (!serviceHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            finishDisplayPulse();
                        }
                    }, DISPLAY_SETTLE_MILLIS)) {
                        finishDisplayPulse();
                    }
                } catch (Throwable error) {
                    EpdWallpaperHook.log("daily lock refresh draw failed", error);
                    finishDisplayPulse();
                }
            }
        }, DISPLAY_START_DELAY_MILLIS);
        if (!posted) {
            finishDisplayPulse();
            throw new IllegalStateException("unable to queue daily lock refresh frame");
        }
    }

    private static void postActivePanelRefresh(final View overlay, long delayMillis) {
        if (!serviceHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!refreshInProgress || dailyOverlay != overlay) {
                    return;
                }
                EpdWallpaperHook.forceEinkRefresh(overlay);
                systemContext.sendBroadcast(new Intent("android.eink.force.refresh"));
                EpdWallpaperHook.log("daily lock refresh submitted an active panel frame");
            }
        }, delayMillis)) {
            EpdWallpaperHook.log("unable to queue an active panel refresh");
        }
    }

    private static void postOverlaySurfaceRecycle(final View overlay,
            final WindowManager.LayoutParams overlayParams, long delayMillis) {
        if (!serviceHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (!refreshInProgress || dailyOverlay != overlay) {
                    return;
                }
                try {
                    // The Allwinner e-ink HWC commits the previous top-layer buffer when a new
                    // full-screen layer is introduced.  Reattach the same already-rendered view
                    // once, so the previous buffer is the target image rather than SuspendLogo.
                    windowManager.removeViewImmediate(overlay);
                    dailyOverlay = null;
                    windowManager.addView(overlay, overlayParams);
                    dailyOverlay = overlay;
                    EpdWallpaperHook.forceEinkRefresh(overlay);
                    systemContext.sendBroadcast(new Intent("android.eink.force.refresh"));
                    EpdWallpaperHook.log(
                            "daily lock refresh recycled the prepared overlay surface");
                } catch (Throwable error) {
                    EpdWallpaperHook.log("unable to recycle daily lock overlay surface", error);
                    finishDisplayPulse();
                }
            }
        }, delayMillis)) {
            EpdWallpaperHook.log("unable to queue daily lock overlay recycle");
        }
    }

    private static void requestDisplayState(int policy) throws Throwable {
        Object currentRequest = readField(powerManagerService, "mDisplayPowerRequest");
        if (!displayPowerRequestClass.isInstance(currentRequest)) {
            throw new IllegalStateException("unexpected display power request type");
        }
        Object request = displayPowerRequestCopyConstructor.newInstance(currentRequest);
        int logicalBrightness = policy == DISPLAY_POLICY_BRIGHT ? 1 : 0;
        writeIntField(request, "policy", policy);
        // A logical value of zero makes DisplayPowerController immediately turn SurfaceFlinger
        // back off.  Keep it at the lowest non-zero level so composition survives long enough
        // to submit the e-ink frame; the LightsService hook still forces hardware output to zero.
        writeIntField(request, "screenBrightness", logicalBrightness);
        writeIntField(request, "dozeScreenBrightness", logicalBrightness);
        writeIntField(request, "dozeScreenState",
                policy == DISPLAY_POLICY_BRIGHT ? Display.STATE_ON : Display.STATE_OFF);
        writeBooleanField(request, "useAutoBrightness", false);
        writeBooleanField(request, "boostScreenBrightness", false);
        issuingDisplayPulseRequest = true;
        try {
            requestPowerStateMethod.invoke(displayManagerInternal, request, false);
        } finally {
            issuingDisplayPulseRequest = false;
        }
    }

    private static void finishDisplayPulse() {
        if (!refreshInProgress) {
            releaseCpuWakeLock();
            return;
        }
        try {
            PowerManager power = (PowerManager) systemContext.getSystemService(
                    Context.POWER_SERVICE);
            if (power != null && power.isInteractive()) {
                refreshInProgress = false;
                EpdWallpaperHook.log(
                        "daily lock refresh left display control to an interactive wake");
                return;
            }
            requestDisplayState(DISPLAY_POLICY_OFF);
            EpdWallpaperHook.log("daily lock display state restored to OFF");
        } catch (Throwable error) {
            EpdWallpaperHook.log("unable to restore daily lock display state", error);
        } finally {
            refreshInProgress = false;
            releaseCpuWakeLock();
        }
    }

    private static void abortDisplayPulseForWake() {
        if (!refreshInProgress) {
            removeDailyOverlay();
            return;
        }
        // Run before PowerManagerService processes the wake so its real BRIGHT request is not
        // clamped and the user's normal front-light setting is restored by the system.
        refreshInProgress = false;
        removeDailyOverlay();
        releaseCpuWakeLock();
        EpdWallpaperHook.log("daily lock refresh aborted for an interactive wake");
    }

    private static void releaseCpuWakeLock() {
        if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
            cpuWakeLock.release();
        }
    }

    private static void removeDailyOverlay() {
        View overlay = dailyOverlay;
        dailyOverlay = null;
        if (overlay == null || windowManager == null) {
            return;
        }
        try {
            if (overlay.getParent() != null) {
                windowManager.removeViewImmediate(overlay);
            }
            EpdWallpaperHook.log("daily lock refresh overlay removed");
        } catch (Throwable error) {
            EpdWallpaperHook.log("unable to remove daily lock refresh overlay", error);
        }
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

    private static Method findCompatibleMethod(Class<?> instanceClass, String name,
                                               int parameterCount) throws Throwable {
        Class<?> type = instanceClass;
        while (type != null) {
            Method[] methods = type.getDeclaredMethods();
            for (Method method : methods) {
                if (name.equals(method.getName())
                        && method.getParameterTypes().length == parameterCount) {
                    method.setAccessible(true);
                    return method;
                }
            }
            type = type.getSuperclass();
        }
        throw new NoSuchMethodException(name);
    }

    private static Method findMethod(Class<?> instanceClass, String name) throws Throwable {
        return findMethod(instanceClass, name, new Class<?>[0]);
    }

    private static Method findMethod(Class<?> instanceClass, String name,
                                     Class<?>... parameterTypes) throws Throwable {
        Class<?> type = instanceClass;
        while (type != null) {
            try {
                Method method = type.getDeclaredMethod(name, parameterTypes);
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchMethodException(name);
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

    private static void writeIntField(Object instance, String name, int value) throws Throwable {
        Field field = findField(instance.getClass(), name);
        field.setInt(instance, value);
    }

    private static void writeBooleanField(Object instance, String name, boolean value)
            throws Throwable {
        Field field = findField(instance.getClass(), name);
        field.setBoolean(instance, value);
    }

    private static Field findField(Class<?> instanceClass, String name) throws Throwable {
        Class<?> type = instanceClass;
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }
}
