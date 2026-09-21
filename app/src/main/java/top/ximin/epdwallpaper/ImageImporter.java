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
import android.graphics.Rect;
import android.graphics.RectF;
import android.media.ExifInterface;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

final class ImageImporter {
    static final String MODE_CROP = "scale_crop";
    static final String MODE_FIT_WHITE = "scale_fit_white";
    static final String MODE_FIT_BLACK = "scale_fit_black";
    static final String MODE_SCALE = "scale_exact_ratio";
    static final String MODE_POINT_WHITE = "point_white";
    static final String MODE_POINT_BLACK = "point_black";

    static final int ASPECT_SAME = 0;
    static final int ASPECT_WIDER = 1;
    static final int ASPECT_TALLER = -1;

    private ImageImporter() {
    }

    static Inspection inspect(Context context, Uri uri) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String sourceName = queryDisplayName(resolver, uri);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        InputStream input = resolver.openInputStream(uri);
        if (input == null) {
            throw new IOException("无法打开图片");
        }
        try {
            BitmapFactory.decodeStream(input, null, bounds);
        } finally {
            input.close();
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("不支持或损坏的图片");
        }
        int orientation = readOrientation(resolver, uri);
        boolean swaps = swapsAxes(orientation);
        int orientedWidth = swaps ? bounds.outHeight : bounds.outWidth;
        int orientedHeight = swaps ? bounds.outWidth : bounds.outHeight;
        DisplayMetrics metrics = realDisplayMetrics(context);
        int targetWidth = Math.min(metrics.widthPixels, metrics.heightPixels);
        int targetHeight = Math.max(metrics.widthPixels, metrics.heightPixels);
        long sourceRatio = (long) orientedWidth * targetHeight;
        long targetRatio = (long) targetWidth * orientedHeight;
        int aspect = sourceRatio == targetRatio ? ASPECT_SAME
                : (sourceRatio > targetRatio ? ASPECT_WIDER : ASPECT_TALLER);
        return new Inspection(sourceName, bounds.outWidth, bounds.outHeight,
                orientedWidth, orientedHeight, targetWidth, targetHeight,
                orientation, aspect);
    }

    static Result importUri(Context context, Uri uri,
            Inspection inspection, String mode) throws IOException {
        int sample = isPointMode(mode) ? 1 : calculateSample(
                inspection.orientedWidth, inspection.orientedHeight,
                inspection.targetWidth, inspection.targetHeight);
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decode.inDither = false;
        InputStream imageInput = context.getContentResolver().openInputStream(uri);
        if (imageInput == null) {
            throw new IOException("无法再次打开图片");
        }
        Bitmap source;
        try {
            source = BitmapFactory.decodeStream(imageInput, null, decode);
        } catch (OutOfMemoryError error) {
            throw new IOException("图片过大，无法按原始像素处理；请先缩小图片", error);
        } finally {
            imageInput.close();
        }
        if (source == null) {
            throw new IOException("图片解码失败");
        }

        Bitmap oriented = applyOrientation(source, inspection.orientation);
        if (oriented != source) {
            source.recycle();
        }
        Bitmap output = render(oriented, inspection.targetWidth,
                inspection.targetHeight, mode);
        if (output != oriented) {
            oriented.recycle();
        }

        File directory = WallpaperConfig.library(context);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            output.recycle();
            throw new IOException("无法创建应用内图库");
        }
        File destination = uniqueDestination(directory, inspection.sourceName);
        File temporary = new File(directory, destination.getName() + ".tmp");
        IndexedPngEncoder.Result encoded;
        try {
            encoded = IndexedPngEncoder.write(output, temporary);
        } finally {
            output.recycle();
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("保存处理后的图片失败");
        }
        return new Result(destination, inspection, mode, encoded.colorAdapted);
    }

    private static DisplayMetrics realDisplayMetrics(Context context) {
        DisplayMetrics metrics = new DisplayMetrics();
        WindowManager manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (manager != null) {
            manager.getDefaultDisplay().getRealMetrics(metrics);
        } else {
            metrics.setTo(context.getResources().getDisplayMetrics());
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

    private static boolean swapsAxes(int orientation) {
        return orientation == ExifInterface.ORIENTATION_ROTATE_90
                || orientation == ExifInterface.ORIENTATION_ROTATE_270
                || orientation == ExifInterface.ORIENTATION_TRANSPOSE
                || orientation == ExifInterface.ORIENTATION_TRANSVERSE;
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

    private static Bitmap render(Bitmap source, int targetWidth,
            int targetHeight, String mode) {
        Bitmap output = Bitmap.createBitmap(
                targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        int background = (MODE_FIT_BLACK.equals(mode) || MODE_POINT_BLACK.equals(mode))
                ? Color.BLACK : Color.WHITE;
        canvas.drawColor(background);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (isPointMode(mode)) {
            int copyWidth = Math.min(source.getWidth(), targetWidth);
            int copyHeight = Math.min(source.getHeight(), targetHeight);
            int sourceLeft = (source.getWidth() - copyWidth) / 2;
            int sourceTop = (source.getHeight() - copyHeight) / 2;
            int destinationLeft = (targetWidth - copyWidth) / 2;
            int destinationTop = (targetHeight - copyHeight) / 2;
            paint.setFilterBitmap(false);
            canvas.drawBitmap(source,
                    new Rect(sourceLeft, sourceTop,
                            sourceLeft + copyWidth, sourceTop + copyHeight),
                    new Rect(destinationLeft, destinationTop,
                            destinationLeft + copyWidth, destinationTop + copyHeight), paint);
            return output;
        }
        float scaleX = (float) targetWidth / source.getWidth();
        float scaleY = (float) targetHeight / source.getHeight();
        boolean crop = MODE_CROP.equals(mode);
        float scale = crop ? Math.max(scaleX, scaleY) : Math.min(scaleX, scaleY);
        float drawnWidth = source.getWidth() * scale;
        float drawnHeight = source.getHeight() * scale;
        float left = (targetWidth - drawnWidth) / 2f;
        float top = (targetHeight - drawnHeight) / 2f;
        canvas.drawBitmap(source, null,
                new RectF(left, top, left + drawnWidth, top + drawnHeight), paint);
        return output;
    }

    private static boolean isPointMode(String mode) {
        return MODE_POINT_BLACK.equals(mode) || MODE_POINT_WHITE.equals(mode);
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
        File candidate = new File(directory, base + ".png");
        int suffix = 2;
        while (candidate.exists()) {
            candidate = new File(directory, base + '-' + suffix + ".png");
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

    static final class Inspection {
        final String sourceName;
        final int encodedWidth;
        final int encodedHeight;
        final int orientedWidth;
        final int orientedHeight;
        final int targetWidth;
        final int targetHeight;
        final int orientation;
        final int aspect;

        Inspection(String sourceName, int encodedWidth, int encodedHeight,
                int orientedWidth, int orientedHeight, int targetWidth,
                int targetHeight, int orientation, int aspect) {
            this.sourceName = sourceName;
            this.encodedWidth = encodedWidth;
            this.encodedHeight = encodedHeight;
            this.orientedWidth = orientedWidth;
            this.orientedHeight = orientedHeight;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
            this.orientation = orientation;
            this.aspect = aspect;
        }

        boolean exactSize() {
            return orientedWidth == targetWidth && orientedHeight == targetHeight;
        }
    }

    static final class Result {
        final File file;
        final Inspection inspection;
        final String mode;
        final boolean colorAdapted;

        Result(File file, Inspection inspection, String mode, boolean colorAdapted) {
            this.file = file;
            this.inspection = inspection;
            this.mode = mode;
            this.colorAdapted = colorAdapted;
        }

        String description() {
            String treatment;
            if (MODE_CROP.equals(mode)) {
                treatment = inspection.aspect == ASPECT_WIDER
                        ? "等比铺满 · 左右裁切" : "等比铺满 · 上下裁切";
            } else if (MODE_FIT_BLACK.equals(mode) || MODE_FIT_WHITE.equals(mode)) {
                String side = inspection.aspect == ASPECT_WIDER ? "上下" : "左右";
                String color = MODE_FIT_BLACK.equals(mode) ? "黑色" : "白色";
                treatment = "等比完整显示 · " + side + color + "填充";
            } else if (MODE_SCALE.equals(mode)) {
                treatment = "等比缩放至屏幕";
            } else {
                treatment = "点对点居中 · "
                        + (MODE_POINT_BLACK.equals(mode) ? "黑色" : "白色") + "填充";
            }
            String color = colorAdapted ? "已转换为16级灰度" : "原图已符合16级灰度";
            return String.format(Locale.CHINA, "%d×%d → %d×%d · %s · %s",
                    inspection.orientedWidth, inspection.orientedHeight,
                    inspection.targetWidth, inspection.targetHeight, treatment, color);
        }
    }
}
