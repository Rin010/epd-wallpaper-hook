package top.ximin.epdwallpaper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

final class WallpaperConfig {
    static final String PREFS = "wallpaper_config";
    static final File LEGACY_ROOT = new File("/storage/emulated/0/Wallpaper");
    private static final String LIBRARY_DIRECTORY = "wallpapers";

    static final String LOCK = "lock";
    static final String SHUTDOWN = "shutdown";
    static final String REBOOT = "reboot";
    static final String DAILY_REFRESH_ENABLED = "daily_refresh_enabled";
    static final String[] LOCK_SYSTEM_IMAGES = {"photo5", "photo6", "photo7", "photo8"};
    static final String DATE_BLACK = "black";
    static final String DATE_WHITE = "white";
    static final String DATE_OFF = "off";

    private WallpaperConfig() {
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void migrateUnifiedModel(Context context, SharedPreferences preferences) {
        if (preferences.getBoolean("unified_model_v2", false)) {
            return;
        }
        SharedPreferences.Editor editor = preferences.edit();
        List<WallpaperItem> items = WallpaperItem.all(context);
        String shutdownSelection = null;
        String rebootSelection = null;
        for (WallpaperItem item : items) {
            boolean selectedForLock;
            String dateMode;
            if (item.system) {
                selectedForLock = WallpaperItem.isStockLockResource(item.resourceName)
                        && preferences.getBoolean(
                        "system_enabled:" + LOCK + ':' + item.resourceName, true);
                dateMode = oldSystemDateMode(preferences, item.resourceName);
            } else {
                String oldId = legacyCustomId(item.file);
                boolean enabled = preferences.getBoolean("custom_enabled:" + oldId, true);
                selectedForLock = enabled && preferences.getBoolean(
                        "custom_role:" + LOCK + ':' + oldId, true);
                dateMode = oldCustomDateMode(preferences, item.file);
                if (enabled && preferences.getBoolean(
                        "custom_role:" + SHUTDOWN + ':' + oldId, false)
                        && shutdownSelection == null) {
                    shutdownSelection = item.id;
                }
                if (enabled && preferences.getBoolean(
                        "custom_role:" + REBOOT + ':' + oldId, false)
                        && rebootSelection == null) {
                    rebootSelection = item.id;
                }
            }
            editor.putBoolean(lockKey(item.id), selectedForLock);
            editor.putString(dateKey(item.id), dateMode);
        }
        if (shutdownSelection == null) {
            shutdownSelection = WallpaperItem.systemId("shutdown_window_img");
        }
        if (rebootSelection == null) {
            rebootSelection = WallpaperItem.systemId("reboot_window_img");
        }
        editor.putString(selectionKey(SHUTDOWN), shutdownSelection);
        editor.putString(selectionKey(REBOOT), rebootSelection);
        editor.putBoolean("unified_model_v2", true);
        editor.commit();
    }

    static boolean isGlobalEnabled(SharedPreferences preferences) {
        return preferences.getBoolean("global_enabled", true);
    }

    static boolean isCategoryEnabled(SharedPreferences preferences, String category) {
        return preferences.getBoolean("category_enabled:" + category, true);
    }

    static boolean isDailyRefreshEnabled(SharedPreferences preferences) {
        return preferences.getBoolean(DAILY_REFRESH_ENABLED, true);
    }

    static boolean isLockSelected(SharedPreferences preferences, WallpaperItem item) {
        return preferences.getBoolean(lockKey(item.id),
                item.system && WallpaperItem.isStockLockResource(item.resourceName));
    }

    static void setLockSelected(SharedPreferences preferences,
            WallpaperItem item, boolean selected) {
        preferences.edit().putBoolean(lockKey(item.id), selected).apply();
    }

    static String getDateMode(SharedPreferences preferences, WallpaperItem item) {
        return normalizeDateMode(preferences.getString(dateKey(item.id), DATE_BLACK));
    }

    static void setDateMode(SharedPreferences preferences,
            WallpaperItem item, String mode) {
        preferences.edit().putString(dateKey(item.id), normalizeDateMode(mode)).apply();
    }

    static String nextDateMode(String mode) {
        mode = normalizeDateMode(mode);
        if (DATE_BLACK.equals(mode)) {
            return DATE_WHITE;
        }
        if (DATE_WHITE.equals(mode)) {
            return DATE_OFF;
        }
        return DATE_BLACK;
    }

    static String getSelectedItem(SharedPreferences preferences, String category) {
        String fallback = SHUTDOWN.equals(category)
                ? WallpaperItem.systemId("shutdown_window_img")
                : WallpaperItem.systemId("reboot_window_img");
        return preferences.getString(selectionKey(category), fallback);
    }

    static void setSelectedItem(SharedPreferences preferences,
            String category, WallpaperItem item) {
        preferences.edit().putString(selectionKey(category), item.id).apply();
    }

    static void forgetCustomItem(SharedPreferences preferences, File file) {
        String id = WallpaperItem.customId(file);
        SharedPreferences.Editor editor = preferences.edit()
                .remove(lockKey(id)).remove(dateKey(id)).remove(infoKey(id));
        if (id.equals(getSelectedItem(preferences, SHUTDOWN))) {
            editor.putString(selectionKey(SHUTDOWN),
                    WallpaperItem.systemId("shutdown_window_img"));
        }
        if (id.equals(getSelectedItem(preferences, REBOOT))) {
            editor.putString(selectionKey(REBOOT),
                    WallpaperItem.systemId("reboot_window_img"));
        }
        editor.apply();
    }

    static String getCustomInfo(SharedPreferences preferences, File file) {
        return preferences.getString(infoKey(WallpaperItem.customId(file)), "应用内图片");
    }

    static void initializeImportedItem(
            SharedPreferences preferences, ImageImporter.Result result) {
        String id = WallpaperItem.customId(result.file);
        preferences.edit()
                .putBoolean(lockKey(id), true)
                .putString(dateKey(id), DATE_OFF)
                .putString(infoKey(id), result.description())
                .commit();
    }

    static void initializeMigratedItem(SharedPreferences preferences, File legacy,
            ImageImporter.Result result) {
        String oldId = legacyCustomId(legacy);
        String newId = WallpaperItem.customId(result.file);
        preferences.edit()
                .putBoolean(lockKey(newId), preferences.getBoolean(
                        "custom_role:" + LOCK + ':' + oldId, true))
                .putString(dateKey(newId), oldCustomDateMode(preferences, legacy))
                .putString(infoKey(newId), result.description() + " · 已从旧目录迁移")
                .commit();
    }

    static String normalizeDateMode(String mode) {
        if (DATE_WHITE.equals(mode) || DATE_OFF.equals(mode)) {
            return mode;
        }
        return DATE_BLACK;
    }

    private static String oldSystemDateMode(
            SharedPreferences preferences, String resourceName) {
        String modeKey = "system_date_mode:" + resourceName;
        if (preferences.contains(modeKey)) {
            return normalizeDateMode(preferences.getString(modeKey, DATE_BLACK));
        }
        return preferences.getBoolean("system_date:" + resourceName, true)
                ? DATE_BLACK : DATE_OFF;
    }

    private static String oldCustomDateMode(SharedPreferences preferences, File file) {
        String oldId = legacyCustomId(file);
        String key = "custom_date_mode:" + oldId;
        if (preferences.contains(key)) {
            return normalizeDateMode(preferences.getString(key, DATE_BLACK));
        }
        return preferences.getBoolean("custom_date:" + oldId, true)
                ? DATE_BLACK : DATE_OFF;
    }

    private static String lockKey(String id) {
        return "item_lock:" + id;
    }

    private static String dateKey(String id) {
        return "item_date:" + id;
    }

    private static String infoKey(String id) {
        return "item_info:" + id;
    }

    private static String selectionKey(String category) {
        return "item_selected:" + category;
    }

    private static String legacyCustomId(File file) {
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
                return byName != 0 ? byName
                        : left.getAbsolutePath().compareTo(right.getAbsolutePath());
            }
        });
        return files;
    }

    static void assertUnderRoot(Context context, File file) throws IOException {
        String rootPath = library(context).getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("path escaped wallpaper root");
        }
    }

    static boolean isSupportedImage(File file) {
        String name = file.getName().toLowerCase(java.util.Locale.US);
        return name.endsWith(".png") || name.endsWith(".jpg")
                || name.endsWith(".jpeg") || name.endsWith(".webp")
                || name.endsWith(".bmp");
    }
}
