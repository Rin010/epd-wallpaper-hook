package top.ximin.epdwallpaper;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

final class WallpaperItem {
    static final String SYSTEM_PREFIX = "system:";
    static final String CUSTOM_PREFIX = "custom:";
    static final String[] SYSTEM_RESOURCES = {
            "photo5", "photo6", "photo7", "photo8",
            "shutdown_window_img", "reboot_window_img"
    };

    final String id;
    final String title;
    final boolean system;
    final String resourceName;
    final File file;

    private WallpaperItem(String id, String title, boolean system,
            String resourceName, File file) {
        this.id = id;
        this.title = title;
        this.system = system;
        this.resourceName = resourceName;
        this.file = file;
    }

    static WallpaperItem system(String resourceName, String title) {
        return new WallpaperItem(SYSTEM_PREFIX + resourceName, title,
                true, resourceName, null);
    }

    static WallpaperItem custom(File file) {
        return new WallpaperItem(CUSTOM_PREFIX + file.getName(), file.getName(),
                false, null, file);
    }

    static List<WallpaperItem> all(Context context) {
        ArrayList<WallpaperItem> items = new ArrayList<>();
        items.add(system("photo5", "系统锁屏 1"));
        items.add(system("photo6", "系统锁屏 2"));
        items.add(system("photo7", "系统锁屏 3"));
        items.add(system("photo8", "系统锁屏 4"));
        items.add(system("shutdown_window_img", "系统关机画面"));
        items.add(system("reboot_window_img", "系统重启画面"));
        for (File file : WallpaperConfig.listImages(context)) {
            items.add(custom(file));
        }
        return items;
    }

    static String systemId(String resourceName) {
        return SYSTEM_PREFIX + resourceName;
    }

    static String customId(File file) {
        return CUSTOM_PREFIX + file.getName();
    }

    static boolean isStockLockResource(String resourceName) {
        return "photo5".equals(resourceName) || "photo6".equals(resourceName)
                || "photo7".equals(resourceName) || "photo8".equals(resourceName);
    }
}
