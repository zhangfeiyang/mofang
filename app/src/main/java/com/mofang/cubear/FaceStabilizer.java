package com.mofang.cubear;

/**
 * Emits a face only once its readings have held still for several frames.
 *
 * <p>Stability is judged on the raw Lab readings, not on the provisional colour names. Judging it
 * on names meant a sticker flickering between red and orange, which the fixed threshold did
 * constantly, reset the counter and the face never got captured however long it was held there.
 * The measurements themselves are far steadier than the labels put on them.
 */
public final class FaceStabilizer {
    /** Frame-to-frame drift allowed per patch. This gates steadiness, not colour identity. */
    private static final float DRIFT_TOLERANCE = 14f;
    /**
     * Occluded or glared patches tolerated per face. The nine-per-colour constraint plus the piece
     * rules recover this many comfortably, and insisting on more readable patches than this rejects
     * roughly one usable frame in ten for no gain.
     */
    private static final int MAX_UNRELIABLE = 2;

    /**
     * Frames that may fail in a row before a steady run is abandoned. Detection and sampling each
     * drop the occasional frame, and restarting the count on every one of them made capturing a
     * face far less likely than the per-frame success rate suggests.
     */
    private static final int MISSES_TOLERATED = 2;

    private final int framesRequired;
    private FaceSample previous;
    private int stableFrames;
    private int consecutiveMisses;
    private boolean emitted;
    private float[][] labSum;
    private int[] labCount;

    public FaceStabilizer(int framesRequired) {
        this.framesRequired = framesRequired;
    }

    public FaceSample push(FaceSample sample) {
        if (!usable(sample)) {
            if (++consecutiveMisses > MISSES_TOLERATED) resetCandidate();
            return null;
        }
        consecutiveMisses = 0;
        if (previous == null || !holdsStill(previous, sample)) {
            previous = sample;
            stableFrames = 1;
            emitted = false;
            startAveraging(sample);
            return null;
        }
        previous = sample;
        stableFrames++;
        accumulate(sample);
        if (!emitted && stableFrames >= framesRequired) {
            emitted = true;
            return averaged(sample);
        }
        return null;
    }

    private void startAveraging(FaceSample sample) {
        if (sample.lab == null) { labSum = null; labCount = null; return; }
        labSum = new float[9][3];
        labCount = new int[9];
        accumulate(sample);
    }

    private void accumulate(FaceSample sample) {
        if (labSum == null || sample.lab == null) return;
        for (int i = 0; i < 9; i++) {
            if (!sample.reliable[i]) continue;
            for (int channel = 0; channel < 3; channel++) labSum[i][channel] += sample.lab[i][channel];
            labCount[i]++;
        }
    }

    /**
     * Emits the mean of the readings gathered over the steady run rather than the final frame.
     * Sensor noise is independent between frames, so averaging shrinks it, and the colour
     * assignment downstream is only ever as good as the measurements handed to it.
     */
    private FaceSample averaged(FaceSample latest) {
        if (labSum == null) return latest;
        float[][] mean = new float[9][];
        boolean[] reliable = new boolean[9];
        for (int i = 0; i < 9; i++) {
            if (labCount[i] == 0) {
                mean[i] = latest.lab[i];
                reliable[i] = false;
                continue;
            }
            mean[i] = new float[]{labSum[i][0] / labCount[i], labSum[i][1] / labCount[i],
                labSum[i][2] / labCount[i]};
            reliable[i] = true;
        }
        return new FaceSample(latest.stickers, mean, reliable, latest.confidence);
    }

    private static boolean usable(FaceSample sample) {
        if (sample == null) return false;
        if (sample.lab == null) return !sample.containsUnknown() && sample.confidence >= 0.80f;
        // The centre names the face, so it alone must be trustworthy.
        return sample.centerReliable() && sample.unreliableCount() <= MAX_UNRELIABLE;
    }

    private static boolean holdsStill(FaceSample previous, FaceSample current) {
        if (previous.lab == null || current.lab == null) {
            return previous.signature().equals(current.signature());
        }
        for (int i = 0; i < 9; i++) {
            if (!previous.reliable[i] || !current.reliable[i]) continue;
            if (Lab.distance(previous.lab[i], current.lab[i]) > DRIFT_TOLERANCE) return false;
        }
        return true;
    }

    public void resetCandidate() {
        previous = null;
        stableFrames = 0;
        consecutiveMisses = 0;
        emitted = false;
        labSum = null;
        labCount = null;
    }
}
