package com.mofang.cubear;

import static org.junit.Assert.*;

import cs.min2phase.Tools;
import java.util.Random;
import org.junit.Test;

/**
 * The fifth-face inference must survive what a phone actually produces: per-face lighting drift,
 * per-patch sensor noise, and arbitrary rolls — not just the clean canonical readings of
 * {@link SixthFaceSolverTest}. Calibration for the tolerance knobs lives here: the configuration
 * recovered 39/40 drifted scans while never returning a wrong cube.
 */
public class SixthFaceNoiseTest {
    private static final String SOLVED =
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    private static int[] rgbOf(CubeColor color) {
        return new int[]{(color.argb >> 16) & 0xFF, (color.argb >> 8) & 0xFF, color.argb & 0xFF};
    }

    private static FaceSample noisyFace(String state, char letter, int rolls, float gain,
                                        float[] shift, Random rng) {
        int base = "URFDLB".indexOf(letter) * 9;
        CubeColor[] stickers = new CubeColor[9];
        float[][] lab = new float[9][];
        for (int cell = 0; cell < 9; cell++) {
            int[] rgb = rgbOf(CubeColor.fromFace(state.charAt(base + cell)));
            int r = clamp(rgb[0] * gain + shift[0] + rng.nextGaussian() * 5);
            int g = clamp(rgb[1] * gain + shift[1] + rng.nextGaussian() * 5);
            int b = clamp(rgb[2] * gain + shift[2] + rng.nextGaussian() * 5);
            lab[cell] = Lab.fromRgb(r, g, b);
            stickers[cell] = CubeColor.classify(r, g, b);
        }
        FaceSample sample = new FaceSample(stickers, lab, 0.9f);
        for (int i = 0; i < rolls; i++) sample = sample.rotateClockwise();
        return sample;
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    @Test public void driftedScansStillInferTheMissingFace() {
        String state = SOLVED;
        for (String move : "R U2 F' L D B2 R'".split(" ")) state = CubeMoves.apply(state, move);
        int solved = 0;
        int trials = 40;
        long start = System.currentTimeMillis();
        for (int t = 0; t < trials; t++) {
            Random rng = new Random(t * 31L + 3);
            char missing = "URFDLB".charAt(rng.nextInt(6));
            FaceSample[] faces = new FaceSample[5];
            int i = 0;
            for (char letter : "URFDLB".toCharArray()) {
                if (letter == missing) continue;
                float gain = 0.85f + rng.nextFloat() * 0.2f;
                float[] shift = {(float) rng.nextGaussian() * 8,
                    (float) rng.nextGaussian() * 8, (float) rng.nextGaussian() * 8};
                faces[i++] = noisyFace(state, letter, rng.nextInt(4), gain, shift, rng);
            }
            SixthFaceSolver.Result r = SixthFaceSolver.solve(faces);
            if (r == null) continue;
            assertEquals("a returned cube must be the scanned one, trial " + t, state, r.state);
            assertEquals(0, Tools.verify(r.state));
            solved++;
        }
        System.out.println("realistic noise: " + solved + "/" + trials + " solved in "
            + (System.currentTimeMillis() - start) + "ms");
        assertTrue("at least 36/40 drifted scans must infer the sixth face", solved >= 36);
    }
}
