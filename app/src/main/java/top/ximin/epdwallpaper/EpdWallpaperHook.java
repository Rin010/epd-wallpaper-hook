package top.ximin.epdwallpaper;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

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
            log("unified gallery hooks installed in " + lpparam.processName);
        } catch (Throwable error) {
            log("unable to install vendor hooks", error);
        }
        try {
            DailyLockRefresh.install(lpparam.classLoader);
        } catch (Throwable error) {
            log("daily lock refresh is unavailable on this firmware", error);
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
                        if (selection == null || !selection.system) {
                            return;
                        }
                        int resourceId = context.getResources().getIdentifier(
                                selection.resourceName, "drawable", "android");
                        if (resourceId != 0) {
                            param.args[2] = resourceId;
                        } else {
                            log("lock resource missing: " + selection.resourceName);
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
                                log("LunarCalendarView contains no ImageView");
                                return;
                            }
                            if (!selection.system && !applyCustomImage(background, selection)) {
                                return;
                            }
                            int textViews;
                            if (WallpaperConfig.DATE_OFF.equals(selection.dateMode)) {
                                textViews = setDateVisibility(root, background, View.GONE);
                            } else {
                                textViews = styleDateText(root, background, selection.dateMode);
                            }
                            log("lock selected " + selection.id + ", date="
                                    + selection.dateMode + ", textViews=" + textViews);
                        } catch (Throwable error) {
                            log("lock replacement failed; using constructed image", error);
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
                                return;
                            }
                            Field rebootField = phoneWindowManager.getDeclaredField("mReboot");
                            rebootField.setAccessible(true);
                            String category = rebootField.getBoolean(null)
                                    ? WallpaperConfig.REBOOT : WallpaperConfig.SHUTDOWN;
                            Selection selection = select(category);
                            if (selection == null) {
                                return;
                            }
                            boolean applied = selection.system
                                    ? applySystemImage(imageView, selection)
                                    : applyCustomImage(imageView, selection);
                            if (applied) {
                                forceEinkRefresh(imageView);
                                log(category + " selected " + selection.id);
                            }
                        } catch (Throwable error) {
                            log("shutdown/reboot replacement failed", error);
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
        int count = Math.max(0, Math.min(readInt(config, "item.count", 0), 2000));
        if (WallpaperConfig.LOCK.equals(category)) {
            ArrayList<Selection> candidates = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                String prefix = "item." + index + '.';
                if (readBoolean(config, prefix + "lock", false)) {
                    Selection selection = readSelection(config, prefix, true);
                    if (selection != null) {
                        candidates.add(selection);
                    }
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

        String selectedId = config.getProperty(category + ".selected");
        if (selectedId == null) {
            return null;
        }
        for (int index = 0; index < count; index++) {
            String prefix = "item." + index + '.';
            if (selectedId.equals(config.getProperty(prefix + "id"))) {
                return readSelection(config, prefix, false);
            }
        }
        return null;
    }

    private static Selection readSelection(
            Properties config, String prefix, boolean withDate) {
        String id = config.getProperty(prefix + "id");
        String type = config.getProperty(prefix + "type");
        String date = withDate
                ? normalizeDateMode(config.getProperty(prefix + "date.mode"))
                : WallpaperConfig.DATE_OFF;
        if (id == null) {
            return null;
        }
        if ("system".equals(type)) {
            String resource = config.getProperty(prefix + "resource");
            if (resource == null || resource.length() == 0) {
                return null;
            }
            return Selection.system(id, resource, date);
        }
        if (!"custom".equals(type)) {
            return null;
        }
        String fileName = config.getProperty(prefix + "file");
        if (fileName == null || !fileName.equals(new File(fileName).getName())) {
            return null;
        }
        File image = new File(SystemWallpaperStore.IMAGE_ROOT, fileName);
        return isSafeSystemImage(image) ? Selection.custom(id, image, date) : null;
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
            return "2".equals(properties.getProperty("format")) ? properties : null;
        } catch (IOException error) {
            log("unable to read system configuration", error);
            return null;
        }
    }

    static boolean isHookCategoryEnabled(String category) {
        Properties config = loadConfiguration();
        return config != null
                && readBoolean(config, "global.enabled", false)
                && readBoolean(config, "category." + category + ".enabled", false);
    }

    static boolean isDailyLockRefreshEnabled() {
        Properties config = loadConfiguration();
        return config != null
                && readBoolean(config, "daily.refresh.enabled", true)
                && readBoolean(config, "global.enabled", false)
                && readBoolean(config, "category." + WallpaperConfig.LOCK
                        + ".enabled", false);
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

    private static String normalizeDateMode(String mode) {
        if (WallpaperConfig.DATE_WHITE.equals(mode) || WallpaperConfig.DATE_OFF.equals(mode)) {
            return mode;
        }
        return WallpaperConfig.DATE_BLACK;
    }

    private static boolean applyCustomImage(ImageView imageView, Selection selection) {
        Bitmap bitmap = decodeForDisplay(selection.image, imageView.getResources());
        if (bitmap == null) {
            log("could not decode " + selection.id);
            return false;
        }
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        imageView.setImageBitmap(bitmap);
        return true;
    }

    private static boolean applySystemImage(ImageView imageView, Selection selection) {
        int id = imageView.getResources().getIdentifier(
                selection.resourceName, "drawable", "android");
        if (id == 0) {
            log("system resource missing: " + selection.resourceName);
            return false;
        }
        imageView.setScaleType(ImageView.ScaleType.FIT_XY);
        imageView.setImageResource(id);
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

    private static int setDateVisibility(View view, ImageView background, int visibility) {
        if (view == background) {
            return 0;
        }
        int count = 0;
        if (view instanceof TextView) {
            view.setVisibility(visibility);
            count++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                count += setDateVisibility(group.getChildAt(index), background, visibility);
            }
        }
        return count;
    }

    private static int styleDateText(View view, ImageView background, String mode) {
        if (view == background) {
            return 0;
        }
        int count = 0;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            boolean white = WallpaperConfig.DATE_WHITE.equals(mode);
            text.setVisibility(View.VISIBLE);
            text.setTextColor(white ? Color.WHITE : Color.BLACK);
            text.setShadowLayer(2f, 0f, 0f, white ? Color.BLACK : Color.WHITE);
            count++;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                count += styleDateText(group.getChildAt(index), background, mode);
            }
        }
        return count;
    }

    private static Bitmap decodeForDisplay(File image, Resources resources) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(image.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }
        DisplayMetrics metrics = resources.getDisplayMetrics();
        int sample = 1;
        while (bounds.outWidth / (sample * 2) >= metrics.widthPixels
                && bounds.outHeight / (sample * 2) >= metrics.heightPixels) {
            sample *= 2;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        options.inScaled = false;
        return BitmapFactory.decodeFile(image.getAbsolutePath(), options);
    }

    static void forceEinkRefresh(View view) {
        try {
            Method forceRefresh = View.class.getMethod("forceRefresh");
            forceRefresh.invoke(view);
        } catch (Throwable unavailable) {
            view.invalidate();
        }
    }

    static void log(String message) {
        Log.i(TAG, message);
        XposedBridge.log(TAG + ": " + message);
    }

    static void log(String message, Throwable error) {
        Log.e(TAG, message, error);
        XposedBridge.log(TAG + ": " + message + "\n" + Log.getStackTraceString(error));
    }

    private static final class Selection {
        final String id;
        final boolean system;
        final File image;
        final String resourceName;
        final String dateMode;

        private Selection(String id, boolean system, File image,
                String resourceName, String dateMode) {
            this.id = id;
            this.system = system;
            this.image = image;
            this.resourceName = resourceName;
            this.dateMode = dateMode;
        }

        static Selection system(String id, String resourceName, String dateMode) {
            return new Selection(id, true, null, resourceName, dateMode);
        }

        static Selection custom(String id, File image, String dateMode) {
            return new Selection(id, false, image, null, dateMode);
        }
    }
}
