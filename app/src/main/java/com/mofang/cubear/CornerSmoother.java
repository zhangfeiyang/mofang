package com.mofang.cubear;

/**
 * Averages the detected quadrilateral across consecutive frames before it is used.
 *
 * <p>The network's corner error is part systematic and part frame-to-frame jitter. Only the jitter
 * can be removed for free, and averaging removes it. This runs on the coarse corners, before
 * refinement, so the refiner starts from a steadier estimate.
 *
 * <p>The average is abandoned whenever the quad jumps, so following the cube as it is turned stays
 * immediate and only a genuinely still cube gets smoothed. Corners are matched to the previous
 * frame's cyclically first: the network may start its four corners from a different one when the
 * face sits near 45 degrees, and averaging mismatched corners would collapse the quad.
 */
public final class CornerSmoother {
    /** Weight given to the newest observation once the quad is being tracked. */
    private static final double BLEND = 0.4;

    private double[] smoothed;

    /** Returns the smoothed quad (x0,y0..x3,y3) for this frame, or null when given nothing. */
    public double[] update(double[] corners) {
        if (corners == null) {
            smoothed = null;
            return null;
        }
        if (smoothed != null) {
            corners = alignedTo(smoothed, corners);
            if (jumped(smoothed, corners)) smoothed = null;
        }
        if (smoothed == null) {
            smoothed = corners.clone();
            return smoothed.clone();
        }
        for (int i = 0; i < 8; i++) smoothed[i] = smoothed[i] * (1 - BLEND) + corners[i] * BLEND;
        return smoothed.clone();
    }

    public void reset() { smoothed = null; }

    /** {@code corners} rotated to the cyclic order closest to {@code reference}. */
    static double[] alignedTo(double[] reference, double[] corners) {
        int best = 0;
        double lowest = Double.MAX_VALUE;
        for (int shift = 0; shift < 4; shift++) {
            double cost = 0;
            for (int i = 0; i < 4; i++) {
                int j = (i + shift) % 4;
                cost += Math.hypot(reference[i * 2] - corners[j * 2], reference[i * 2 + 1] - corners[j * 2 + 1]);
            }
            if (cost < lowest) { lowest = cost; best = shift; }
        }
        double[] out = new double[8];
        for (int i = 0; i < 4; i++) {
            int j = (i + best) % 4;
            out[i * 2] = corners[j * 2];
            out[i * 2 + 1] = corners[j * 2 + 1];
        }
        return out;
    }

    /** A corner moving more than a fifth of the quad's own size means the cube was turned. */
    private static boolean jumped(double[] previous, double[] current) {
        double diagonal = Math.hypot(previous[0] - previous[4], previous[1] - previous[5]);
        double limit = Math.max(18.0, diagonal * 0.20);
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(previous[i * 2] - current[i * 2], previous[i * 2 + 1] - current[i * 2 + 1]) > limit) {
                return true;
            }
        }
        return false;
    }
}
