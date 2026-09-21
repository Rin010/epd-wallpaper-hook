package top.ximin.epdwallpaper;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class SystemWallpaperStore {
    static final String ROOT = "/data/system/epd-wallpaper";
    static final String IMAGE_ROOT = ROOT + "/images";
    static final String CONFIG = ROOT + "/config.properties";

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();

    interface Callback {
        void onComplete(boolean success, String message);
    }

    private SystemWallpaperStore() {
    }

    static void syncAllAsync(final Context context, final SharedPreferences preferences,
            final Callback callback) {
        final Context appContext = context.getApplicationContext();
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureDirectories();
                    for (File image : WallpaperConfig.listImages(appContext)) {
                        installImage(image);
                    }
                    writeConfiguration(appContext, preferences);
                    notifyCallback(callback, true, "系统图库已同步");
                } catch (Throwable error) {
                    notifyCallback(callback, false, describe(error));
                }
            }
        });
    }

    static void syncConfigurationAsync(
            final Context context, final SharedPreferences preferences) {
        final Context appContext = context.getApplicationContext();
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    ensureDirectories();
                    writeConfiguration(appContext, preferences);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    static void removeImageAsync(final Context context, final File image,
            final SharedPreferences preferences) {
        final Context appContext = context.getApplicationContext();
        final String safeName = image.getName();
        WRITER.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    runRoot("rm -f " + quote(IMAGE_ROOT + '/' + safeName));
                    writeConfiguration(appContext, preferences);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static void ensureDirectories() throws IOException, InterruptedException {
        runRoot("mkdir -p " + quote(IMAGE_ROOT)
                + " && chown system:system " + quote(ROOT) + ' ' + quote(IMAGE_ROOT)
                + " && chmod 0750 " + quote(ROOT) + ' ' + quote(IMAGE_ROOT)
                + " && restorecon -RF " + quote(ROOT));
    }

    private static void installImage(File source) throws IOException, InterruptedException {
        String destination = IMAGE_ROOT + '/' + source.getName();
        String temporary = destination + ".new";
        runRoot("cp " + quote(source.getAbsolutePath()) + ' ' + quote(temporary)
                + " && chown system:system " + quote(temporary)
                + " && chmod 0640 " + quote(temporary)
                + " && restorecon -F " + quote(temporary)
                + " && mv -f " + quote(temporary) + ' ' + quote(destination));
    }

    private static void writeConfiguration(Context context, SharedPreferences preferences)
            throws IOException, InterruptedException {
        Properties properties = new Properties();
        properties.setProperty("format", "2");
        properties.setProperty("global.enabled", Boolean.toString(
                WallpaperConfig.isGlobalEnabled(preferences)));
        for (String category : new String[] {
                WallpaperConfig.LOCK, WallpaperConfig.SHUTDOWN, WallpaperConfig.REBOOT}) {
            properties.setProperty("category." + category + ".enabled", Boolean.toString(
                    WallpaperConfig.isCategoryEnabled(preferences, category)));
        }
        properties.setProperty("shutdown.selected",
                WallpaperConfig.getSelectedItem(preferences, WallpaperConfig.SHUTDOWN));
        properties.setProperty("reboot.selected",
                WallpaperConfig.getSelectedItem(preferences, WallpaperConfig.REBOOT));

        List<WallpaperItem> items = WallpaperItem.all(context);
        properties.setProperty("item.count", Integer.toString(items.size()));
        for (int index = 0; index < items.size(); index++) {
            WallpaperItem item = items.get(index);
            String prefix = "item." + index + '.';
            properties.setProperty(prefix + "id", item.id);
            properties.setProperty(prefix + "type", item.system ? "system" : "custom");
            if (item.system) {
                properties.setProperty(prefix + "resource", item.resourceName);
            } else {
                properties.setProperty(prefix + "file", item.file.getName());
            }
            properties.setProperty(prefix + "lock", Boolean.toString(
                    WallpaperConfig.isLockSelected(preferences, item)));
            properties.setProperty(prefix + "date.mode",
                    WallpaperConfig.getDateMode(preferences, item));
        }

        File staging = new File(context.getCacheDir(), "wallpaper-config.properties");
        FileOutputStream output = new FileOutputStream(staging);
        try {
            properties.store(output, "E-paper wallpaper manager");
            output.getFD().sync();
        } finally {
            output.close();
        }
        String temporary = CONFIG + ".new";
        runRoot("cp " + quote(staging.getAbsolutePath()) + ' ' + quote(temporary)
                + " && chown system:system " + quote(temporary)
                + " && chmod 0640 " + quote(temporary)
                + " && restorecon -F " + quote(temporary)
                + " && mv -f " + quote(temporary) + ' ' + quote(CONFIG));
        staging.delete();
    }

    private static void runRoot(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true).start();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream()));
        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (output.length() < 2048) {
                output.append(line).append('\n');
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("Root 同步失败（" + exit + "）：" + output.toString().trim());
        }
    }

    private static String quote(String value) {
        return '\'' + value.replace("'", "'\\''") + '\'';
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.length() == 0
                ? error.getClass().getSimpleName() : message;
    }

    private static void notifyCallback(
            Callback callback, boolean success, String message) {
        if (callback != null) {
            callback.onComplete(success, message);
        }
    }
}
