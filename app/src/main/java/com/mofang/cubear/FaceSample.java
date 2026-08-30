package com.mofang.cubear;

import java.util.Arrays;

/**
 * One scanned face.
 *
 * <p>{@link #stickers} is a provisional per-patch guess used for the live overlay and for deciding
 * when a face has held still. The authoritative colouring comes later from {@link ColorAssignment}
 * working on {@link #lab}, so the raw measurements are carried through rather than collapsed here.
 */
public final class FaceSample {
    private static final int[] CLOCKWISE = {6, 3, 0, 7, 4, 1, 8, 5, 2};

    public final CubeColor[] stickers;
    /** Nine Lab readings in the same order as {@link #stickers}, or null for provisional samples. */
    public final float[][] lab;
    /** False where the patch was not one flat colour, so its reading carries no colour evidence. */
    public final boolean[] reliable;
    public final float confidence;

    public FaceSample(CubeColor[] stickers, float confidence) {
        this(stickers, null, null, confidence);
    }

    public FaceSample(CubeColor[] stickers, float[][] lab, float confidence) {
        this(stickers, lab, null, confidence);
    }

    public FaceSample(CubeColor[] stickers, float[][] lab, boolean[] reliable, float confidence) {
        if (stickers.length != 9) throw new IllegalArgumentException("A face needs 9 stickers");
        if (lab != null && lab.length != 9) throw new IllegalArgumentException("A face needs 9 readings");
        if (reliable != null && reliable.length != 9) {
            throw new IllegalArgumentException("A face needs 9 reliability flags");
        }
        this.stickers = stickers.clone();
        this.lab = lab == null ? null : lab.clone();
        if (reliable != null) {
            this.reliable = reliable.clone();
        } else {
            this.reliable = new boolean[9];
            java.util.Arrays.fill(this.reliable, true);
        }
        this.confidence = confidence;
    }

    public int unreliableCount() {
        int count = 0;
        for (boolean ok : reliable) if (!ok) count++;
        return count;
    }

    public boolean centerReliable() { return reliable[4]; }

    public CubeColor center() { return stickers[4]; }

    /** Lab of the centre sticker, which acts as this face's colour prototype. */
    public float[] centerLab() { return lab == null ? null : lab[4]; }

    public String signature() {
        StringBuilder out = new StringBuilder(9);
        for (CubeColor sticker : stickers) out.append(sticker.face);
        return out.toString();
    }

    public boolean containsUnknown() {
        return Arrays.asList(stickers).contains(CubeColor.UNKNOWN);
    }

    public FaceSample rotateClockwise() {
        CubeColor[] rotated = new CubeColor[9];
        float[][] rotatedLab = lab == null ? null : new float[9][];
        boolean[] rotatedReliable = new boolean[9];
        for (int i = 0; i < 9; i++) {
            rotated[i] = stickers[CLOCKWISE[i]];
            rotatedReliable[i] = reliable[CLOCKWISE[i]];
            if (rotatedLab != null) rotatedLab[i] = lab[CLOCKWISE[i]];
        }
        return new FaceSample(rotated, rotatedLab, rotatedReliable, confidence);
    }
}
