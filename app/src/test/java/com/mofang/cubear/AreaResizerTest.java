package com.mofang.cubear;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;

public class AreaResizerTest {
    /** Independent 2-D reference: each output pixel averages the source area it covers. */
    private static float reference(byte[] rgba, int w, int h, int dw, int dh, int x, int y, int ch) {
        double sx = w / (double) dw, sy = h / (double) dh;
        double x0 = x * sx, x1 = (x + 1) * sx, y0 = y * sy, y1 = (y + 1) * sy;
        double sum = 0;
        for (int py = (int) Math.floor(y0); py < Math.ceil(y1) && py < h; py++) {
            double oy = Math.min(y1, py + 1) - Math.max(y0, py);
            for (int px = (int) Math.floor(x0); px < Math.ceil(x1) && px < w; px++) {
                double ox = Math.min(x1, px + 1) - Math.max(x0, px);
                sum += (rgba[(py * w + px) * 4 + ch] & 0xFF) * ox * oy;
            }
        }
        return (float) Math.round(sum / (sx * sy)) / 255f;
    }

    @Test public void matchesTheAreaAverageAtTheAppsFractionalScale() {
        int w = 90, h = 160, dw = 20, dh = 36;   // the 720x1280 -> 160x288 ratios, smaller
        byte[] rgba = new byte[w * h * 4];
        new Random(1).nextBytes(rgba);
        float[] out = new float[dw * dh * 3];
        new AreaResizer(w, h, dw, dh).resize(rgba, out);
        int plane = dw * dh;
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                for (int ch = 0; ch < 3; ch++) {
                    assertEquals("pixel " + x + "," + y + " ch " + ch,
                        reference(rgba, w, h, dw, dh, x, y, ch), out[ch * plane + y * dw + x], 1.01f / 255f);
                }
            }
        }
    }

    @Test public void integerFactorsAreBlockMeans() {
        int w = 8, h = 8;
        byte[] rgba = new byte[w * h * 4];
        for (int i = 0; i < w * h; i++) {
            rgba[i * 4] = (byte) (i % 2 == 0 ? 200 : 100);
            rgba[i * 4 + 1] = (byte) 50;
            rgba[i * 4 + 2] = (byte) 255;
        }
        float[] out = new float[2 * 2 * 3];
        new AreaResizer(w, h, 2, 2).resize(rgba, out);
        assertEquals(150 / 255f, out[0], 1e-6f);
        assertEquals(50 / 255f, out[4], 1e-6f);
        assertEquals(1f, out[8], 1e-6f);
    }
}
