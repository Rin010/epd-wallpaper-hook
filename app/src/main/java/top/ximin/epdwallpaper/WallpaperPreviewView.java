package top.ximin.epdwallpaper;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class WallpaperPreviewView extends View {
    private static final int FIRMWARE_WIDTH = 1404;
    private static final int FIRMWARE_HEIGHT = 1872;
    private static final int DAY_LEFT = 160;
    private static final int DAY_TOP = 103;
    private static final int DATE_LEFT = 80;
    private static final int DATE_TOP = 239;

    private final Bitmap bitmap;
    private String dateMode;

    WallpaperPreviewView(Context context, WallpaperItem item, String dateMode, boolean fullSize) {
        super(context);
        this.dateMode = WallpaperConfig.normalizeDateMode(dateMode);
        bitmap = decode(item, fullSize ? 1 : 4);
        setBackgroundColor(Color.WHITE);
    }

    void setDateMode(String mode) {
        dateMode = WallpaperConfig.normalizeDateMode(mode);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int desiredHeight = Math.round(width * (float) FIRMWARE_HEIGHT / FIRMWARE_WIDTH);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        int heightLimit = MeasureSpec.getSize(heightMeasureSpec);
        int height = heightMode == MeasureSpec.UNSPECIFIED
                ? desiredHeight : Math.min(desiredHeight, heightLimit);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (bitmap != null) {
            Paint imagePaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
            canvas.drawBitmap(bitmap, null,
                    new Rect(0, 0, getWidth(), getHeight()), imagePaint);
        }
        if (!WallpaperConfig.DATE_OFF.equals(dateMode)) {
            drawFirmwareDate(canvas);
        }
    }

    private void drawFirmwareDate(Canvas canvas) {
        float scaleX = (float) getWidth() / FIRMWARE_WIDTH;
        float scaleY = (float) getHeight() / FIRMWARE_HEIGHT;
        float density = getResources().getDisplayMetrics().scaledDensity;
        int fill = WallpaperConfig.DATE_WHITE.equals(dateMode) ? Color.WHITE : Color.BLACK;
        int outline = WallpaperConfig.DATE_WHITE.equals(dateMode) ? Color.BLACK : Color.WHITE;

        Paint day = new Paint(Paint.ANTI_ALIAS_FLAG);
        day.setTypeface(Typeface.DEFAULT_BOLD);
        day.setFakeBoldText(true);
        day.setTextSize(48f * density * scaleY);
        drawOutlinedText(canvas, new SimpleDateFormat("d", Locale.CHINA).format(new Date()),
                DAY_LEFT * scaleX, textBaseline(day, DAY_TOP * scaleY),
                day, fill, outline, Math.max(1f, 2f * scaleY));

        Paint date = new Paint(Paint.ANTI_ALIAS_FLAG);
        date.setTypeface(Typeface.DEFAULT_BOLD);
        date.setTextSize(24f * density * scaleY);
        String value = new SimpleDateFormat("yyyy '年' M '月'", Locale.CHINA)
                .format(new Date());
        drawOutlinedText(canvas, value, DATE_LEFT * scaleX,
                textBaseline(date, DATE_TOP * scaleY), date,
                fill, outline, Math.max(1f, 2f * scaleY));
    }

    private static float textBaseline(Paint paint, float top) {
        return top - paint.getFontMetrics().top;
    }

    private static void drawOutlinedText(Canvas canvas, String value, float x, float baseline,
            Paint paint, int fill, int outline, float strokeWidth) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setColor(outline);
        canvas.drawText(value, x, baseline, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(fill);
        canvas.drawText(value, x, baseline, paint);
    }

    private static Bitmap decode(WallpaperItem item, int sample) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sample;
        options.inPreferredConfig = Bitmap.Config.RGB_565;
        options.inDither = true;
        if (item.system) {
            Resources resources = Resources.getSystem();
            int id = resources.getIdentifier(item.resourceName, "drawable", "android");
            return id == 0 ? null : BitmapFactory.decodeResource(resources, id, options);
        }
        return BitmapFactory.decodeFile(item.file.getAbsolutePath(), options);
    }
}
