package com.mofang.cubear;

import static org.junit.Assert.*;
import cs.min2phase.Tools;
import java.util.Random;
import org.junit.Test;

/**
 * Pool management on the kind of stream the camera really delivers: many looks per face, and
 * now and then a junk look whose centre matches no face at all.
 */
public class AssemblerPoolTest {
    private static final String SOLVED =
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    private static String scrambled() {
        String state = SOLVED;
        for (String move : "L' U F2 R D' B R2 F".split(" ")) state = CubeMoves.apply(state, move);
        return state;
    }

    private static FaceSample look(String state, int face, Random rng, float confidence) {
        CubeColor[] provisional = new CubeColor[9];
        float[][] lab = new float[9][];
        for (int cell = 0; cell < 9; cell++) {
            CubeColor truth = CubeColor.fromFace(state.charAt(face * 9 + cell));
            int r = clamp(((truth.argb >> 16) & 0xFF) + rng.nextGaussian() * 4);
            int g = clamp(((truth.argb >> 8) & 0xFF) + rng.nextGaussian() * 4);
            int b = clamp((truth.argb & 0xFF) + rng.nextGaussian() * 4);
            lab[cell] = Lab.fromRgb(r, g, b);
            provisional[cell] = CubeColor.classify(r, g, b);
        }
        FaceSample sample = new FaceSample(provisional, lab, confidence);
        for (int i = rng.nextInt(4); i > 0; i--) sample = sample.rotateClockwise();
        return sample;
    }

    /** Nine flat patches around a pink centre no face of the cube has: a typical straddle. */
    private static FaceSample junk() {
        CubeColor[] provisional = new CubeColor[9];
        float[][] lab = new float[9][];
        for (int cell = 0; cell < 9; cell++) {
            lab[cell] = Lab.fromRgb(236, 120, 170);
            provisional[cell] = CubeColor.RED;
        }
        return new FaceSample(provisional, lab, 1.0f);
    }

    private static int clamp(double v) { return (int) Math.max(0, Math.min(255, Math.round(v))); }

    @Test public void aSingleLookIsNotACollectedFace() {
        CubeStateAssembler assembler = new CubeStateAssembler();
        Random rng = new Random(1);
        String state = scrambled();
        assertFalse(assembler.put(look(state, 2, rng, 0.8f)));
        assertEquals(0, assembler.size());
        assertTrue(assembler.establishedColors().isEmpty());
        assertTrue("the second look confirms the face", assembler.put(look(state, 2, rng, 0.8f)));
        assertEquals(1, assembler.size());
    }

    @Test public void aConfidentJunkLookNeverEvictsARealFace() {
        String state = scrambled();
        Random rng = new Random(2);
        CubeStateAssembler assembler = new CubeStateAssembler();
        // Five faces held for a while, a junk look in between, then the sixth face — enough
        // looks to overflow the pool, which is when the old ranking dropped a real face.
        int[] order = {2, 4, 0, 1, 5};
        for (int k = 0; k < order.length; k++) {
            for (int i = 0; i < 8; i++) assembler.put(look(state, order[k], rng, 0.6f));
            if (k == 1) assembler.put(junk());
        }
        for (int i = 0; i < 6; i++) assembler.put(look(state, 3, rng, 0.6f));

        assertEquals("every real colour survives the overflow", 6, assembler.establishedColors().size());
        assertTrue(assembler.isComplete());
        String assembled = assembler.assemble();
        assertNotNull(assembler.lastFailure(), assembled);
        assertEquals(state, assembled);
    }

    @Test public void aJunkLookDoesNotBlockTheFiveFaceInference() {
        String state = scrambled();
        Random rng = new Random(3);
        CubeStateAssembler assembler = new CubeStateAssembler();
        for (int face : new int[]{0, 1, 2, 4, 5}) {
            assembler.put(look(state, face, rng, 0.7f));
            assembler.put(look(state, face, rng, 0.7f));
        }
        assembler.put(junk());
        assertEquals("the junk look is not a sixth face", 5, assembler.size());
        String assembled = assembler.assemble();
        if (assembled != null) {
            assertEquals(state, assembled);
            assertEquals(0, Tools.verify(assembled));
        } else {
            // Five faces do not always pin the sixth down; what must not happen is a refusal
            // because the stray look was counted as a group.
            assertNotEquals("还没有 5 个独立面", assembler.lastFailure());
        }
    }

    @Test public void aCopyAssemblesIndependentlyOfTheLivePool() {
        String state = scrambled();
        Random rng = new Random(4);
        CubeStateAssembler assembler = new CubeStateAssembler();
        for (int face = 0; face < 6; face++) {
            assembler.put(look(state, face, rng, 0.7f));
            assembler.put(look(state, face, rng, 0.7f));
        }
        CubeStateAssembler snapshot = assembler.copy();
        assembler.clear();
        assertEquals(0, assembler.size());
        assertEquals(state, snapshot.assemble());
        assertNotNull(snapshot.palette());
        assertNull(assembler.palette());
    }
}
