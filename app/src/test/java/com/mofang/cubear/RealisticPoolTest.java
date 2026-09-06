package com.mofang.cubear;

import static org.junit.Assert.*;
import cs.min2phase.Tools;
import java.util.Random;
import org.junit.Test;

/**
 * Reproduces what the assembler actually receives on a phone: a pool of many observations with
 * per-frame lighting drift, not one clean reading per face.
 */
public class RealisticPoolTest {
    private static final String SOLVED =
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    private static int[] rgbOf(CubeColor color) {
        return new int[]{(color.argb >> 16) & 0xFF, (color.argb >> 8) & 0xFF, color.argb & 0xFF};
    }

    /** One look at a face, with a whole-frame lighting shift and a little per-patch noise. */
    private static FaceSample look(String state, int face, float gain, int rolls, Random rng) {
        CubeColor[] provisional = new CubeColor[9];
        float[][] lab = new float[9][];
        for (int cell = 0; cell < 9; cell++) {
            CubeColor truth = CubeColor.fromFace(state.charAt(face * 9 + cell));
            int[] rgb = rgbOf(truth);
            int r = clamp(rgb[0] * gain + rng.nextGaussian() * 6);
            int g = clamp(rgb[1] * gain + rng.nextGaussian() * 6);
            int b = clamp(rgb[2] * gain + rng.nextGaussian() * 6);
            lab[cell] = Lab.fromRgb(r, g, b);
            provisional[cell] = CubeColor.classify(r, g, b);
        }
        FaceSample sample = new FaceSample(provisional, lab, 0.6f + rng.nextFloat() * 0.4f);
        for (int i = 0; i < rolls; i++) sample = sample.rotateClockwise();
        return sample;
    }

    private static int clamp(double value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }

    @Test public void aPoolOfManyLooksPerFaceStillAssembles() {
        String state = SOLVED;
        for (String move : "L' U F2 R D' B".split(" ")) state = CubeMoves.apply(state, move);

        Random rng = new Random(7);
        CubeStateAssembler assembler = new CubeStateAssembler();
        // Four looks at each face, each under its own lighting, in the order a user would turn.
        for (int pass = 0; pass < 4; pass++) {
            for (int face = 0; face < 6; face++) {
                float gain = 0.80f + pass * 0.09f;
                assembler.put(look(state, face, gain, rng.nextInt(4), rng));
            }
        }
        assertEquals("six faces should be recognised", 6, assembler.size());

        String assembled = assembler.assembleLegalState();
        assertNotNull("a realistic pool must still assemble", assembled);
        assertEquals(0, Tools.verify(assembled));
    }

    /**
     * A quad spanning two faces reads nine perfectly flat stickers, so it scores a high confidence
     * and outranks honest looks that lost a patch to a finger. Two such straddles are enough that
     * swapping one face at a time can never recover.
     */
    @Test public void straddlingObservationsDoNotSinkTheScan() {
        String state = SOLVED;
        for (String move : "L' U F2 R D' B".split(" ")) state = CubeMoves.apply(state, move);

        Random rng = new Random(11);
        CubeStateAssembler assembler = new CubeStateAssembler();
        for (int pass = 0; pass < 4; pass++) {
            for (int face = 0; face < 6; face++) {
                assembler.put(look(state, face, 0.80f + pass * 0.09f, rng.nextInt(4), rng));
            }
        }
        // Two straddles: the left column belongs to a neighbouring face, the rest to this one.
        for (int face : new int[]{1, 4}) {
            assembler.put(straddle(state, face, (face + 2) % 6, rng));
        }

        String assembled = assembler.assembleLegalState();
        assertNotNull("a straddled look must not block an otherwise good scan", assembled);
        assertEquals(0, Tools.verify(assembled));
    }

    /**
     * The thin case: several faces were only glanced at twice, and a straddle is one of the two.
     * There is no majority to outvote it, so recovery has to come from dropping looks.
     */
    @Test public void straddlesSurviveEvenWhenAFaceWasOnlySeenTwice() {
        String state = SOLVED;
        for (String move : "R U2 D' B F' L".split(" ")) state = CubeMoves.apply(state, move);

        Random rng = new Random(23);
        CubeStateAssembler assembler = new CubeStateAssembler();
        for (int face = 0; face < 6; face++) {
            assembler.put(look(state, face, 0.88f, rng.nextInt(4), rng));
            assembler.put(face == 2 || face == 5
                ? straddle(state, face, (face + 1) % 6, rng)
                : look(state, face, 0.95f, rng.nextInt(4), rng));
        }
        assertEquals(6, assembler.size());

        String assembled = assembler.assembleLegalState();
        assertNotNull("two thin faces spoiled at once must still be recoverable", assembled);
        assertEquals(0, Tools.verify(assembled));
    }

    @Test public void fiveFacesWithSeveralLooksEachStillInferTheSixth() {
        String state = SOLVED;
        for (String move : "R U2 F' L D B2 R'".split(" ")) state = CubeMoves.apply(state, move);

        Random rng = new Random(13);
        CubeStateAssembler assembler = new CubeStateAssembler();
        for (int pass = 0; pass < 3; pass++) {
            for (int face = 0; face < 6; face++) {
                if (face == 3) continue;
                assembler.put(look(state, face, 0.84f + pass * 0.07f, rng.nextInt(4), rng));
            }
        }
        assertEquals(5, assembler.size());
        assertNull(assembler.assembleLegalState());
        String assembled = assembler.assembleFromFiveFaces();
        assertNotNull("five well-observed faces must infer the sixth", assembled);
        assertEquals(state, assembled);
        assertEquals(0, Tools.verify(assembled));
        assertEquals(CubeColor.YELLOW, assembler.palette().missingColor());
    }

    /** Nine flat stickers, but the left column is taken from the neighbouring face. */
    private static FaceSample straddle(String state, int face, int neighbour, Random rng) {
        CubeColor[] provisional = new CubeColor[9];
        float[][] lab = new float[9][];
        for (int cell = 0; cell < 9; cell++) {
            int source = cell % 3 == 0 ? neighbour : face;
            CubeColor truth = CubeColor.fromFace(state.charAt(source * 9 + cell));
            int[] rgb = rgbOf(truth);
            int r = clamp(rgb[0] + rng.nextGaussian() * 4);
            int g = clamp(rgb[1] + rng.nextGaussian() * 4);
            int b = clamp(rgb[2] + rng.nextGaussian() * 4);
            lab[cell] = Lab.fromRgb(r, g, b);
            provisional[cell] = CubeColor.classify(r, g, b);
        }
        // Flat patches and a confident quad: exactly the score an honest look struggles to beat.
        return new FaceSample(provisional, lab, 1f);
    }
}
