package com.mofang.cubear;

/**
 * The six colours as this particular cube looked under this particular light.
 *
 * <p>Once a scan has been solved, the six centre readings are known good, so live frames can be
 * named by nearest prototype instead of by absolute thresholds. That matters most during the
 * guidance phase: recognising that a turn has happened means reading nine stickers correctly on
 * every step, and a fixed hue boundary is wrong often enough to stall the walkthrough.
 *
 * <p>When the sixth face was inferred rather than scanned, the palette starts with five
 * prototypes and remembers which colour was never seen. Patches too far from every prototype stay
 * unnamed — they can only belong to the missing colour — and the first steady centre of that
 * colour completes the palette on the spot.
 */
public final class ScanPalette {
    /**
     * Distance beyond which a patch matches no known prototype. Red and orange, the closest pair
     * on a real cube, sit further apart than this even under drift, so only a genuinely unseen
     * colour lands past the cap.
     */
    private static final float UNSEEN_CAP = 24f;

    private float[][] prototypes;
    private CubeColor[] names;
    /** The colour with no prototype yet, or null once all six have been seen. */
    private CubeColor missing;

    private ScanPalette(float[][] prototypes, CubeColor[] names, CubeColor missing) {
        this.prototypes = prototypes;
        this.names = names;
        this.missing = missing;
    }

    public static ScanPalette from(ColorAssignment.Result result) {
        if (result == null || result.prototypes == null) return null;
        float[][] copy = new float[result.prototypes.length][];
        for (int i = 0; i < copy.length; i++) copy[i] = result.prototypes[i].clone();
        return new ScanPalette(copy, result.faceColors.clone(), null);
    }

    public static ScanPalette fromFive(float[][] prototypes, CubeColor[] names,
                                       CubeColor missing) {
        float[][] copy = new float[prototypes.length][];
        for (int i = 0; i < copy.length; i++) copy[i] = prototypes[i].clone();
        return new ScanPalette(copy, names.clone(), missing);
    }

    /** The colour that was inferred rather than scanned, or null when the palette is complete. */
    public CubeColor missingColor() { return missing; }

    /**
     * Adopts a centre reading as the missing colour's prototype.
     *
     * <p>The caller must already know the face on camera is the missing colour; any reliable
     * centre that matches none of the five known prototypes can only be that.
     */
    public void learnMissing(float[] lab) {
        if (missing == null || lab == null) return;
        float[][] grown = new float[prototypes.length + 1][];
        for (int i = 0; i < prototypes.length; i++) grown[i] = prototypes[i];
        grown[prototypes.length] = lab.clone();
        prototypes = grown;
        CubeColor[] grownNames = new CubeColor[names.length + 1];
        for (int i = 0; i < names.length; i++) grownNames[i] = names[i];
        grownNames[names.length] = missing;
        names = grownNames;
        missing = null;
    }

    /**
     * Completes a five-colour palette from a reliable patch of the missing colour.
     *
     * <p>On a scrambled cube that colour already shows up on the faces that were scanned, so
     * guidance does not have to wait for the unseen centre to face the camera. A patch only
     * counts when it is nearer the lighting-adapted missing colour than any scanned prototype —
     * otherwise a highlight sitting far from all five would be adopted as a fake sixth colour.
     *
     * @return true when the missing colour was learned from this sample
     */
    public boolean maybeLearn(FaceSample sample) {
        if (missing == null || sample == null || sample.lab == null) return false;
        float[] expected = adaptedMissing();
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < 9; i++) {
            if (!sample.reliable[i]) continue;
            float toExpected = Lab.distance(sample.lab[i], expected);
            float toKnown = (float) Math.sqrt(nearestDistanceSquared(sample.lab[i]));
            if (toExpected + 8f >= toKnown) continue;
            // Prefer the centre when it qualifies: that reading already named a face.
            float score = toExpected + (i == 4 ? 0f : 4f);
            if (score < bestScore) {
                bestScore = score;
                best = i;
            }
        }
        if (best < 0) return false;
        learnMissing(sample.lab[best]);
        return true;
    }

    /** Canonical missing colour, shifted by the mean Lab offset the five centres show. */
    private float[] adaptedMissing() {
        float[] offset = new float[3];
        for (int i = 0; i < prototypes.length; i++) {
            int argb = names[i].argb;
            float[] canonical = Lab.fromRgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
            for (int channel = 0; channel < 3; channel++) {
                offset[channel] += prototypes[i][channel] - canonical[channel];
            }
        }
        int argb = missing.argb;
        float[] sixth = Lab.fromRgb((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
        float count = prototypes.length;
        for (int channel = 0; channel < 3; channel++) sixth[channel] += offset[channel] / count;
        return sixth;
    }

    private float nearestDistanceSquared(float[] lab) {
        float best = Float.MAX_VALUE;
        for (float[] prototype : prototypes) {
            best = Math.min(best, Lab.distanceSquared(lab, prototype));
        }
        return best;
    }

    public CubeColor nearest(float[] lab) {
        return names[nearestIndex(lab)];
    }

    private int nearestIndex(float[] lab) {
        int best = 0;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < prototypes.length; i++) {
            float distance = Lab.distanceSquared(lab, prototypes[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    /**
     * Renames a live sample against this palette. Patches flagged unreliable stay unknown so the
     * caller can ignore them rather than act on a guess; with a colour still missing, patches
     * past the cap stay unknown too, because naming them to the nearest of five would be a guess.
     */
    public FaceSample relabel(FaceSample sample) {
        if (sample == null || sample.lab == null) return sample;
        CubeColor[] colors = new CubeColor[9];
        for (int i = 0; i < 9; i++) {
            if (!sample.reliable[i]) {
                colors[i] = CubeColor.UNKNOWN;
                continue;
            }
            float[] lab = sample.lab[i];
            if (missing != null && nearestDistanceSquared(lab) > UNSEEN_CAP * UNSEEN_CAP) {
                colors[i] = CubeColor.UNKNOWN;
                continue;
            }
            colors[i] = names[nearestIndex(lab)];
        }
        return new FaceSample(colors, sample.lab, sample.reliable, sample.confidence);
    }
}
