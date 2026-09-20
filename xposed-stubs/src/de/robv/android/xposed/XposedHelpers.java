package de.robv.android.xposed;

public final class XposedHelpers {
    private XposedHelpers() {}

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> clazz, String methodName, Object... parameterTypesAndCallback) {
        return null;
    }

    public static XC_MethodHook.Unhook findAndHookConstructor(
            Class<?> clazz, Object... parameterTypesAndCallback) {
        return null;
    }
}
