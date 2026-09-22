package ru.lct.heating.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GridMaskCodecTest {

    @Test
    void encodesAndReadsBackWithoutDownscale() {
        int width = 4;
        int height = 3;
        boolean[] set = new boolean[width * height];
        set[0] = true;
        set[5] = true;
        set[11] = true;

        GridMaskCodec.Downscale ds = GridMaskCodec.downscale(width, height, 0);
        String encoded = GridMaskCodec.encode(width, height, ds,
                index -> set[index]);

        assertThat(ds.factor).isEqualTo(1);
        assertThat(ds.imageWidth).isEqualTo(width);
        assertThat(ds.imageHeight).isEqualTo(height);
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int imageRow = height - 1 - row;
                assertThat(GridMaskCodec.bitAt(encoded, width, imageRow, col))
                        .as("клетка %d,%d", col, row)
                        .isEqualTo(set[row * width + col]);
            }
        }
    }

    @Test
    void downscale_isConservative() {
        int width = 4;
        int height = 4;
        boolean[] set = new boolean[width * height];
        set[0] = true;

        GridMaskCodec.Downscale ds = GridMaskCodec.downscale(width, height, 4);
        assertThat(ds.factor).isEqualTo(2);
        assertThat(ds.imageWidth).isEqualTo(2);
        assertThat(ds.imageHeight).isEqualTo(2);

        String encoded = GridMaskCodec.encode(width, height, ds, index -> set[index]);
        assertThat(GridMaskCodec.bitAt(encoded, 2, 1, 0)).isTrue();
        assertThat(GridMaskCodec.bitAt(encoded, 2, 0, 1)).isFalse();
    }

    @Test
    void downscaleKeepsAtLeastOnePixelForEmptyGrid() {
        GridMaskCodec.Downscale ds = GridMaskCodec.downscale(1000, 1000, 100);
        assertThat((long) ds.imageWidth * ds.imageHeight).isLessThanOrEqualTo(100);
        assertThat(ds.imageWidth).isGreaterThan(0);
        assertThat(ds.imageHeight).isGreaterThan(0);
    }
}
