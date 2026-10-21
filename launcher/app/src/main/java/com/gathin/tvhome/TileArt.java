package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

/**
 * Tiles for apps that ship no banner: this firmware's China-market apps (唱吧, 多屏互动, 媒体中心) only
 * have a launcher icon, which left them as a small icon on a flat dark card next to full-bleed banners.
 * Sample the icon's colour and paint the card with a gradient of it so every tile reads the same size.
 */
final class TileArt {

    private static final int FALLBACK = 0xFF3A3F47;

    private TileArt() {}

    /** Average colour of the icon's opaque pixels, brightened enough to read against white text. */
    static int dominantColor(Drawable icon) {
        if (icon == null) return FALLBACK;
        final int size = 24;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bmp);
            icon.setBounds(0, 0, size, size);
            icon.draw(canvas);
            long r = 0, g = 0, b = 0, weight = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = bmp.getPixel(x, y);
                    int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha < 32) continue;
                    r += ((pixel >> 16) & 0xFF) * (long) alpha;
                    g += ((pixel >> 8) & 0xFF) * (long) alpha;
                    b += (pixel & 0xFF) * (long) alpha;
                    weight += alpha;
                }
            }
            if (weight == 0) return FALLBACK;
            int color = Color.rgb((int) (r / weight), (int) (g / weight), (int) (b / weight));
            return readable(color);
        } catch (RuntimeException unsupported) {
            return FALLBACK;
        } finally {
            bmp.recycle();
        }
    }

    /** Keeps the hue but lifts near-black icons out of the dark card and drops near-white ones. */
    private static int readable(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        if (hsv[1] < 0.18f) hsv[1] = 0.18f;
        if (hsv[2] < 0.45f) hsv[2] = 0.45f;
        if (hsv[2] > 0.92f) hsv[2] = 0.92f;
        return Color.HSVToColor(hsv);
    }

    /** True for artwork that is one flat colour (white-on-white placeholders), which reads as an empty card. */
    static boolean isBlank(Drawable art) {
        if (art == null) return true;
        final int size = 16;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bmp);
            art.setBounds(0, 0, size, size);
            art.draw(canvas);
            double sum = 0, sumSq = 0;
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    int pixel = bmp.getPixel(x, y);
                    double luma = 0.299 * ((pixel >> 16) & 0xFF)
                            + 0.587 * ((pixel >> 8) & 0xFF) + 0.114 * (pixel & 0xFF);
                    sum += luma;
                    sumSq += luma * luma;
                }
            }
            int count = size * size;
            double mean = sum / count;
            double deviation = Math.sqrt(Math.max(0, sumSq / count - mean * mean));
            return deviation < 12 && (mean > 200 || mean < 40);
        } catch (RuntimeException unsupported) {
            return true;
        } finally {
            bmp.recycle();
        }
    }

    /** The card background for an icon tile: a diagonal gradient of the app's own colour. */
    static Drawable tile(Context ctx, int accent) {
        int base = accent == 0 ? FALLBACK : accent;
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{shade(base, 1.18f), shade(base, 0.52f)});
        gradient.setCornerRadius(16f * ctx.getResources().getDisplayMetrics().density);
        return gradient;
    }

    private static int shade(int color, float factor) {
        int r = Math.min(255, Math.round(Color.red(color) * factor));
        int g = Math.min(255, Math.round(Color.green(color) * factor));
        int b = Math.min(255, Math.round(Color.blue(color) * factor));
        return Color.rgb(r, g, b);
    }
}
