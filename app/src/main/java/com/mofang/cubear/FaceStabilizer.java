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
     * rules recover this many comfortably, and every extra demanded patch is a frame the user has
     * to keep presenting, so this stays as generous as the assignment step can absorb.
     */
    private static final int MAX_UNRELIABLE = 3;

    /**
     * Frames that may fail in a row before a steady run is abandoned. Detection and sampling each
     * drop the occasional frame, and restarting the count on every one of them made capturing a
     * face far less likely than the per-frame success rate suggests.
     */
    private static final int MISSES_TOLERATED = 2;

    private final int framesRequired;
    private FaceSample canonical;
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
        if (canonical == null || !holdsStill(canonical, sample)) {
            canonical = sample;
            stableFrames = 1;
            emitted = false;
            startAveraging(sample);
            return null;
        }
        // The user holds the cube at an arbitrary in-plane angle, and the detector's corner
        // ordering flips by 90 degrees whenever the angle crosses one of its boundaries — the
        // same stationary face then arrives rotated. Comparing cells raw would reset the run on
        // every flip and no face would ever be captured; comparing after roll alignment is what
        // makes "hold it still" mean still, whatever the angle.
        FaceSample aligned = canonical.lab == null || sample.lab == null
            ? sample
            : alignedTo(canonical, sample);
        stableFrames++;
        accumulate(aligned);
        if (!emitted && stableFrames >= framesRequired) {
            emitted = true;
            return averaged(aligned);
        }
        return null;
    }

    /**
     * How close the current steady run is to emitting, from 0 to 1.
     *
     * <p>Feeding this to the overlay tells the user how much longer to hold the face still, which
     * is the difference between waiting in confidence and giving up at two thirds of a capture.
     */
    public float progress() {
        if (canonical == null || emitted) return 0f;
        return Math.min(1f, stableFrames / (float) framesRequired);
    }

    /**
     * Rotates {@code sample} into {@code reference}'s roll frame.
     *
     * <p>The face's colour pattern decides: of the four quarter-turns, the one whose cells agree
     * best with the reference is the same physical face at the same roll. A scrambled face is
     * almost never rotation-symmetric, so the winner is unambiguous.
     */
    private static FaceSample alignedTo(FaceSample reference, FaceSample sample) {
        FaceSample best = sample, rotated = sample;
        double lowest = Double.MAX_VALUE;
        for (int turn = 0; turn < 4; turn++) {
            double cost = 0;
            for (int cell = 0; cell < 9; cell++) {
                cost += Lab.distance(reference.lab[cell], rotated.lab[cell]);
            }
            if (cost < lowest) { lowest = cost; best = rotated; }
            rotated = rotated.rotateClockwise();
        }
        return best;
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
        // The centre names the face, so it alone must be trustworthy. No split gate here: the
        // 58-unit cut was calibrated against a pool whose green looks it excluded, and on this
        // user's cube the honest green face reads split 75-90 — the gate silently made one face
        // uncapturable. Synthetic ground truth says honest scrambled faces reach 105. Straddles
        // are handled downstream where there is multi-look evidence: per-colour clustering, the
        // consensus close-filter, the retry ladders, and the two-looks-per-colour rule.
        return sample.centerReliable() && sample.unreliableCount() <= MAX_UNRELIABLE;
    }

    private static boolean holdsStill(FaceSample reference, FaceSample current) {
        if (reference.lab == null || current.lab == null) {
            return reference.signature().equals(current.signature());
        }
        FaceSample aligned = alignedTo(reference, current);
        for (int i = 0; i < 9; i++) {
            if (!reference.reliable[i] || !aligned.reliable[i]) continue;
            if (Lab.distance(reference.lab[i], aligned.lab[i]) > DRIFT_TOLERANCE) return false;
        }
        return true;
    }

    public void resetCandidate() {
        canonical = null;
        stableFrames = 0;
        consecutiveMisses = 0;
        emitted = false;
        labSum = null;
        labCount = null;
    }
}
