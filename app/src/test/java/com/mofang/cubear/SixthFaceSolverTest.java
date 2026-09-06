package com.mofang.cubear;

import static org.junit.Assert.*;
import cs.min2phase.Tools;
import java.util.Random;
import org.junit.Test;

/** The sixth face must follow from the five that were scanned, whatever the missing one is. */
public class SixthFaceSolverTest {
    private static final String SOLVED =
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    /** One scanned face with canonical colour readings, at an arbitrary roll. */
    private static FaceSample faceOf(String state, char letter, int rolls) {
        return faceOf(state, letter, rolls, 1f);
    }

    private static String scrambled(String moves) {
        String state = SOLVED;
        for (String move : moves.split(" ")) state = CubeMoves.apply(state, move);
        return state;
    }

    private static int[] rgbOf(CubeColor color) {
        return new int[]{(color.argb >> 16) & 0xFF, (color.argb >> 8) & 0xFF, color.argb & 0xFF};
    }

    @Test public void infersEachPossibleMissingFace() {
        String state = scrambled("R U2 F' L D B2 R'");
        Random rng = new Random(5);
        for (char missing : "URFDLB".toCharArray()) {
            FaceSample[] faces = new FaceSample[5];
            int i = 0;
            for (char letter : "URFDLB".toCharArray()) {
                if (letter == missing) continue;
                faces[i++] = faceOf(state, letter, rng.nextInt(4));
            }
            SixthFaceSolver.Result result = SixthFaceSolver.solve(faces);
            assertNotNull("missing face " + missing, result);
            assertEquals(state, result.state);
            assertEquals(0, Tools.verify(result.state));
            assertNotNull(result.palette);
            assertEquals(CubeColor.fromFace(missing), result.palette.missingColor());
        }
    }

    @Test public void fourFacesAreNotEnough() {
        String state = scrambled("L' U F2 R D' B");
        FaceSample[] faces = {faceOf(state, 'U', 0), faceOf(state, 'R', 1),
            faceOf(state, 'F', 2), faceOf(state, 'D', 3)};
        assertNull(SixthFaceSolver.solve(faces));
    }

    @Test public void anImpossibleVisiblePieceKillsTheInference() {
        String state = scrambled("F R U2 B' L");
        FaceSample[] faces = new FaceSample[5];
        int i = 0;
        for (char letter : "URFDL".toCharArray()) faces[i++] = faceOf(state, letter, 0);
        // F1 sits on the fully visible UFL corner; recolouring it red ({U,R,L}) matches no piece.
        CubeColor[] corrupted = faces[2].stickers.clone();
        corrupted[1] = CubeColor.RED;
        float[][] labs = faces[2].lab.clone();
        int[] rgb = rgbOf(CubeColor.RED);
        labs[1] = Lab.fromRgb(rgb[0], rgb[1], rgb[2]);
        faces[2] = new FaceSample(corrupted, labs, 1f);
        assertNull(SixthFaceSolver.solve(faces));
    }

    /**
     * A scrambled cube puts the missing colour on the five visible faces. Naming those stickers
     * against only the five centres used to force yellow onto orange and kill the inference.
     */
    @Test public void missingColourStickersOnVisibleFacesAreNamed() {
        String state = scrambled("R U2 F' L D B2 R'");
        // This scramble places yellow (D) stickers on U/R/F; skip D and the remaining faces
        // must still recover the exact state.
        FaceSample[] faces = new FaceSample[5];
        int i = 0;
        for (char letter : "URFLB".toCharArray()) faces[i++] = faceOf(state, letter, 0);
        int yellowOnVisible = 0;
        for (FaceSample face : faces) {
            for (CubeColor sticker : face.stickers) if (sticker == CubeColor.YELLOW) yellowOnVisible++;
        }
        assertTrue("the scramble must actually show yellow on the five faces", yellowOnVisible > 0);
        SixthFaceSolver.Result result = SixthFaceSolver.solve(faces);
        assertNotNull(result);
        assertEquals(state, result.state);
    }

    @Test public void infersUnderAWarmExposure() {
        String state = scrambled("L' U F2 R D' B");
        Random rng = new Random(9);
        int recovered = 0;
        for (char missing : "URFDLB".toCharArray()) {
            FaceSample[] faces = new FaceSample[5];
            int i = 0;
            for (char letter : "URFDLB".toCharArray()) {
                if (letter == missing) continue;
                faces[i++] = faceOf(state, letter, rng.nextInt(4), 0.82f);
            }
            SixthFaceSolver.Result result = SixthFaceSolver.solve(faces);
            if (result == null) continue;
            assertEquals("missing face " + missing + " under warm light", state, result.state);
            recovered++;
        }
        assertTrue("warm light should still recover most missing faces, not guess wrongly",
            recovered >= 4);
    }

    @Test public void infersManyRandomScramblesOrRefusesAmbiguity() {
        Random rng = new Random(1);
        String[] moves = {"U", "U'", "U2", "R", "R'", "R2", "F", "F'", "F2",
            "D", "D'", "D2", "L", "L'", "L2", "B", "B'", "B2"};
        int recovered = 0;
        for (int n = 0; n < 24; n++) {
            String state = SOLVED;
            for (int m = 0; m < 25; m++) state = CubeMoves.apply(state, moves[rng.nextInt(moves.length)]);
            char missing = "URFDLB".charAt(n % 6);
            FaceSample[] faces = new FaceSample[5];
            int i = 0;
            for (char letter : "URFDLB".toCharArray()) {
                if (letter == missing) continue;
                faces[i++] = faceOf(state, letter, rng.nextInt(4));
            }
            SixthFaceSolver.Result result = SixthFaceSolver.solve(faces);
            if (result == null) continue;
            assertEquals("scramble " + n + " missing " + missing, state, result.state);
            recovered++;
        }
        assertTrue("unique sixth faces must be recovered; ambiguous ones must be refused",
            recovered >= 16);
    }

    private static FaceSample faceOf(String state, char letter, int rolls, float gain) {
        int base = "URFDLB".indexOf(letter) * 9;
        CubeColor[] stickers = new CubeColor[9];
        float[][] lab = new float[9][];
        for (int cell = 0; cell < 9; cell++) {
            stickers[cell] = CubeColor.fromFace(state.charAt(base + cell));
            int[] rgb = rgbOf(stickers[cell]);
            lab[cell] = Lab.fromRgb(clamp(rgb[0] * gain), clamp(rgb[1] * gain), clamp(rgb[2] * gain));
        }
        FaceSample sample = new FaceSample(stickers, lab, 1f);
        for (int i = 0; i < rolls; i++) sample = sample.rotateClockwise();
        return sample;
    }

    private static int clamp(float value) {
        return (int) Math.max(0, Math.min(255, Math.round(value)));
    }
}
