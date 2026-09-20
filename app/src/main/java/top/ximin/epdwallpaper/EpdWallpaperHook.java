package top.ximin.epdwallpaper;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class EpdWallpaperHook implements IXposedHookLoadPackage {
    private static final String TAG = "EpdWallpaperHook";
    private static final ThreadLocal<Selection> LOCK_SELECTION = new ThreadLocal<>();
    private static final Map<String, Long> NEXT_INDEX = new HashMap<>();
    private static volatile boolean hooksInstalled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }
        synchronized (EpdWallpaperHook.class) {
            if (hooksInstalled) {
                return;
            }
            hooksInstalled = true;
        }
        try {
            installLockHook(lpparam.classLoader);
            installShutdownHook(lpparam.classLoader);
            log("exact vendor hooks installed with system-owned image store in package="
                    + lpparam.packageName + " process=" + lpparam.processName);
        } catch (Throwable error) {
            log("unable to install exact vendor hooks", error);
        }
    }

    private static void installLockHook(ClassLoader classLoader) throws Throwable {
        Class<?> lunarCalendarView = Class.forName(
                "android.widget.LunarCalendarView", false, classLoader);
        XposedHelpers.findAndHookConstructor(
                lunarCalendarView, Context.class, boolean.class, int.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        Selection selection = select(WallpaperConfig.LOCK);
                        LOCK_SELECTION.set(selection);
                        if (selection == null || !"system".equals(selection.mode)
                                || selection.resourceName == null
                                || WallpaperConfig.SYSTEM_DEFAULT.equals(selection.resourceName)) {
                            return;
                        }
                        int resourceId = context.getResources().getIdentifier(
                                selection.resourceName, "drawable", "android");
                        if (resourceId != 0) {
                            param.args[2] = resourceId;
                        } else {
                            log("system lock resource not found: " + selection.resourceName);
                        }
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Selection selection = LOCK_SELECTION.get();
                        LOCK_SELECTION.remove();
                        if (selection == null) {
                            return;
                        }
                        try {
                            View root = (View) param.thisObject;
                            ImageView background = findFirstImageView(root);
                            if (background == null) {
                                log("LunarCalendarView contains no background ImageView");
                                return;
                            }
                            if ("custom".equals(selection.mode)
                                    && !applyCustomImage(background, selection)) {
                                return;
                            }
                            if (!selection.showDate) {
                                hideNonImageLeaves(root, background);
                            }
                            log("lock selected " + selection.displayName
                                    + ", showDate=" + selection.showDate);
                        } catch (Throwable error) {
                            log("lock replacement failed; using stock image", error);
                        }
                    }
                });
    }

    private static void installShutdownHook(ClassLoader classLoader) throws Throwable {
        final Class<?> phoneWindowManager = Class.forName(
                "com.android.server.policy.PhoneWindowManager", false, classLoader);
        XposedHelpers.findAndHookMethod(phoneWindowManager, "showShutDown",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Field imageField = phoneWindowManager.getDeclaredField("mShutdownBgImg");
                            imageField.setAccessible(true);
                            ImageView imageView = (ImageView) imageField.get(null);
                            if (imageView == null) {
                                log("showShutDown completed without mShutdownBgImg");
                                return;
                            }
                            Field rebootField = phoneWindowManager.getDeclaredField("mReboot");
                            rebootField.setAccessible(true);
                            String category = rebootField.getBoolean(null)
                                    ? WallpaperConfig.REBOOT : WallpaperConfig.SHUTDOWN;
                            Selection selection = select(category);
                            if (selection == null || !"custom".equals(selection.mode)) {
                                return;
                            }
                            if (applyCustomImage(imageView, selection)) {
                                forceEinkRefresh(imageView);
                                log(category + " selected " + selection.displayName);
                            }
                        } catch (Throwable error) {
                            log("shutdown/reboot replacement failed; using stock image", error);
                        }
                    }
                });
    }

    private static Selection select(String category) {
        Properties config = loadConfiguration();
        if (config == null || !readBoolean(config, "global.enabled", false)
                || !readBoolean(config, "category." + category + ".enabled", false)) {
            return null;
        }
        ArrayList<Selection> candidates = new ArrayList<>();
        if (WallpaperConfig.LOCK.equals(category)) {
            for (String resource : WallpaperConfig.LOCK_SYSTEM_IMAGES) {
                if (readBoolean(config, "system.lock." + resource + ".enabled", true)) {
                    candidates.add(Selection.system(resource, readBoolean(config,
                            "system.lock." + resource + ".date", true)));
                }
            }
        } else if (readBoolean(config,
                "system." + category + ".default.enabled", true)) {
            candidates.add(Selection.system(WallpaperConfig.SYSTEM_DEFAULT, false));
        }
        int count = Math.max(0, Math.min(readInt(config, "custom.count", 0), 1000));
        for (int index = 0; index < count; index++) {
            String prefix = "custom." + index + '.';
            String fileName = config.getProperty(prefix + "file");
            if (fileName == null || !fileName.equals(new File(fileName).getName())
                    || !readBoolean(config, prefix + "enabled", false)
                    || !readBoolean(config, prefix + category, false)) {
                continue;
            }
            File image = new File(SystemWallpaperStore.IMAGE_ROOT, fileName);
            if (isSafeSystemImage(image)) {
                candidates.add(Selection.custom(image,
                        WallpaperConfig.LOCK.equals(category)
                                && readBoolean(config, prefix + "date", true)));
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        long next;
        synchronized (NEXT_INDEX) {
            Long previous = NEXT_INDEX.get(category);
            next = previous == null ? System.nanoTime() : previous + 1L;
            NEXT_INDEX.put(category, next);
        }
        return candidates.get((int) Math.floorMod(next, (long) candidates.size()));
    }

    private static Properties loadConfiguration() {
        File file = new File(SystemWallpaperStore.CONFIG);
        if (!file.isFile() || !file.canRead()) {
            return null;
        }
        Properties properties = new Properties();
        try {
            FileInputStream input = new FileInputStream(file);
            try {
                properties.load(input);
            } finally {
                input.close();
            }
            return "1".equals(properties.getProperty("format")) ? properties : null;
        } catch (IOException error) {
            log("unable to read system-owned configuration", error);
            return null;
        }
    }

    private static boolean isSafeSystemImage(File image) {
        try {
            String root = new File(SystemWallpaperStore.IMAGE_ROOT).getCanonicalPath()
                    + File.separator;
            return image.getCanonicalPath().startsWith(root)
                    && image.isFile() && image.canRead();
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean readBoolean(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value);
    }

    private static int readInt(Properties properties, String key, int fallback) {
        try {
            return Integer.parseInt(properties.getProperty(key));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static boolean applyCustomImage(ImageView imageView, Selection selection) {
        Bitmap bitmap = decodeForDisplay(selection.image, imageView.getResources());
        if (bitmap == null) {
            log("could not decode custom image " + selection.displayName
                    + "; using stock image");
            return false;
        }
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageBitmap(bitmap);
        return true;
    }

    private static ImageView findFirstImageView(View view) {
        if (view instanceof ImageView) {
            return (ImageView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                ImageView image = findFirstImageView(group.getChildAt(index));
                if (image != null) {
                    return image;
                }
            }
        }
        return null;
    }

    private static void hideNonImageLeaves(View view, ImageView background) {
        if (view == background) {
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                hideNonImageLeaves(group.getChildAt(index), background);
            }
        } else {
            view.setVisibility(View.GONE);
        }
    }

    private static Bitmap decodeForDisplay(File image, Resources resources) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        DisplayMetrics metrics = resources.getDisplayMetrics();
        int targetWidth = Math.max(1, metrics.widthPixels);
        int targetHeight = Math.max(1, metrics.heightPixels);
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= targetWidth
                && bounds.outHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        options.inScaled = false;
        return BitmapFactory.decodeFile(image.getAbsolutePath(), options);
    }

    private static void forceEinkRefresh(ImageView imageView) {
        try {
            Method forceRefresh = View.class.getMethod("forceRefresh");
            forceRefresh.invoke(imageView);
        } catch (Throwable unavailable) {
            imageView.invalidate();
            log("View.forceRefresh unavailable; invalidated shutdown view instead");
        }
    }

    private static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    private static void log(String message, Throwable error) {
        Log.e(TAG, message, error);
        XposedBridge.log(TAG + ": " + message + "\n" + Log.getStackTraceString(error));
    }

    private static final class Selection {
        final String mode;
        final File image;
        final String resourceName;
        final String displayName;
        final boolean showDate;

        private Selection(String mode, File image, String resourceName,
                String displayName, boolean showDate) {
            this.mode = mode;
            this.image = image;
            this.resourceName = resourceName;
            this.displayName = displayName;
            this.showDate = showDate;
        }

        static Selection system(String resourceName, boolean showDate) {
            return new Selection("system", null, resourceName, resourceName, showDate);
        }

        static Selection custom(File image, boolean showDate) {
            return new Selection("custom", image, null, image.getName(), showDate);
        }
    }
}
