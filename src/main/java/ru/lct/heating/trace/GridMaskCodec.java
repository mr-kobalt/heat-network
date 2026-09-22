package ru.lct.heating.trace;

import java.util.Base64;
import java.util.function.IntPredicate;

/**
 * Кодирование растровых масок этапа «сетка» в base64-битсеты (ADR-0036).
 * Исходные предикаты заданы по индексу клетки {@code row * width + col}, где
 * строка 0 — южная граница сетки (как в {@code ObstacleMask}). Результат
 * ориентирован «север вверху» и при необходимости прореживается, чтобы число
 * пикселей не превышало бюджет. Прореживание консервативно: пиксель помечен,
 * если помечена хотя бы одна исходная клетка блока.
 */
public final class GridMaskCodec {

    private GridMaskCodec() {
    }

    /** Результат прореживания: коэффициент и размеры изображения. */
    public static final class Downscale {
        public final int factor;
        public final int imageWidth;
        public final int imageHeight;

        Downscale(int factor, int imageWidth, int imageHeight) {
            this.factor = factor;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }

    public static Downscale downscale(int width, int height, int maxPixels) {
        int factor = 1;
        if (maxPixels > 0 && (long) width * height > maxPixels) {
            while ((long) ceilDiv(width, factor) * ceilDiv(height, factor) > maxPixels) {
                factor++;
            }
        }
        return new Downscale(factor, ceilDiv(width, factor), ceilDiv(height, factor));
    }

    /**
     * Кодирует маску. {@code setAt} — предикат по индексу исходной клетки
     * ({@code row * width + col}, строка 0 — юг).
     */
    public static String encode(int width, int height, Downscale downscale,
                                IntPredicate setAt) {
        byte[] bits = new byte[(downscale.imageWidth * downscale.imageHeight + 7) / 8];
        for (int row = 0; row < height; row++) {
            int imageRow = (height - 1 - row) / downscale.factor;
            for (int col = 0; col < width; col++) {
                if (!setAt.test(row * width + col)) {
                    continue;
                }
                int imageCol = col / downscale.factor;
                int index = imageRow * downscale.imageWidth + imageCol;
                bits[index >>> 3] |= (byte) (1 << (index & 7));
            }
        }
        return Base64.getEncoder().encodeToString(bits);
    }

    public static boolean bitAt(String base64, int imageWidth, int imageRow, int imageCol) {
        byte[] bits = Base64.getDecoder().decode(base64);
        int index = imageRow * imageWidth + imageCol;
        return (bits[index >>> 3] & (1 << (index & 7))) != 0;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
