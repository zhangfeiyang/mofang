package com.mofang.cubear;

/**
 * Reads the nine stickers of a face given its four corners.
 *
 * <p>Shared by every detector: how a face is located keeps changing, how it is measured does not.
 * Each cell yields a median colour plus a Lab reading, and cells that are not one flat colour are
 * refused outright rather than reported as a plausible sticker.
 *
 * <p>Two geometries are supported. A quad from {@link FaceRefiner} is the sticker lattice itself,
 * so each cell is read at its exact centre with a wide patch. A coarse quad straight from a
 * detector may be off by a third of a sticker, so it is pulled in toward the centre and read
 * through a narrow patch that stays on the sticker despite that error — the conservative reading
 * the app always used.
 *
 * <p>Works on the packed RGBA frame directly: no warp image, no per-frame native allocation.
 */
public final class FaceSampler {
    /**
     * Corner pull-in for coarse quads: keeps the 3x3 subdivision on stickers rather than on the
     * black frame when the corners are loose.
     */
    static final double COARSE_INSET = 0.84;
    /**
     * Half-width of the patch, as a share of one cell, for coarse quads. Calibrated on 60 real
     * warped faces from a struggling session: a 0.14 patch read a mean of 3.2/9 cells, 0.10 read
     * 3.9 — worn, mottled stickers punish a wide patch on a loose quad, and a narrower one also
     * stays clear of the black borders the inset can drag towards the outer cells.
     */
    static final double COARSE_RADIUS = 0.10;
    /**
     * Half-width for refined quads. The lattice is accurate to a few percent of a pitch, and a
     * sticker spans about 0.8 of one with rounded corners, so 0.22 stays well inside while giving
     * the median four times the pixels to vote with.
     */
    static final double REFINED_RADIUS = 0.22;
    /**
     * Refined lattices are still read slightly inside their outer stickers: on the demo video,
     * sampling the outer cells at 0.92 of their exact offset read the most faces correctly and
     * the fewest with two wrong stickers, against both the exact centres and the coarse sampler.
     * The outermost band of a sticker is where bevel shading and edge glare sit.
     */
    static final double REFINED_INSET = 0.92;
    /** Samples per patch edge; the median needs votes, not every pixel. */
    private static final int GRID = 14;
    /**
     * Above this share of samples far from the median, the cell is a finger, an edge or glare.
     * 0.32 assumed factory-matte stickers; a real played-with cube's worn whites measured
     * 0.39-0.66 and were thrown away wholesale. The median itself stays robust — a half-and-half
     * straddle cell is what the margin is guarding against — so 0.45 buys worn stickers without
     * inviting two-colour cells.
     */
    private static final float MAX_DISPERSION = 0.45f;
    /** Per-channel deviation from the median that marks a sample as an outlier. */
    private static final int OUTLIER_DEVIATION = 46;

    private FaceSampler() {}

    /** Pulls corners in toward their centroid by {@code scale}. */
    static double[] inset(double[] quad, double scale) {
        double cx = 0, cy = 0;
        for (int i = 0; i < 4; i++) { cx += quad[i * 2]; cy += quad[i * 2 + 1]; }
        cx /= 4; cy /= 4;
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            out[i * 2] = cx + scale * (quad[i * 2] - cx);
            out[i * 2 + 1] = cy + scale * (quad[i * 2 + 1] - cy);
        }
        return out;
    }

    /**
     * @param rgba packed frame, R,G,B,A per pixel
     * @param quad four corners clockwise from the top-left, frame pixels
     * @param refined true when {@code quad} is a {@link FaceRefiner} lattice boundary
     * @param quality how well the face was localised, folded into the reported confidence
     * @return the sample, or null when the quad is degenerate
     */
    public static FaceSample sample(byte[] rgba, int width, int height, double[] quad,
                                    boolean refined, float quality) {
        double[] geometry = inset(quad, refined ? REFINED_INSET : COARSE_INSET);
        double[] toImage = Homography.fromSquare(3, geometry);
        if (toImage == null) return null;
        double radius = refined ? REFINED_RADIUS : COARSE_RADIUS;
        CubeColor[] colors = new CubeColor[9];
        float[][] lab = new float[9][];
        boolean[] reliable = new boolean[9];
        int[] r = new int[GRID * GRID], g = new int[GRID * GRID], b = new int[GRID * GRID];
        int[] histogram = new int[256];
        double[] point = new double[2];
        int known = 0;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                int count = 0;
                for (int sy = 0; sy < GRID; sy++) {
                    double ly = row + 0.5 + radius * (2.0 * (sy + 0.5) / GRID - 1);
                    for (int sx = 0; sx < GRID; sx++) {
                        double lx = col + 0.5 + radius * (2.0 * (sx + 0.5) / GRID - 1);
                        Homography.apply(toImage, lx, ly, point);
                        int px = clamp((int) Math.round(point[0]), 0, width - 1);
                        int py = clamp((int) Math.round(point[1]), 0, height - 1);
                        int at = (py * width + px) * 4;
                        r[count] = rgba[at] & 0xFF;
                        g[count] = rgba[at + 1] & 0xFF;
                        b[count] = rgba[at + 2] & 0xFF;
                        count++;
                    }
                }
                int[] median = {median(r, count, histogram), median(g, count, histogram),
                    median(b, count, histogram)};
                int outliers = 0;
                for (int i = 0; i < count; i++) {
                    int deviation = Math.max(Math.abs(r[i] - median[0]),
                        Math.max(Math.abs(g[i] - median[1]), Math.abs(b[i] - median[2])));
                    if (deviation > OUTLIER_DEVIATION) outliers++;
                }
                boolean uniform = outliers <= MAX_DISPERSION * count;
                lab[index] = Lab.fromRgb(median[0], median[1], median[2]);
                // A shadowed white sticker reads as dark grey — L 50-70 with near-zero chroma —
                // which is exactly what the cube's black body looks like only much darker.
                // The old L<78 cut devoured every white face that wasn't fully lit.
                boolean plastic = lab[index][0] < 45f && Lab.chroma(lab[index]) < 25f;
                reliable[index] = uniform && !plastic;
                CubeColor color = reliable[index]
                    ? CubeColor.classify(median[0], median[1], median[2])
                    : CubeColor.UNKNOWN;
                colors[index] = color;
                if (color != CubeColor.UNKNOWN) known++;
            }
        }
        return new FaceSample(colors, lab, reliable, (known / 9f) * quality);
    }

    /**
     * Lower median through a histogram. The median ignores specular highlights and the dark bevel
     * around a sticker, both of which drag a mean off the sticker's true colour.
     */
    static int median(int[] values, int count, int[] histogram) {
        java.util.Arrays.fill(histogram, 0);
        for (int i = 0; i < count; i++) histogram[values[i]]++;
        int seen = 0;
        for (int value = 0; value < 256; value++) {
            seen += histogram[value];
            if (seen * 2 >= count) return value;
        }
        return 255;
    }

    private static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }
}
