package top.ximin.epdwallpaper;

import android.os.PowerManager;
import android.os.SystemClock;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Uses the firmware's ordinary PowerManager wake and sleep paths. */
final class SystemSleepCycle {
    private static final String WAKE_REASON = "EpdWallpaper:DailyRefresh";
    private static final int GO_TO_SLEEP_REASON_APPLICATION = 0;
    private static final int GO_TO_SLEEP_FLAGS_NONE = 0;

    private final PowerManager powerManager;
    private final Method wakeUp;
    private final Method goToSleep;

    SystemSleepCycle(PowerManager powerManager) throws NoSuchMethodException {
        this.powerManager = powerManager;
        wakeUp = PowerManager.class.getMethod("wakeUp", long.class, String.class);
        goToSleep = PowerManager.class.getMethod(
                "goToSleep", long.class, int.class, int.class);
    }

    boolean isInteractive() {
        return powerManager.isInteractive();
    }

    void wake() throws Throwable {
        invoke(wakeUp, Long.valueOf(SystemClock.uptimeMillis()), WAKE_REASON);
    }

    void sleep() throws Throwable {
        invoke(goToSleep, Long.valueOf(SystemClock.uptimeMillis()),
                Integer.valueOf(GO_TO_SLEEP_REASON_APPLICATION),
                Integer.valueOf(GO_TO_SLEEP_FLAGS_NONE));
    }

    private void invoke(Method method, Object... arguments) throws Throwable {
        try {
            method.invoke(powerManager, arguments);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        }
    }
}
