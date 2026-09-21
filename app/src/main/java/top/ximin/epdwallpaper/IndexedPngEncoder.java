package top.ximin.epdwallpaper;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

final class IndexedPngEncoder {
    private static final byte[] SIGNATURE = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };

    private IndexedPngEncoder() {
    }

    static Result write(Bitmap bitmap, File destination) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int[] palette = compatiblePalette(bitmap);
        boolean adapted = palette == null;
        if (adapted) {
            palette = new int[16];
            for (int index = 0; index < 16; index++) {
                palette[index] = index * 17;
            }
        }

        byte[] compressed = compressPixels(bitmap, palette, adapted);
        FileOutputStream raw = new FileOutputStream(destination);
        DataOutputStream output = new DataOutputStream(raw);
        try {
            output.write(SIGNATURE);
            ByteArrayOutputStream headerBytes = new ByteArrayOutputStream(13);
            DataOutputStream header = new DataOutputStream(headerBytes);
            header.writeInt(width);
            header.writeInt(height);
            header.writeByte(4);
            header.writeByte(3);
            header.writeByte(0);
            header.writeByte(0);
            header.writeByte(0);
            header.close();
            writeChunk(output, "IHDR", headerBytes.toByteArray());

            byte[] paletteBytes = new byte[16 * 3];
            for (int index = 0; index < 16; index++) {
                int gray = palette[index];
                paletteBytes[index * 3] = (byte) gray;
                paletteBytes[index * 3 + 1] = (byte) gray;
                paletteBytes[index * 3 + 2] = (byte) gray;
            }
            writeChunk(output, "PLTE", paletteBytes);
            writeChunk(output, "IDAT", compressed);
            writeChunk(output, "IEND", new byte[0]);
            output.flush();
            raw.getFD().sync();
        } finally {
            output.close();
        }
        return new Result(adapted, palette.length);
    }

    private static int[] compatiblePalette(Bitmap bitmap) {
        Set<Integer> colors = new HashSet<>();
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int color : row) {
                int red = Color.red(color);
                if (red != Color.green(color) || red != Color.blue(color)) {
                    return null;
                }
                colors.add(red);
                if (colors.size() > 16) {
                    return null;
                }
            }
        }
        Integer[] ordered = colors.toArray(new Integer[colors.size()]);
        Arrays.sort(ordered);
        int[] palette = new int[16];
        int fallback = ordered.length == 0 ? 0 : ordered[ordered.length - 1];
        for (int index = 0; index < 16; index++) {
            palette[index] = index < ordered.length ? ordered[index] : fallback;
        }
        return palette;
    }

    private static byte[] compressPixels(Bitmap bitmap, int[] palette, boolean adapted)
            throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(
                (bitmap.getWidth() * bitmap.getHeight()) / 4);
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        DeflaterOutputStream compressed = new DeflaterOutputStream(bytes, deflater);
        int[] row = new int[bitmap.getWidth()];
        byte[] packed = new byte[(bitmap.getWidth() + 1) / 2];
        try {
            for (int y = 0; y < bitmap.getHeight(); y++) {
                bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
                Arrays.fill(packed, (byte) 0);
                for (int x = 0; x < row.length; x++) {
                    int index;
                    if (adapted) {
                        int color = row[x];
                        int luminance = (77 * Color.red(color)
                                + 150 * Color.green(color)
                                + 29 * Color.blue(color) + 128) >> 8;
                        index = (luminance * 15 + 127) / 255;
                    } else {
                        index = paletteIndex(palette, Color.red(row[x]));
                    }
                    int offset = x / 2;
                    if ((x & 1) == 0) {
                        packed[offset] = (byte) (index << 4);
                    } else {
                        packed[offset] |= (byte) index;
                    }
                }
                compressed.write(0);
                compressed.write(packed);
            }
        } finally {
            compressed.close();
        }
        return bytes.toByteArray();
    }

    private static int paletteIndex(int[] palette, int gray) {
        for (int index = 0; index < palette.length; index++) {
            if (palette[index] == gray) {
                return index;
            }
        }
        return 0;
    }

    private static void writeChunk(DataOutputStream output, String type, byte[] data)
            throws IOException {
        byte[] typeBytes = type.getBytes("US-ASCII");
        output.writeInt(data.length);
        output.write(typeBytes);
        output.write(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        output.writeInt((int) crc.getValue());
    }

    static final class Result {
        final boolean colorAdapted;
        final int paletteSize;

        Result(boolean colorAdapted, int paletteSize) {
            this.colorAdapted = colorAdapted;
            this.paletteSize = paletteSize;
        }
    }
}
