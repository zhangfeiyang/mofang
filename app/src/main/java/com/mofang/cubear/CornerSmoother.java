package com.mofang.cubear;

import org.opencv.core.Point;

/**
 * Averages the detected quadrilateral across consecutive frames before it is used.
 *
 * <p>The network's corner error is part systematic and part frame-to-frame jitter. Only the jitter
 * can be removed for free, and averaging removes it. This runs before sampling rather than only
 * before drawing, because a quad that wobbles by a few percent walks the nine sample points towards
 * the edges of their cells, which is what makes a face need several tries to register.
 *
 * <p>The average is abandoned whenever the quad jumps, so following the cube as it is turned stays
 * immediate and only a genuinely still cube gets smoothed.
 */
public final class CornerSmoother {
    /** Weight given to the newest observation once the quad is being tracked. */
    private static final double BLEND = 0.4;

    private Point[] smoothed;

    /** Returns the smoothed quad for this frame, or null when given nothing. */
    public Point[] update(Point[] corners) {
        if (corners == null) {
            smoothed = null;
            return null;
        }
        if (smoothed == null || jumped(smoothed, corners)) {
            smoothed = copy(corners);
            return copy(smoothed);
        }
        for (int i = 0; i < 4; i++) {
            smoothed[i] = new Point(
                smoothed[i].x * (1 - BLEND) + corners[i].x * BLEND,
                smoothed[i].y * (1 - BLEND) + corners[i].y * BLEND);
        }
        return copy(smoothed);
    }

    public void reset() { smoothed = null; }

    /** A corner moving more than a fifth of the quad's own size means the cube was turned. */
    private static boolean jumped(Point[] previous, Point[] current) {
        double diagonal = Math.hypot(previous[0].x - previous[2].x, previous[0].y - previous[2].y);
        double limit = Math.max(18.0, diagonal * 0.20);
        for (int i = 0; i < 4; i++) {
            if (Math.hypot(previous[i].x - current[i].x, previous[i].y - current[i].y) > limit) {
                return true;
            }
        }
        return false;
    }

    private static Point[] copy(Point[] corners) {
        Point[] out = new Point[4];
        for (int i = 0; i < 4; i++) out[i] = new Point(corners[i].x, corners[i].y);
        return out;
    }
}
