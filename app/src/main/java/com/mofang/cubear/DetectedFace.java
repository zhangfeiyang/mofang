package com.mofang.cubear;

/** A recognized face plus its four corners in the analyzed camera frame. */
public final class DetectedFace {
    public final FaceSample sample;
    public final float[] corners; // TL, TR, BR, BL as x,y pairs.
    public final int imageWidth;
    public final int imageHeight;
    public final float detectionScore;

    public DetectedFace(FaceSample sample, float[] corners, int imageWidth, int imageHeight,
                        float detectionScore) {
        if (corners.length != 8) throw new IllegalArgumentException("Four corners required");
        this.sample = sample;
        this.corners = corners.clone();
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.detectionScore = detectionScore;
    }

    /**
     * Shortest side of the quad as a fraction of the frame's short edge.
     *
     * <p>A face the camera barely resolves cannot be read honestly: each sticker lands on a
     * handful of pixels and the median comes back plausible but wrong, poisoning the observation
     * pool. Callers gate capture on this instead of trusting such samples.
     */
    public float minSideFraction() {
        float shortest = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int next = (i + 1) % 4;
            float dx = corners[next * 2] - corners[i * 2];
            float dy = corners[next * 2 + 1] - corners[i * 2 + 1];
            shortest = Math.min(shortest, (float) Math.hypot(dx, dy));
        }
        return shortest / Math.min(imageWidth, imageHeight);
    }
}
