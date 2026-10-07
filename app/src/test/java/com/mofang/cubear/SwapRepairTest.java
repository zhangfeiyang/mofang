package com.mofang.cubear;

import static org.junit.Assert.*;
import java.util.Random;
import org.junit.Test;

/**
 * Six scanned faces with one red and one orange sticker read the wrong way round, as on the phone
 * on 2026-10-04: the counts stay at nine each, the cube fails its check, and the old five-face
 * fallback rebuilt yellow wrongly. The repair must recover the real cube.
 */
public class SwapRepairTest {
    private static final float[] RED = {50, 56, 20}, ORANGE = {63, 31, 50}, WHITE = {80, -2, 5},
        YELLOW = {77, -10, 72}, GREEN = {66, -52, 52}, BLUE = {44, -2, -42};

    private static float[] lab(char face) {
        switch (face) {
            case 'U': return WHITE;
            case 'R': return RED;
            case 'F': return GREEN;
            case 'D': return YELLOW;
            case 'L': return ORANGE;
            default: return BLUE;
        }
    }

    private static String scramble(long seed) {
        String[] moves = {"U", "U2", "U'", "R", "R2", "R'", "F", "F2", "F'", "D", "D2", "D'",
            "L", "L2", "L'", "B", "B2", "B'"};
        Random random = new Random(seed);
        String state = GuideCube.solved();
        for (int i = 0; i < 25; i++) state = CubeMoves.apply(state, moves[random.nextInt(moves.length)]);
        return state;
    }

    /** Two looks of each face, with the given facelets' readings replaced. */
    private static CubeStateAssembler scan(String state, int redAsOrange, int orangeAsRed) {
        CubeStateAssembler assembler = new CubeStateAssembler();
        Random noise = new Random(1);
        for (int look = 0; look < 2; look++) {
            for (int face = 0; face < 6; face++) {
                float[][] readings = new float[9][];
                CubeColor[] stickers = new CubeColor[9];
                java.util.Arrays.fill(stickers, CubeColor.UNKNOWN);
                for (int cell = 0; cell < 9; cell++) {
                    int index = face * 9 + cell;
                    float[] base = index == redAsOrange ? new float[]{60, 37, 42}
                        : index == orangeAsRed ? new float[]{53, 49, 27} : lab(state.charAt(index));
                    readings[cell] = new float[]{base[0] + noise.nextFloat() * 2 - 1,
                        base[1] + noise.nextFloat() * 2 - 1, base[2] + noise.nextFloat() * 2 - 1};
                }
                stickers[4] = CubeColor.fromFace(state.charAt(face * 9 + 4));
                boolean[] reliable = new boolean[9];
                java.util.Arrays.fill(reliable, true);
                assembler.put(new FaceSample(stickers, readings, reliable, 1f));
            }
        }
        return assembler;
    }

    @Test public void aSwappedRedAndOrangeIsRepaired() {
        int repaired = 0, trials = 0;
        Random pick = new Random(7);
        for (long seed = 1; seed <= 150; seed++) {
            String state = scramble(seed);
            java.util.List<Integer> reds = new java.util.ArrayList<>(), oranges = new java.util.ArrayList<>();
            for (int i = 0; i < 54; i++) {
                if (i % 9 == 4) continue;
                if (state.charAt(i) == 'R') reds.add(i);
                if (state.charAt(i) == 'L') oranges.add(i);
            }
            int red = reds.get(pick.nextInt(reds.size()));
            int orange = oranges.get(pick.nextInt(oranges.size()));
            trials++;
            CubeStateAssembler assembler = scan(state, red, orange);
            assertTrue(assembler.isComplete());
            String assembled = assembler.assemble();
            // Never a wrong cube: either the real one, or a refusal naming a face to show again.
            if (assembled != null) {
                assertEquals("seed " + seed, state, assembled);
                repaired++;
            } else {
                assertNotNull("seed " + seed, assembler.suspect());
            }
        }
        System.out.println("swap repair: " + repaired + " of " + trials);
        assertTrue(repaired + " of " + trials, repaired * 10 >= trials * 9);
    }

    @Test public void fiveFacesAreNeverEnough() {
        String state = scramble(3);
        CubeStateAssembler assembler = new CubeStateAssembler();
        CubeStateAssembler full = scan(state, -1, -1);
        for (FaceSample look : full.observations()) {
            if (look.center() != CubeColor.YELLOW) assembler.put(look);
        }
        assertFalse(assembler.isComplete());
        assertNull(assembler.assemble());
        assertEquals(state, full.assemble());
    }
}
