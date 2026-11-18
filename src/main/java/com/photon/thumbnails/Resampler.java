package com.photon.thumbnails;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Wysokiej jakości zmniejszanie obrazu filtrem Lanczos-3 (rozdzielnie: najpierw poziomo, potem pionowo).
 * <p>
 * Działa na surowych wartościach próbek — bez konwersji przestrzeni barw — dzięki czemu
 * po skopiowaniu profilu ICC oryginału kolory miniatury pozostają wierne (np. dla Adobe RGB).
 */
final class Resampler {

    private static final double SUPPORT = 3.0;

    private Resampler() {
    }

    /**
     * @param source obraz 1-kanałowy (szarości) albo 3-kanałowy (RGB)
     * @return obraz {@link BufferedImage#TYPE_BYTE_GRAY} albo {@link BufferedImage#TYPE_INT_RGB}
     */
    static BufferedImage resize(BufferedImage source, int width, int height) {
        Raster in = source.getRaster();
        int bands = in.getNumBands() >= 3 ? 3 : 1;
        int srcW = in.getWidth();
        int srcH = in.getHeight();
        int componentBits = source.getColorModel().getComponentSize(0);
        float toByte = componentBits == 8 ? 1f : 255f / ((1 << componentBits) - 1);

        // Poziomo — wiersz po wierszu, bez trzymania całego źródła jako float
        Weights wx = weights(srcW, width);
        int nb = in.getNumBands();
        int[] line = new int[srcW * nb];
        float[][] horizontal = new float[srcH][width * bands];
        for (int y = 0; y < srcH; y++) {
            in.getPixels(0, y, srcW, 1, line);
            float[] dst = horizontal[y];
            for (int x = 0; x < width; x++) {
                int start = wx.start[x];
                float[] w = wx.values[x];
                for (int b = 0; b < bands; b++) {
                    float sum = 0;
                    for (int k = 0; k < w.length; k++) {
                        sum += line[(start + k) * nb + b] * w[k];
                    }
                    dst[x * bands + b] = sum * toByte;
                }
            }
        }

        // Pionowo + zapis
        BufferedImage out = new BufferedImage(width, height,
                bands == 3 ? BufferedImage.TYPE_INT_RGB : BufferedImage.TYPE_BYTE_GRAY);
        WritableRaster raster = out.getRaster();
        Weights wy = weights(srcH, height);
        int[] outLine = new int[width * bands];
        for (int y = 0; y < height; y++) {
            int start = wy.start[y];
            float[] w = wy.values[y];
            for (int i = 0; i < width * bands; i++) {
                float sum = 0;
                for (int k = 0; k < w.length; k++) {
                    sum += horizontal[start + k][i] * w[k];
                }
                outLine[i] = clamp(Math.round(sum));
            }
            raster.setPixels(0, y, width, 1, outLine);
        }
        return out;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    /** Wagi filtra dla każdego piksela wyniku: indeks pierwszej próbki i znormalizowane wagi. */
    private record Weights(int[] start, float[][] values) {
    }

    private static Weights weights(int srcSize, int dstSize) {
        double scale = (double) srcSize / dstSize;
        double filterScale = Math.max(1.0, scale); // przy zmniejszaniu filtr rozciągamy (antyaliasing)
        double support = SUPPORT * filterScale;
        int[] start = new int[dstSize];
        float[][] values = new float[dstSize][];
        for (int i = 0; i < dstSize; i++) {
            double center = (i + 0.5) * scale;
            int left = Math.max(0, (int) Math.floor(center - support));
            int right = Math.min(srcSize - 1, (int) Math.ceil(center + support));
            float[] w = new float[right - left + 1];
            double total = 0;
            for (int j = left; j <= right; j++) {
                double v = lanczos((j + 0.5 - center) / filterScale);
                w[j - left] = (float) v;
                total += v;
            }
            if (total != 0) {
                for (int k = 0; k < w.length; k++) {
                    w[k] /= (float) total;
                }
            }
            start[i] = left;
            values[i] = w;
        }
        return new Weights(start, values);
    }

    private static double lanczos(double x) {
        if (x == 0) {
            return 1;
        }
        if (Math.abs(x) >= SUPPORT) {
            return 0;
        }
        double pix = Math.PI * x;
        return SUPPORT * Math.sin(pix) * Math.sin(pix / SUPPORT) / (pix * pix);
    }
}
