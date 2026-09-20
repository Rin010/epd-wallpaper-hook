package top.ximin.epdwallpaper;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class EpdWallpaperHook implements IXposedHookLoadPackage {
    private static final String TAG = "EpdWallpaperHook";
    private static final File ROOT = new File("/storage/emulated/0/Wallpaper");
    private static final Uri PROVIDER_URI = Uri.parse(
            "content://top.ximin.epdwallpaper.files");
    private static final Pattern LOCK_RESOURCE = Pattern.compile(
            "photo[1-8](?:_charging|_land)?", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Long> NEXT_INDEX = new HashMap<>();
    private static volatile boolean hookInstalled;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"android".equals(lpparam.packageName)) {
            return;
        }

        synchronized (EpdWallpaperHook.class) {
            if (hookInstalled) {
                return;
            }
            hookInstalled = true;
        }

        try {
            installVendorLockHook(lpparam.classLoader);
            installVendorShutdownHook(lpparam.classLoader);
            XposedHelpers.findAndHookMethod(
                    ImageView.class,
                    "setImageResource",
                    int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            replaceImageIfConfigured(param);
                        }
                    });
            log("vendor and resource hooks installed in package=" + lpparam.packageName
                    + " process=" + lpparam.processName);
        } catch (Throwable error) {
            log("unable to install hook", error);
        }
    }

    private static void installVendorLockHook(ClassLoader classLoader) throws Throwable {
        Class<?> lunarCalendarView = Class.forName(
                "android.widget.LunarCalendarView", false, classLoader);
        XposedHelpers.findAndHookConstructor(
                lunarCalendarView,
                Context.class,
                boolean.class,
                int.class,
                boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            View root = (View) param.thisObject;
                            ImageView background = findFirstImageView(root);
                            if (background == null) {
                                log("lock hook ran but LunarCalendarView contains no ImageView");
                                return;
                            }
                            boolean landscape = root.getResources().getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE;
                            applyConfiguredImage(background, "lock", landscape,
                                    "LunarCalendarView constructor");
                        } catch (Throwable error) {
                            log("vendor lock replacement failed; using stock image", error);
                        }
                    }
                });
    }

    private static void installVendorShutdownHook(ClassLoader classLoader) throws Throwable {
        final Class<?> phoneWindowManager = Class.forName(
                "com.android.server.policy.PhoneWindowManager", false, classLoader);
        XposedHelpers.findAndHookMethod(
                phoneWindowManager,
                "showShutDown",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Field imageField = phoneWindowManager.getDeclaredField("mShutdownBgImg");
                            imageField.setAccessible(true);
                            ImageView imageView = (ImageView) imageField.get(null);
                            if (imageView == null) {
                                log("shutdown hook ran but mShutdownBgImg is null");
                                return;
                            }

                            Field rebootField = phoneWindowManager.getDeclaredField("mReboot");
                            rebootField.setAccessible(true);
                            String category = rebootField.getBoolean(null) ? "reboot" : "shutdown";
                            boolean landscape = imageView.getResources().getConfiguration().orientation
                                    == Configuration.ORIENTATION_LANDSCAPE;
                            if (applyConfiguredImage(imageView, category, landscape,
                                    "PhoneWindowManager.showShutDown")) {
                                forceEinkRefresh(imageView);
                            }
                        } catch (Throwable error) {
                            log("vendor shutdown/reboot replacement failed; using stock image", error);
                        }
                    }
                });
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

    private static boolean applyConfiguredImage(
            ImageView imageView, String category, boolean landscape, String source)
            throws IOException {
        SelectedImage selected = chooseAccessibleImage(imageView.getContext(), category, landscape);
        if (selected == null) {
            File directory = new File(ROOT, category);
            log(source + " matched " + category + " but no readable image was found in "
                    + directory.getAbsolutePath() + " (exists=" + directory.exists()
                    + ", canRead=" + directory.canRead() + ')');
            return false;
        }

        Bitmap bitmap = selected.file != null
                ? decodeForDisplay(selected.file, imageView.getResources())
                : decodeForDisplay(selected.uri, imageView.getContext(), imageView.getResources());
        if (bitmap == null) {
            log(source + " could not decode " + selected.description
                    + "; using stock image");
            return false;
        }

        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setImageBitmap(bitmap);
        log(source + " replaced " + category + " image with "
                + selected.description);
        return true;
    }

    private static void forceEinkRefresh(ImageView imageView) {
        try {
            Method forceRefresh = View.class.getMethod("forceRefresh");
            forceRefresh.invoke(imageView);
        } catch (Throwable unavailable) {
            imageView.invalidate();
            log("View.forceRefresh unavailable after shutdown replacement; invalidated view instead");
        }
    }

    private static void replaceImageIfConfigured(XC_MethodHook.MethodHookParam param) {
        try {
            ImageView imageView = (ImageView) param.thisObject;
            int resourceId = (Integer) param.args[0];
            if (resourceId == 0) {
                return;
            }

            String resourceName;
            try {
                resourceName = imageView.getResources().getResourceEntryName(resourceId);
            } catch (Resources.NotFoundException ignored) {
                return;
            }

            String category = categoryForResource(resourceName);
            if (category == null) {
                return;
            }

            boolean landscape = resourceName.toLowerCase(Locale.US).endsWith("_land")
                    || imageView.getResources().getConfiguration().orientation
                    == Configuration.ORIENTATION_LANDSCAPE;
            if (applyConfiguredImage(imageView, category, landscape,
                    "resource " + resourceName)) {
                param.setResult(null);
            }
        } catch (Throwable error) {
            // This code runs in system_server. Any unexpected condition must fall back
            // to the stock resource instead of destabilizing the process.
            log("replacement failed; using stock resource", error);
        }
    }

    private static String categoryForResource(String resourceName) {
        String name = resourceName.toLowerCase(Locale.US);
        if (LOCK_RESOURCE.matcher(name).matches() || "default_standby_logo".equals(name)) {
            return "lock";
        }
        if ("shutdown_window_img".equals(name)
                || "shutdown_window_img_land".equals(name)
                || "shoutdown_display_img".equals(name)) {
            return "shutdown";
        }
        if ("reboot_window_img".equals(name) || "reboot_window_img_land".equals(name)) {
            return "reboot";
        }
        return null;
    }

    private static SelectedImage chooseAccessibleImage(
            Context context, String category, boolean landscape) throws IOException {
        File direct = chooseImage(category, landscape);
        if (direct != null) {
            return new SelectedImage(direct, null, direct.getAbsolutePath());
        }

        try {
            Bundle extras = new Bundle();
            extras.putBoolean("landscape", landscape);
            Bundle result = context.getContentResolver().call(
                    PROVIDER_URI, "select", category, extras);
            if (result == null) {
                return null;
            }
            Uri uri = result.getParcelable("uri");
            if (uri == null) {
                return null;
            }
            String name = result.getString("display_name", uri.getLastPathSegment());
            return new SelectedImage(null, uri, "/storage/emulated/0/Wallpaper/"
                    + category + "/" + name + " (provider)");
        } catch (Throwable error) {
            log("wallpaper provider failed for " + category, error);
            return null;
        }
    }

    private static File chooseImage(String category, boolean landscape) throws IOException {
        File categoryDirectory = new File(ROOT, category);
        File orientationDirectory = new File(
                categoryDirectory, landscape ? "landscape" : "portrait");

        File activeDirectory = hasImages(orientationDirectory)
                ? orientationDirectory : categoryDirectory;
        File[] images = listImages(activeDirectory);
        if (images.length == 0) {
            return null;
        }

        Arrays.sort(images, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int byName = left.getName().compareToIgnoreCase(right.getName());
                return byName != 0 ? byName : left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        });

        String rootPath = ROOT.getCanonicalPath() + File.separator;
        String key = category + ':' + (landscape ? "landscape" : "portrait")
                + ':' + activeDirectory.getCanonicalPath();
        long index;
        synchronized (NEXT_INDEX) {
            Long previous = NEXT_INDEX.get(key);
            index = previous == null ? System.nanoTime() : previous + 1L;
            NEXT_INDEX.put(key, index);
        }

        int selectedIndex = (int) Math.floorMod(index, (long) images.length);
        File selected = images[selectedIndex];
        String selectedPath = selected.getCanonicalPath();
        if (!selectedPath.startsWith(rootPath) || !selected.isFile() || !selected.canRead()) {
            log("rejected unreadable or out-of-tree path: " + selectedPath);
            return null;
        }
        return selected;
    }

    private static boolean hasImages(File directory) {
        return listImages(directory).length > 0;
    }

    private static File[] listImages(File directory) {
        if (!directory.isDirectory() || !directory.canRead()) {
            return new File[0];
        }
        File[] files = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                if (!file.isFile() || !file.canRead()) {
                    return false;
                }
                String name = file.getName().toLowerCase(Locale.US);
                return name.endsWith(".png")
                        || name.endsWith(".jpg")
                        || name.endsWith(".jpeg")
                        || name.endsWith(".webp")
                        || name.endsWith(".bmp");
            }
        });
        return files == null ? new File[0] : files;
    }

    private static Bitmap decodeForDisplay(File file, Resources resources) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        DisplayMetrics metrics = resources.getDisplayMetrics();
        int targetWidth = Math.max(1, metrics.widthPixels);
        int targetHeight = Math.max(1, metrics.heightPixels);
        int sampleSize = 1;
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth
                && bounds.outHeight / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        options.inScaled = false;
        return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
    }

    private static Bitmap decodeForDisplay(Uri uri, Context context, Resources resources) {
        ContentResolver resolver = context.getContentResolver();
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        ParcelFileDescriptor descriptor = null;
        try {
            descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor == null) {
                return null;
            }
            BitmapFactory.decodeFileDescriptor(descriptor.getFileDescriptor(), null, bounds);
        } catch (Throwable error) {
            log("unable to read image bounds through provider: " + uri, error);
            return null;
        } finally {
            closeQuietly(descriptor);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null;
        }

        DisplayMetrics metrics = resources.getDisplayMetrics();
        int targetWidth = Math.max(1, metrics.widthPixels);
        int targetHeight = Math.max(1, metrics.heightPixels);
        int sampleSize = 1;
        while (bounds.outWidth / (sampleSize * 2) >= targetWidth
                && bounds.outHeight / (sampleSize * 2) >= targetHeight) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        options.inScaled = false;
        try {
            descriptor = resolver.openFileDescriptor(uri, "r");
            return descriptor == null ? null : BitmapFactory.decodeFileDescriptor(
                    descriptor.getFileDescriptor(), null, options);
        } catch (Throwable error) {
            log("unable to decode image through provider: " + uri, error);
            return null;
        } finally {
            closeQuietly(descriptor);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor descriptor) {
        if (descriptor != null) {
            try {
                descriptor.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static final class SelectedImage {
        final File file;
        final Uri uri;
        final String description;

        SelectedImage(File file, Uri uri, String description) {
            this.file = file;
            this.uri = uri;
            this.description = description;
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
}
