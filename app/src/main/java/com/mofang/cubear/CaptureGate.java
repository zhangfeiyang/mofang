package com.mofang.cubear;

/**
 * Decides whether a detection's readings may feed a capture.
 *
 * <p>A refined detection always may, and so may the detector's own quad when the refiner could
 * not anchor (fingers, blur, a cube it cannot see): the retrained detector's corners are good
 * enough to read through its inset sampler. What is refused is a disputed frame — the refiner
 * found a lattice the detector disagrees with, so one of them is on the wrong face or straddles
 * an edge, and nothing says which. Those were the readings behind most wrong captures.
 *
 * <p>An unrefined frame must also show a sticker at its centre. Refinement fails mostly because
 * something covers the face, and a thumb across the middle reads as a flat, perfectly "reliable"
 * skin-coloured centre: on the demo video such looks formed a junk group that blocked the
 * five-face inference until the sixth face arrived. Refined frames are exempt, so a white face
 * under warm light, beige rather than white, still captures.
 *
 * <p>The gate also notices a long run of unrefined frames, which almost always means the face is
 * partly covered, so the UI can say so.
 */
final class CaptureGate {
    /** Consecutive unrefined detections after which the face is presumed partly blocked. */
    static final int STRUGGLING_AFTER = 8;

    private int coarseStreak;

    /** @return true when this detection's sample may be used for capture */
    boolean admit(DetectedFace face) {
        if (face == null) return false;
        if (face.refined) {
            coarseStreak = 0;
            return true;
        }
        if (coarseStreak < Integer.MAX_VALUE) coarseStreak++;
        return !face.disputed && stickerLikeCentre(face.sample);
    }

    /** Saturated like a coloured sticker, or bright and neutral like a well-lit white one. */
    static boolean stickerLikeCentre(FaceSample sample) {
        float[] lab = sample == null ? null : sample.centerLab();
        if (lab == null) return sample != null;
        float chroma = Lab.chroma(lab);
        return chroma >= 40f || (lab[0] >= 68f && chroma <= 22f);
    }

    /** True while detections keep arriving without the refiner anchoring: likely an occlusion. */
    boolean struggling() {
        return coarseStreak >= STRUGGLING_AFTER;
    }

    void reset() { coarseStreak = 0; }
}
