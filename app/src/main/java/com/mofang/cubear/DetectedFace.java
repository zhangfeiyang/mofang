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
}
