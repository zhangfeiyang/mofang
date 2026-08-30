package com.mofang.cubear;

/**
 * sRGB to CIELAB conversion and perceptual distance.
 *
 * <p>Sticker decisions are made in Lab because lightness carries the red/orange distinction that
 * hue alone collapses: measured on real frames, red and orange sit 2-4 degrees apart in hue but
 * roughly 30 units apart in L.
 */
public final class Lab {
    private Lab() {}

    /** Converts an sRGB triple to CIELAB under a D65 white point. */
    public static float[] fromRgb(int red, int green, int blue) {
        float r = linearize(red / 255f);
        float g = linearize(green / 255f);
        float b = linearize(blue / 255f);

        float x = (0.4124564f * r + 0.3575761f * g + 0.1804375f * b) / 0.95047f;
        float y = (0.2126729f * r + 0.7151522f * g + 0.0721750f * b);
        float z = (0.0193339f * r + 0.1191920f * g + 0.9503041f * b) / 1.08883f;

        float fx = pivot(x), fy = pivot(y), fz = pivot(z);
        return new float[]{116f * fy - 16f, 500f * (fx - fy), 200f * (fy - fz)};
    }

    private static float linearize(float channel) {
        return channel <= 0.04045f ? channel / 12.92f
            : (float) Math.pow((channel + 0.055f) / 1.055f, 2.4);
    }

    private static float pivot(float value) {
        return value > 0.008856f ? (float) Math.cbrt(value) : (7.787f * value) + 16f / 116f;
    }

    /** Squared CIE76 distance. Squared because the assignment solver only compares costs. */
    public static float distanceSquared(float[] a, float[] b) {
        float dl = a[0] - b[0], da = a[1] - b[1], db = a[2] - b[2];
        return dl * dl + da * da + db * db;
    }

    public static float distance(float[] a, float[] b) {
        return (float) Math.sqrt(distanceSquared(a, b));
    }

    /** Chroma, i.e. distance from the neutral axis. Low chroma means white or a washed-out read. */
    public static float chroma(float[] lab) {
        return (float) Math.hypot(lab[1], lab[2]);
    }
}
