package top.ximin.epdwallpaper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;

final class WallpaperConfig {
    static final String PREFS = "wallpaper_config";
    static final File LEGACY_ROOT = new File("/storage/emulated/0/Wallpaper");
    private static final String LIBRARY_DIRECTORY = "wallpapers";

    static final String LOCK = "lock";
    static final String SHUTDOWN = "shutdown";
    static final String REBOOT = "reboot";
    static final String SYSTEM_DEFAULT = "default";
    static final String[] LOCK_SYSTEM_IMAGES = {"photo5", "photo6", "photo7", "photo8"};

    private WallpaperConfig() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isGlobalEnabled(SharedPreferences preferences) {
        return preferences.getBoolean("global_enabled", true);
    }

    static boolean isCategoryEnabled(SharedPreferences preferences, String category) {
        return preferences.getBoolean("category_enabled:" + category, true);
    }

    static boolean isSystemItemEnabled(
            SharedPreferences preferences, String category, String resourceName) {
        return preferences.getBoolean(
                "system_enabled:" + category + ':' + resourceName, true);
    }

    static void setSystemItemEnabled(SharedPreferences preferences, String category,
            String resourceName, boolean enabled) {
        preferences.edit().putBoolean(
                "system_enabled:" + category + ':' + resourceName, enabled).apply();
    }

    static boolean isCustomItemEnabled(SharedPreferences preferences, File file) {
        return preferences.getBoolean("custom_enabled:" + customId(file), false);
    }

    static void setCustomItemEnabled(
            SharedPreferences preferences, File file, boolean enabled) {
        preferences.edit().putBoolean("custom_enabled:" + customId(file), enabled).apply();
    }

    static boolean isCustomRoleEnabled(
            SharedPreferences preferences, File file, String category) {
        return preferences.getBoolean(
                "custom_role:" + category + ':' + customId(file), false);
    }

    static void setCustomRoleEnabled(SharedPreferences preferences, File file,
            String category, boolean enabled) {
        preferences.edit().putBoolean(
                "custom_role:" + category + ':' + customId(file), enabled).apply();
    }

    static boolean isSystemDateEnabled(
            SharedPreferences preferences, String resourceName) {
        return preferences.getBoolean("system_date:" + resourceName, true);
    }

    static void setSystemDateEnabled(
            SharedPreferences preferences, String resourceName, boolean enabled) {
        preferences.edit().putBoolean("system_date:" + resourceName, enabled).apply();
    }

    static boolean isCustomDateEnabled(SharedPreferences preferences, File file) {
        return preferences.getBoolean("custom_date:" + customId(file), true);
    }

    static void setCustomDateEnabled(
            SharedPreferences preferences, File file, boolean enabled) {
        preferences.edit().putBoolean("custom_date:" + customId(file), enabled).apply();
    }

    static void forgetCustomItem(SharedPreferences preferences, File file) {
        String id = customId(file);
        preferences.edit()
                .remove("custom_enabled:" + id)
                .remove("custom_date:" + id)
                .remove("custom_info:" + id)
                .remove("custom_role:" + LOCK + ':' + id)
                .remove("custom_role:" + SHUTDOWN + ':' + id)
                .remove("custom_role:" + REBOOT + ':' + id)
                .apply();
    }

    static String getCustomInfo(SharedPreferences preferences, File file) {
        return preferences.getString("custom_info:" + customId(file), "应用内图片");
    }

    static void initializeImportedItem(
            SharedPreferences preferences, ImageImporter.Result result) {
        String id = customId(result.file);
        preferences.edit()
                .putBoolean("custom_enabled:" + id, true)
                .putBoolean("custom_date:" + id, true)
                .putBoolean("custom_role:" + LOCK + ':' + id, true)
                .putBoolean("custom_role:" + SHUTDOWN + ':' + id, false)
                .putBoolean("custom_role:" + REBOOT + ':' + id, false)
                .putString("custom_info:" + id, result.description())
                .commit();
    }

    static void initializeMigratedItem(SharedPreferences preferences, File legacy,
            ImageImporter.Result result) {
        String oldId = customId(legacy);
        String newId = customId(result.file);
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean("custom_enabled:" + newId,
                        preferences.getBoolean("custom_enabled:" + oldId, true))
                .putBoolean("custom_date:" + newId,
                        preferences.getBoolean("custom_date:" + oldId, true))
                .putBoolean("custom_role:" + LOCK + ':' + newId,
                        preferences.getBoolean("custom_role:" + LOCK + ':' + oldId, true))
                .putBoolean("custom_role:" + SHUTDOWN + ':' + newId,
                        preferences.getBoolean("custom_role:" + SHUTDOWN + ':' + oldId, false))
                .putBoolean("custom_role:" + REBOOT + ':' + newId,
                        preferences.getBoolean("custom_role:" + REBOOT + ':' + oldId, false))
                .putString("custom_info:" + newId, result.description() + " · 已从旧目录迁移");
        editor.commit();
    }

    static String customId(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    static File library(Context context) {
        return new File(context.getFilesDir(), LIBRARY_DIRECTORY);
    }

    static File[] listImages(Context context) {
        File root = library(context);
        if (!root.isDirectory() || !root.canRead()) {
            return new File[0];
        }
        File[] files = root.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.canRead() && isSupportedImage(file);
            }
        });
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                int byName = left.getName().compareToIgnoreCase(right.getName());
                return byName != 0
                        ? byName : left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        });
        return files;
    }

    static File resolveImage(Context context, String name) throws IOException {
        File file = new File(library(context), name);
        assertUnderRoot(context, file);
        return file;
    }

    static void assertUnderRoot(Context context, File file) throws IOException {
        String rootPath = library(context).getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("path escaped wallpaper root");
        }
    }

    static boolean isSupportedImage(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.US);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".webp")
                || name.endsWith(".bmp");
    }

    static boolean isCategory(String category) {
        return LOCK.equals(category) || SHUTDOWN.equals(category) || REBOOT.equals(category);
    }
}
