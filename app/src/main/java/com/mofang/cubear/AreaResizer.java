package com.mofang.cubear;

/**
 * Downscales a packed RGBA frame into the network's normalised RGB planes.
 *
 * <p>Matches OpenCV's {@code INTER_AREA} for shrinking, which is what the training pipeline used:
 * every output pixel is the average of exactly the source area it covers, with fractional weights
 * for source pixels cut by its edges, and the result rounded to 8 bits before normalising — the
 * same values the network saw when it was trained. Separable, with the per-column and per-row
 * tap tables computed once for a frame size.
 */
final class AreaResizer {
    private final int srcWidth, srcHeight, dstWidth, dstHeight;
    private final int[][] colIndex, rowIndex;
    private final float[][] colWeight, rowWeight;
    /** One source row filtered horizontally: dstWidth x 3 channels. */
    private final float[] rowBuffer;
    /** Horizontally filtered rows, srcHeight x dstWidth x 3. */
    private final float[] horizontal;

    AreaResizer(int srcWidth, int srcHeight, int dstWidth, int dstHeight) {
        this.srcWidth = srcWidth;
        this.srcHeight = srcHeight;
        this.dstWidth = dstWidth;
        this.dstHeight = dstHeight;
        colIndex = new int[dstWidth][];
        colWeight = new float[dstWidth][];
        taps(srcWidth, dstWidth, colIndex, colWeight);
        rowIndex = new int[dstHeight][];
        rowWeight = new float[dstHeight][];
        taps(srcHeight, dstHeight, rowIndex, rowWeight);
        rowBuffer = new float[dstWidth * 3];
        horizontal = new float[srcHeight * dstWidth * 3];
    }

    boolean fits(int width, int height) { return width == srcWidth && height == srcHeight; }

    /** Source pixels overlapping each output cell, weighted by overlap over the cell's span. */
    private static void taps(int src, int dst, int[][] index, float[][] weight) {
        double scale = src / (double) dst;
        for (int d = 0; d < dst; d++) {
            double start = d * scale, end = (d + 1) * scale;
            int first = (int) Math.floor(start), last = (int) Math.ceil(end) - 1;
            last = Math.min(last, src - 1);
            int n = last - first + 1;
            index[d] = new int[n];
            weight[d] = new float[n];
            for (int k = 0; k < n; k++) {
                int s = first + k;
                double overlap = Math.min(end, s + 1) - Math.max(start, s);
                index[d][k] = s;
                weight[d][k] = (float) (overlap / scale);
            }
        }
    }

    /**
     * @param rgba packed frame of the size this resizer was built for
     * @param out three planes (R, G, B) of dstWidth x dstHeight, values in 0..1
     */
    void resize(byte[] rgba, float[] out) {
        for (int y = 0; y < srcHeight; y++) {
            int rowBase = y * srcWidth * 4;
            for (int x = 0; x < dstWidth; x++) {
                float r = 0, g = 0, b = 0;
                int[] idx = colIndex[x];
                float[] w = colWeight[x];
                for (int k = 0; k < idx.length; k++) {
                    int at = rowBase + idx[k] * 4;
                    float wk = w[k];
                    r += (rgba[at] & 0xFF) * wk;
                    g += (rgba[at + 1] & 0xFF) * wk;
                    b += (rgba[at + 2] & 0xFF) * wk;
                }
                rowBuffer[x * 3] = r;
                rowBuffer[x * 3 + 1] = g;
                rowBuffer[x * 3 + 2] = b;
            }
            System.arraycopy(rowBuffer, 0, horizontal, y * dstWidth * 3, dstWidth * 3);
        }
        int plane = dstWidth * dstHeight;
        for (int y = 0; y < dstHeight; y++) {
            int[] idx = rowIndex[y];
            float[] w = rowWeight[y];
            for (int x = 0; x < dstWidth; x++) {
                float r = 0, g = 0, b = 0;
                for (int k = 0; k < idx.length; k++) {
                    int at = (idx[k] * dstWidth + x) * 3;
                    float wk = w[k];
                    r += horizontal[at] * wk;
                    g += horizontal[at + 1] * wk;
                    b += horizontal[at + 2] * wk;
                }
                int o = y * dstWidth + x;
                out[o] = Math.round(r) / 255f;
                out[plane + o] = Math.round(g) / 255f;
                out[2 * plane + o] = Math.round(b) / 255f;
            }
        }
    }

    /** The same downscale as packed RGBA bytes, for debug images. */
    byte[] resizeToRgba(byte[] rgba) {
        float[] planes = new float[dstWidth * dstHeight * 3];
        resize(rgba, planes);
        int plane = dstWidth * dstHeight;
        byte[] out = new byte[plane * 4];
        for (int i = 0; i < plane; i++) {
            out[i * 4] = (byte) Math.round(planes[i] * 255);
            out[i * 4 + 1] = (byte) Math.round(planes[plane + i] * 255);
            out[i * 4 + 2] = (byte) Math.round(planes[2 * plane + i] * 255);
            out[i * 4 + 3] = (byte) 255;
        }
        return out;
    }
}
