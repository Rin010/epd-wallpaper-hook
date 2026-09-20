package top.ximin.epdwallpaper;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class ImageImporter {
    static final String MODE_FILL = "fill";
    static final String MODE_FIT = "fit";

    private ImageImporter() {
    }

    static Result importUri(Context context, Uri uri, String mode) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String sourceName = queryDisplayName(resolver, uri);

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream boundsInput = resolver.openInputStream(uri);
        if (boundsInput == null) {
            throw new IOException("无法打开图片");
        }
        try {
            BitmapFactory.decodeStream(boundsInput, null, bounds);
        } finally {
            boundsInput.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("不支持或损坏的图片");
        }

        int orientation = readOrientation(resolver, uri);
        int sourceWidth = bounds.outWidth;
        int sourceHeight = bounds.outHeight;
        boolean swapsAxes = orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE;

        DisplayMetrics metrics = realDisplayMetrics(context);
        int targetWidth = Math.max(1, metrics.widthPixels);
        int targetHeight = Math.max(1, metrics.heightPixels);
        int orientedWidth = swapsAxes ? sourceHeight : sourceWidth;
        int orientedHeight = swapsAxes ? sourceWidth : sourceHeight;
        int sample = calculateSample(orientedWidth, orientedHeight, targetWidth, targetHeight);

        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.RGB_565;
        decode.inDither = true;
        InputStream imageInput = resolver.openInputStream(uri);
        if (imageInput == null) {
            throw new IOException("无法再次打开图片");
        }
        Bitmap source;
        try {
            source = BitmapFactory.decodeStream(imageInput, null, decode);
        } finally {
            imageInput.close();
        }
        if (source == null) {
            throw new IOException("图片解码失败");
        }

        Bitmap oriented = applyOrientation(source, orientation);
        if (oriented != source) {
            source.recycle();
        }
        Bitmap output = render(oriented, targetWidth, targetHeight, MODE_FILL.equals(mode));
        if (output != oriented) {
            oriented.recycle();
        }

        File directory = WallpaperConfig.library(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            output.recycle();
            throw new IOException("无法创建应用内图库");
        }
        File destination = uniqueDestination(directory, sourceName);
        File temporary = new File(directory, destination.getName() + ".tmp");
        FileOutputStream stream = new FileOutputStream(temporary);
        boolean compressed;
        try {
            compressed = output.compress(Bitmap.CompressFormat.JPEG, 92, stream);
            stream.getFD().sync();
        } finally {
            stream.close();
            output.recycle();
        }
        if (!compressed || !temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("保存处理后的图片失败");
        }
        return new Result(destination, sourceName, sourceWidth, sourceHeight,
                targetWidth, targetHeight, mode);
    }

    private static DisplayMetrics realDisplayMetrics(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) {
            manager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.setTo(context.getResources().getDisplayMetrics());
        }
        if (metrics.widthPixels > metrics.heightPixels) {
            int swap = metrics.widthPixels;
            metrics.widthPixels = metrics.heightPixels;
            metrics.heightPixels = swap;
        }
        return metrics;
    }

    private static int calculateSample(
            int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
        int sample = 1;
        while (sourceWidth / (sample * 2) >= targetWidth
                && sourceHeight / (sample * 2) >= targetHeight) {
            sample *= 2;
        }
        return sample;
    }

    private static int readOrientation(ContentResolver resolver, Uri uri) {
        try {
            InputStream input = resolver.openInputStream(uri);
            if (input == null) {
                return ExifInterface.ORIENTATION_NORMAL;
            }
            try {
                ExifInterface exif = new ExifInterface(input);
                return exif.getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            } finally {
                input.close();
            }
        } catch (IOException ignored) {
            return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    private static Bitmap applyOrientation(Bitmap source, int orientation) {
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:
                matrix.setScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_180:
                matrix.setRotate(180f);
                break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:
                matrix.setRotate(180f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_TRANSPOSE:
                matrix.setRotate(90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_90:
                matrix.setRotate(90f);
                break;
            case ExifInterface.ORIENTATION_TRANSVERSE:
                matrix.setRotate(-90f);
                matrix.postScale(-1f, 1f);
                break;
            case ExifInterface.ORIENTATION_ROTATE_270:
                matrix.setRotate(-90f);
                break;
            default:
                return source;
        }
        return Bitmap.createBitmap(
                source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
    }

    private static Bitmap render(
            Bitmap source, int targetWidth, int targetHeight, boolean fill) {
        Bitmap output = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.WHITE);
        float scaleX = (float) targetWidth / source.getWidth();
        float scaleY = (float) targetHeight / source.getHeight();
        float scale = fill ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        float drawnWidth = source.getWidth() * scale;
        float drawnHeight = source.getHeight() * scale;
        float left = (targetWidth - drawnWidth) / 2f;
        float top = (targetHeight - drawnHeight) / 2f;
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG
                | Paint.DITHER_FLAG);
        canvas.drawBitmap(source, null,
                new RectF(left, top, left + drawnWidth, top + drawnHeight), paint);
        return output;
    }

    private static File uniqueDestination(File directory, String sourceName) {
        String base = sourceName == null ? "wallpaper" : sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[^a-zA-Z0-9._\\-\u4e00-\u9fa5]", "_");
        if (base.length() == 0) {
            base = "wallpaper";
        }
        File candidate = new File(directory, base + ".jpg");
        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(directory, base + '-' + suffix + ".jpg");
            suffix++;
        }
        return candidate;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (RuntimeException ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "wallpaper" : segment;
    }

    static final class Result {
        final File file;
        final String sourceName;
        final int sourceWidth;
        final int sourceHeight;
        final int targetWidth;
        final int targetHeight;
        final String mode;

        Result(File file, String sourceName, int sourceWidth, int sourceHeight,
                int targetWidth, int targetHeight, String mode) {
            this.file = file;
            this.sourceName = sourceName;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            this.mode = mode;
        }

        String description() {
            String treatment = MODE_FILL.equals(mode) ? "铺满裁剪" : "完整留白";
            return String.format(Locale.CHINA, "%d×%d → %d×%d · %s",
                    sourceWidth, sourceHeight, targetWidth, targetHeight, treatment);
        }
    }
}
