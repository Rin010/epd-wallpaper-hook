package top.ximin.epdwallpaper;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Process;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class WallpaperProvider extends ContentProvider {
    public static final String AUTHORITY = "top.ximin.epdwallpaper.files";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY);

    private static final File ROOT = new File("/storage/emulated/0/Wallpaper");
    private static final Map<String, Long> NEXT_INDEX = new HashMap<>();

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        enforceSystemCaller();
        if (!"select".equals(method) || !isCategory(arg)) {
            return super.call(method, arg, extras);
        }

        boolean landscape = extras != null && extras.getBoolean("landscape", false);
        Bundle result = new Bundle();
        try {
            Selection selection = chooseImage(arg, landscape);
            if (selection == null) {
                return result;
            }
            Uri uri = CONTENT_URI.buildUpon()
                    .appendPath("image")
                    .appendPath(arg)
                    .appendPath(selection.slot)
                    .appendPath(selection.file.getName())
                    .build();
            result.putParcelable("uri", uri);
            result.putString("display_name", selection.file.getName());
        } catch (IOException ignored) {
            // The hook treats an empty result as "use the stock image".
        }
        return result;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        enforceSystemCaller();
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read-only provider");
        }

        File file;
        try {
            file = resolveImage(uri);
        } catch (IOException error) {
            throw new FileNotFoundException(error.getMessage());
        }
        if (!file.isFile() || !file.canRead() || !isSupportedImage(file)) {
            throw new FileNotFoundException("unreadable image");
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    private static void enforceSystemCaller() {
        int caller = Binder.getCallingUid();
        if (caller != Process.SYSTEM_UID && caller != Process.myUid()) {
            throw new SecurityException("only system_server may read wallpaper images");
        }
    }

    private static Selection chooseImage(String category, boolean landscape) throws IOException {
        File categoryDirectory = new File(ROOT, category);
        String orientation = landscape ? "landscape" : "portrait";
        File orientationDirectory = new File(categoryDirectory, orientation);
        File activeDirectory;
        String slot;
        if (listImages(orientationDirectory).length > 0) {
            activeDirectory = orientationDirectory;
            slot = orientation;
        } else {
            activeDirectory = categoryDirectory;
            slot = "base";
        }

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

        String key = category + ':' + orientation + ':' + activeDirectory.getCanonicalPath();
        long index;
        synchronized (NEXT_INDEX) {
            Long previous = NEXT_INDEX.get(key);
            index = previous == null ? System.nanoTime() : previous + 1L;
            NEXT_INDEX.put(key, index);
        }
        int selectedIndex = (int) Math.floorMod(index, (long) images.length);
        File selected = images[selectedIndex];
        assertUnderRoot(selected);
        return new Selection(selected, slot);
    }

    private static File resolveImage(Uri uri) throws IOException, FileNotFoundException {
        if (!AUTHORITY.equals(uri.getAuthority())) {
            throw new FileNotFoundException("wrong authority");
        }
        java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() != 4 || !"image".equals(segments.get(0))) {
            throw new FileNotFoundException("malformed image URI");
        }
        String category = segments.get(1);
        String slot = segments.get(2);
        if (!isCategory(category)
                || !("base".equals(slot) || "portrait".equals(slot) || "landscape".equals(slot))) {
            throw new FileNotFoundException("invalid image URI");
        }
        File directory = new File(ROOT, category);
        if (!"base".equals(slot)) {
            directory = new File(directory, slot);
        }
        File file = new File(directory, segments.get(3));
        assertUnderRoot(file);
        return file;
    }

    private static void assertUnderRoot(File file) throws IOException {
        String rootPath = ROOT.getCanonicalPath() + File.separator;
        if (!file.getCanonicalPath().startsWith(rootPath)) {
            throw new IOException("path escaped wallpaper root");
        }
    }

    private static File[] listImages(File directory) {
        if (!directory.isDirectory() || !directory.canRead()) {
            return new File[0];
        }
        File[] files = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File file) {
                return file.isFile() && file.canRead() && isSupportedImage(file);
            }
        });
        return files == null ? new File[0] : files;
    }

    private static boolean isSupportedImage(File file) {
        String name = file.getName().toLowerCase(Locale.US);
        return name.endsWith(".png")
                || name.endsWith(".jpg")
                || name.endsWith(".jpeg")
                || name.endsWith(".webp")
                || name.endsWith(".bmp");
    }

    private static boolean isCategory(String category) {
        return "lock".equals(category)
                || "shutdown".equals(category)
                || "reboot".equals(category);
    }

    @Override
    public String getType(Uri uri) {
        return "image/*";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
            String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection,
            String[] selectionArgs) {
        throw new UnsupportedOperationException("read-only provider");
    }

    private static final class Selection {
        final File file;
        final String slot;

        Selection(File file, String slot) {
            this.file = file;
            this.slot = slot;
        }
    }
}
