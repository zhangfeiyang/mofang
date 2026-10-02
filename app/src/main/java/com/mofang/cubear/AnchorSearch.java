package com.mofang.cubear;

/**
 * Finds a face without the network, by refining from a few likely positions.
 *
 * <p>Replaces the old contour-and-RANSAC fallback. The refiner already knows how to find a sticker
 * lattice near a rough quad and tolerates a pitch of shift, a large roll and a 60% size error, so
 * a handful of anchors — where the face was last seen, then centred squares at the sizes and
 * heights people hold a cube — covers the cases the fallback exists for: the network declining a
 * view for a few frames, or not loading at all. A few anchors are tried per frame, cycling, so a
 * long blind spell never stalls the analysis thread.
 */
final class AnchorSearch {
    /** Face side as a share of the frame width, largest first. */
    private static final double[] SIDES = {0.62, 0.46, 0.34};
    /** Face centre height as a share of the frame height. */
    private static final double[] HEIGHTS = {0.44, 0.58, 0.32};
    private static final int ATTEMPTS_PER_FRAME = 3;

    private final FaceRefiner refiner;
    private int next;

    AnchorSearch(FaceRefiner refiner) { this.refiner = refiner; }

    /** @param hint the last known face quad, tried first, or null */
    FaceRefiner.Result find(byte[] rgba, int width, int height, double[] hint) {
        int attempts = 0;
        if (hint != null) {
            FaceRefiner.Result result = refiner.refine(rgba, width, height, hint);
            if (result != null) return result;
            attempts++;
        }
        int anchors = SIDES.length * HEIGHTS.length;
        for (; attempts < ATTEMPTS_PER_FRAME; attempts++) {
            int k = next++ % anchors;
            double side = SIDES[k % SIDES.length] * width;
            double cx = width / 2.0, cy = HEIGHTS[k / SIDES.length] * height;
            double h = side / 2;
            double[] quad = {cx - h, cy - h, cx + h, cy - h, cx + h, cy + h, cx - h, cy + h};
            FaceRefiner.Result result = refiner.refine(rgba, width, height, quad);
            if (result != null) return result;
        }
        return null;
    }
}
