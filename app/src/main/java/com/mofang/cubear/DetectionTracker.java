package com.mofang.cubear;

/**
 * Bridges a few missed frames so the AR quad does not blink out during a brief dropout.
 *
 * <p>Smoothing itself now happens in {@link CornerSmoother}, upstream of sampling, so that the
 * sample points benefit too. Repeating it here would only add lag between the cube and the overlay.
 */
public final class DetectionTracker {
    private DetectedFace tracked;
    private int missedFrames;

    public DetectedFace update(DetectedFace detection) {
        if (detection == null) {
            missedFrames++;
            if (missedFrames > 4) tracked = null;
            return tracked;
        }
        missedFrames = 0;
        tracked = detection;
        return tracked;
    }

    public void reset() { tracked = null; missedFrames = 0; }

}
