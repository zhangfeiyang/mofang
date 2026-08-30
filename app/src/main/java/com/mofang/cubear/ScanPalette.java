package com.mofang.cubear;

/**
 * The six colours as this particular cube looked under this particular light.
 *
 * <p>Once a scan has been solved, the six centre readings are known good, so live frames can be
 * named by nearest prototype instead of by absolute thresholds. That matters most during the
 * guidance phase: recognising that a turn has happened means reading nine stickers correctly on
 * every step, and a fixed hue boundary is wrong often enough to stall the walkthrough.
 */
public final class ScanPalette {
    private final float[][] prototypes;
    private final CubeColor[] names;

    private ScanPalette(float[][] prototypes, CubeColor[] names) {
        this.prototypes = prototypes;
        this.names = names;
    }

    public static ScanPalette from(ColorAssignment.Result result) {
        if (result == null || result.prototypes == null) return null;
        float[][] copy = new float[result.prototypes.length][];
        for (int i = 0; i < copy.length; i++) copy[i] = result.prototypes[i].clone();
        return new ScanPalette(copy, result.faceColors.clone());
    }

    public CubeColor nearest(float[] lab) {
        CubeColor best = CubeColor.UNKNOWN;
        float bestDistance = Float.MAX_VALUE;
        for (int i = 0; i < prototypes.length; i++) {
            float distance = Lab.distanceSquared(lab, prototypes[i]);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = names[i];
            }
        }
        return best;
    }

    /**
     * Renames a live sample against this palette. Patches flagged unreliable stay unknown so the
     * caller can ignore them rather than act on a guess.
     */
    public FaceSample relabel(FaceSample sample) {
        if (sample == null || sample.lab == null) return sample;
        CubeColor[] colors = new CubeColor[9];
        for (int i = 0; i < 9; i++) {
            colors[i] = sample.reliable[i] ? nearest(sample.lab[i]) : CubeColor.UNKNOWN;
        }
        return new FaceSample(colors, sample.lab, sample.reliable, sample.confidence);
    }
}
