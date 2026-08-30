package com.mofang.cubear;

import static org.junit.Assert.*;
import cs.min2phase.Tools;
import org.junit.Test;

public class CubeCoreTest {
    private static final String SOLVED =
        "UUUUUUUUURRRRRRRRRFFFFFFFFFDDDDDDDDDLLLLLLLLLBBBBBBBBB";

    @Test public void colorClassifierHandlesStickerPalette() {
        assertEquals(CubeColor.WHITE, CubeColor.classify(240, 240, 235));
        assertEquals(CubeColor.RED, CubeColor.classify(225, 52, 44));
        assertEquals(CubeColor.ORANGE, CubeColor.classify(245, 145, 35));
        assertEquals(CubeColor.YELLOW, CubeColor.classify(240, 225, 32));
        assertEquals(CubeColor.GREEN, CubeColor.classify(73, 190, 65));
        assertEquals(CubeColor.BLUE, CubeColor.classify(45, 105, 205));
        assertEquals(CubeColor.UNKNOWN, CubeColor.classify(30, 30, 30));
    }

    @Test public void everyFaceMoveHasOrderFourAndStaysLegal() {
        for (char face : "URFDLB".toCharArray()) {
            String state = SOLVED;
            for (int i = 0; i < 4; i++) state = CubeMoves.apply(state, String.valueOf(face));
            assertEquals(String.valueOf(face), SOLVED, state);
        }
        String state = SOLVED;
        for (String move : "R U R' U' F2 D L B'".split(" ")) state = CubeMoves.apply(state, move);
        assertEquals(0, Tools.verify(state));
    }

    @Test public void assemblerResolvesIndependentFaceRotations() {
        String state = SOLVED;
        for (String move : "R U2 F' L D B2 R'".split(" ")) state = CubeMoves.apply(state, move);
        CubeStateAssembler assembler = new CubeStateAssembler();
        String order = "URFDLB";
        for (int face = 0; face < 6; face++) {
            CubeColor[] colors = new CubeColor[9];
            for (int i = 0; i < 9; i++) colors[i] = CubeColor.fromFace(state.charAt(face * 9 + i));
            FaceSample sample = new FaceSample(colors, 1f);
            for (int turn = 0; turn < face % 4; turn++) sample = sample.rotateClockwise();
            assembler.put(sample);
        }
        String assembled = assembler.assembleLegalState();
        assertNotNull(assembled);
        assertEquals(0, Tools.verify(assembled));
    }

    @Test public void stabilizerOnlyEmitsOncePerStableSignature() {
        FaceStabilizer stabilizer = new FaceStabilizer(3);
        CubeColor[] stickers = new CubeColor[9];
        java.util.Arrays.fill(stickers, CubeColor.GREEN);
        FaceSample face = new FaceSample(stickers, 1f);
        assertNull(stabilizer.push(face));
        assertNull(stabilizer.push(face));
        assertSame(face, stabilizer.push(face));
        assertNull(stabilizer.push(face));
    }

    @Test public void hungarianFindsTheMinimumCostAssignment() {
        double[][] cost = {{4, 1, 3}, {2, 0, 5}, {3, 2, 2}};
        int[] assignment = Hungarian.solve(cost);
        double total = 0;
        for (int row = 0; row < 3; row++) total += cost[row][assignment[row]];
        assertEquals(5.0, total, 1e-9);
        assertEquals(3, java.util.Arrays.stream(assignment).distinct().count());
    }

    @Test public void hungarianHandlesMoreColumnsThanRows() {
        double[][] cost = {{9, 1, 9, 9}, {9, 9, 9, 2}};
        int[] assignment = Hungarian.solve(cost);
        assertEquals(1, assignment[0]);
        assertEquals(3, assignment[1]);
    }

    @Test public void labSeparatesRedFromOrangeByLightness() {
        float[] red = Lab.fromRgb(216, 65, 47);
        float[] orange = Lab.fromRgb(240, 140, 42);
        // The pair the fixed hue threshold could not split is 25+ units apart in Lab.
        assertTrue(Lab.distance(red, orange) > 25f);
        assertTrue(orange[0] > red[0] + 15f);
        assertTrue(Lab.chroma(Lab.fromRgb(244, 243, 238)) < 12f);
    }

    /**
     * A red sticker under warm light, measured at hue 14 on the recorded frames. The hue threshold
     * calls it orange; in Lab it is still plainly red. This is the exact case that corrupted scans.
     */
    private static final int[] DRIFTED_RED = {233, 95, 53};

    @Test public void driftedRedFoolsTheThresholdButNotLab() {
        assertEquals(CubeColor.ORANGE,
            CubeColor.classify(DRIFTED_RED[0], DRIFTED_RED[1], DRIFTED_RED[2]));
        float[] drifted = Lab.fromRgb(DRIFTED_RED[0], DRIFTED_RED[1], DRIFTED_RED[2]);
        int[] red = rgbOf(CubeColor.RED), orange = rgbOf(CubeColor.ORANGE);
        assertTrue(Lab.distance(drifted, Lab.fromRgb(red[0], red[1], red[2]))
            < Lab.distance(drifted, Lab.fromRgb(orange[0], orange[1], orange[2])));
    }

    /**
     * The failure this rework targets: stickers drift far enough that per-patch classification calls
     * them a neighbouring colour. Solving all 54 at once under the nine-per-colour constraint undoes it.
     */
    @Test public void assignmentRecoversStickersThatDriftIntoTheWrongColor() {
        String state = SOLVED;
        for (String move : "R U2 F' L D B2 R'".split(" ")) state = CubeMoves.apply(state, move);

        FaceSample[] faces = new FaceSample[6];
        int corrupted = 0;
        for (int face = 0; face < 6; face++) {
            CubeColor[] provisional = new CubeColor[9];
            float[][] lab = new float[9][];
            for (int cell = 0; cell < 9; cell++) {
                CubeColor truth = CubeColor.fromFace(state.charAt(face * 9 + cell));
                int[] rgb = rgbOf(truth);
                if (cell != 4 && truth == CubeColor.RED && corrupted < 2) {
                    rgb = DRIFTED_RED;
                    corrupted++;
                }
                lab[cell] = Lab.fromRgb(rgb[0], rgb[1], rgb[2]);
                provisional[cell] = CubeColor.classify(rgb[0], rgb[1], rgb[2]);
            }
            faces[face] = new FaceSample(provisional, lab, 1f);
        }
        assertEquals("test needs two drifted stickers", 2, corrupted);

        ColorAssignment.Result result = ColorAssignment.assign(faces, 0);
        assertNotNull(result);
        java.util.Map<CubeColor, Integer> counts = new java.util.EnumMap<>(CubeColor.class);
        for (int face = 0; face < 6; face++) {
            for (int cell = 0; cell < 9; cell++) {
                CubeColor resolved = result.colors[face][cell];
                counts.merge(resolved, 1, Integer::sum);
                assertEquals("face " + face + " cell " + cell,
                    CubeColor.fromFace(state.charAt(face * 9 + cell)), resolved);
            }
        }
        for (CubeColor color : counts.keySet()) assertEquals(color.toString(), 9, (int) counts.get(color));
    }

    @Test public void assemblerSolvesFromLabReadingsDespiteDriftedStickers() {
        String state = SOLVED;
        for (String move : "L' U F2 R D' B".split(" ")) state = CubeMoves.apply(state, move);

        CubeStateAssembler assembler = new CubeStateAssembler();
        int corrupted = 0;
        for (int face = 0; face < 6; face++) {
            CubeColor[] provisional = new CubeColor[9];
            float[][] lab = new float[9][];
            for (int cell = 0; cell < 9; cell++) {
                CubeColor truth = CubeColor.fromFace(state.charAt(face * 9 + cell));
                int[] rgb = rgbOf(truth);
                if (cell != 4 && truth == CubeColor.RED && corrupted < 3) {
                    rgb = DRIFTED_RED;
                    corrupted++;
                }
                lab[cell] = Lab.fromRgb(rgb[0], rgb[1], rgb[2]);
                provisional[cell] = CubeColor.classify(rgb[0], rgb[1], rgb[2]);
            }
            FaceSample sample = new FaceSample(provisional, lab, 1f);
            for (int turn = 0; turn < face % 4; turn++) sample = sample.rotateClockwise();
            assertTrue("face " + face + " should be new", assembler.put(sample));
        }
        assertEquals(6, assembler.size());

        String assembled = assembler.assembleLegalState();
        assertNotNull("drifted stickers must not block a legal state", assembled);
        assertEquals(0, Tools.verify(assembled));
    }

    /**
     * Repeated looks at one face must not inflate the count past six once the cube's six colours
     * are all present. Grouping errs towards splitting rather than merging on purpose: merging two
     * genuinely different faces would cap the scan below six with no way back, whereas an extra
     * group only costs one failed solve attempt.
     */
    @Test public void repeatedLooksAtOneFaceCollapseOnceAllSixColorsArePresent() {
        CubeStateAssembler assembler = new CubeStateAssembler();
        for (CubeColor color : new CubeColor[]{CubeColor.WHITE, CubeColor.YELLOW, CubeColor.GREEN,
                CubeColor.BLUE, CubeColor.RED, CubeColor.ORANGE}) {
            assembler.put(uniformFace(color, 0));
        }
        assertEquals(6, assembler.size());
        for (int wobble : new int[]{3, 6, 9}) assembler.put(uniformFace(CubeColor.GREEN, wobble));
        assertEquals("rescans must not invent a seventh face", 6, assembler.size());
        assertTrue(assembler.isComplete());
    }

    /** The scan used to cap at five faces whenever two centres fell inside a fixed Lab radius. */
    @Test public void allSixFacesSurviveEvenWhenTwoCentresAreClose() {
        CubeStateAssembler assembler = new CubeStateAssembler();
        for (CubeColor color : new CubeColor[]{CubeColor.WHITE, CubeColor.YELLOW, CubeColor.GREEN,
                CubeColor.BLUE, CubeColor.RED, CubeColor.ORANGE}) {
            assembler.put(uniformFace(color, 0));
        }
        assertEquals("all six centres must stay distinct", 6, assembler.size());
        assertTrue(assembler.isComplete());
    }

    @Test public void cubeRulesRejectImpossiblePieces() {
        assertTrue(CubeRules.areOpposite('U', 'D'));
        assertTrue(CubeRules.areOpposite('R', 'L'));
        assertFalse(CubeRules.areOpposite('U', 'R'));
        assertFalse("a face is not opposite itself", CubeRules.areOpposite('U', 'U'));

        assertTrue(CubeRules.piecesArePlausible(SOLVED));
        String scrambled = SOLVED;
        for (String move : "R U R' F2 D".split(" ")) scrambled = CubeMoves.apply(scrambled, move);
        assertTrue("a legally scrambled cube breaks no piece rule",
            CubeRules.piecesArePlausible(scrambled));
    }

    @Test public void cubeRulesBlameTheStickerThatBreaksACorner() {
        // Corner URF is stickers 8, 9 and 20; forcing 9 to the opposite of 20 makes it impossible.
        char[] broken = SOLVED.toCharArray();
        broken[9] = 'B';
        String state = new String(broken);
        assertFalse(CubeRules.piecesArePlausible(state));
        int[] blame = CubeRules.violations(state);
        assertTrue("the broken corner's stickers take the blame",
            blame[8] > 0 && blame[9] > 0 && blame[20] > 0);
        assertEquals("an untouched corner stays clean", 0, blame[6]);
    }

    @Test public void cubeRulesRejectDuplicatedCenters() {
        char[] broken = SOLVED.toCharArray();
        broken[4] = 'R';                       // U centre now claims to be red as well as R's centre
        assertFalse(CubeRules.centersAreDistinct(new String(broken)));
        assertTrue(CubeRules.centersAreDistinct(SOLVED));
    }

    @Test public void stabilizerHoldsThroughFlickeringColorNames() {
        FaceStabilizer stabilizer = new FaceStabilizer(3);
        // Same physical patches every frame, but the provisional name of one sticker flips.
        FaceSample a = driftingFace(CubeColor.RED, 0f);
        FaceSample b = driftingFace(CubeColor.RED, 3f);
        FaceSample c = driftingFace(CubeColor.RED, 5f);
        assertNull(stabilizer.push(a));
        assertNull(stabilizer.push(b));
        assertNotNull("steady readings must capture despite unstable labels", stabilizer.push(c));
    }

    @Test public void stabilizerEmitsTheAverageOfTheSteadyRun() {
        FaceStabilizer stabilizer = new FaceStabilizer(3);
        assertNull(stabilizer.push(driftingFace(CubeColor.RED, 0f)));
        assertNull(stabilizer.push(driftingFace(CubeColor.RED, 6f)));
        FaceSample emitted = stabilizer.push(driftingFace(CubeColor.RED, 12f));
        assertNotNull(emitted);
        int[] rgb = rgbOf(CubeColor.RED);
        float base = Lab.fromRgb(rgb[0], rgb[1], rgb[2])[0];
        // Readings drifted 0, 6 and 12; the emitted sample must carry their mean, not the last one.
        assertEquals(base + 6f, emitted.lab[4][0], 0.01f);
    }

    @Test public void stabilizerSurvivesTheOccasionalDroppedFrame() {
        FaceStabilizer stabilizer = new FaceStabilizer(3);
        assertNull(stabilizer.push(driftingFace(CubeColor.GREEN, 0f)));
        assertNull("a dropped frame must not restart the run", stabilizer.push(null));
        assertNull(stabilizer.push(driftingFace(CubeColor.GREEN, 2f)));
        assertNotNull(stabilizer.push(driftingFace(CubeColor.GREEN, 4f)));
    }

    @Test public void stabilizerGivesUpAfterASustainedGap() {
        FaceStabilizer stabilizer = new FaceStabilizer(3);
        assertNull(stabilizer.push(driftingFace(CubeColor.GREEN, 0f)));
        assertNull(stabilizer.push(driftingFace(CubeColor.GREEN, 1f)));
        for (int i = 0; i < 3; i++) stabilizer.push(null);
        assertNull("the cube moved away, so the run must restart",
            stabilizer.push(driftingFace(CubeColor.GREEN, 2f)));
    }

    @Test public void stabilizerRejectsAnUnreliableCentre() {
        FaceStabilizer stabilizer = new FaceStabilizer(2);
        FaceSample face = driftingFace(CubeColor.GREEN, 0f);
        boolean[] reliable = new boolean[9];
        java.util.Arrays.fill(reliable, true);
        reliable[4] = false;
        FaceSample blocked = new FaceSample(face.stickers, face.lab, reliable, 1f);
        assertNull(stabilizer.push(blocked));
        assertNull(stabilizer.push(blocked));
    }

    @Test public void paletteNamesLiveFacesWithoutAbsoluteThresholds() {
        String state = SOLVED;
        for (String move : "F R U2 B' L".split(" ")) state = CubeMoves.apply(state, move);
        FaceSample[] faces = new FaceSample[6];
        for (int face = 0; face < 6; face++) {
            CubeColor[] provisional = new CubeColor[9];
            float[][] lab = new float[9][];
            for (int cell = 0; cell < 9; cell++) {
                CubeColor truth = CubeColor.fromFace(state.charAt(face * 9 + cell));
                int[] rgb = truth == CubeColor.RED && cell != 4 ? DRIFTED_RED : rgbOf(truth);
                lab[cell] = Lab.fromRgb(rgb[0], rgb[1], rgb[2]);
                provisional[cell] = CubeColor.classify(rgb[0], rgb[1], rgb[2]);
            }
            faces[face] = new FaceSample(provisional, lab, 1f);
        }
        ScanPalette palette = ScanPalette.from(ColorAssignment.assign(faces, 0));
        assertNotNull(palette);

        // The drifted red patches are named orange by the threshold classifier but red by the palette.
        FaceSample relabelled = palette.relabel(faces[0]);
        for (int cell = 0; cell < 9; cell++) {
            assertEquals("cell " + cell,
                CubeColor.fromFace(state.charAt(cell)), relabelled.stickers[cell]);
        }
    }

    @Test public void paletteLeavesUnreadablePatchesUnknown() {
        FaceSample face = driftingFace(CubeColor.BLUE, 0f);
        boolean[] reliable = new boolean[9];
        java.util.Arrays.fill(reliable, true);
        reliable[2] = false;
        FaceSample[] faces = new FaceSample[6];
        CubeColor[] palette = {CubeColor.WHITE, CubeColor.RED, CubeColor.GREEN,
            CubeColor.YELLOW, CubeColor.ORANGE, CubeColor.BLUE};
        for (int i = 0; i < 6; i++) faces[i] = uniformFace(palette[i], 0);
        ScanPalette scanPalette = ScanPalette.from(ColorAssignment.assign(faces, 0));
        FaceSample relabelled = scanPalette.relabel(
            new FaceSample(face.stickers, face.lab, reliable, 1f));
        assertEquals(CubeColor.UNKNOWN, relabelled.stickers[2]);
        assertEquals(CubeColor.BLUE, relabelled.stickers[4]);
    }

    @Test public void moveMatcherIgnoresUnreadablePatchesButNotWrongOnes() {
        String face = "UUUUUUUUU";
        CubeColor[] stickers = new CubeColor[9];
        java.util.Arrays.fill(stickers, CubeColor.WHITE);
        stickers[3] = CubeColor.UNKNOWN;
        assertTrue("one unreadable patch must not stall the walkthrough",
            CubeMoves.faceMatchesAnyRotation(face, new FaceSample(stickers, 1f)));

        stickers[3] = CubeColor.RED;
        assertFalse("a genuinely different sticker must still fail",
            CubeMoves.faceMatchesAnyRotation(face, new FaceSample(stickers, 1f)));

        CubeColor[] tooFew = new CubeColor[9];
        java.util.Arrays.fill(tooFew, CubeColor.UNKNOWN);
        tooFew[0] = CubeColor.WHITE;
        assertFalse("a mostly unreadable face proves nothing",
            CubeMoves.faceMatchesAnyRotation(face, new FaceSample(tooFew, 1f)));
    }

    private static FaceSample driftingFace(CubeColor color, float drift) {
        CubeColor[] stickers = new CubeColor[9];
        float[][] lab = new float[9][];
        int[] rgb = rgbOf(color);
        float[] base = Lab.fromRgb(rgb[0], rgb[1], rgb[2]);
        for (int i = 0; i < 9; i++) {
            stickers[i] = color;
            lab[i] = new float[]{base[0] + drift, base[1], base[2]};
        }
        return new FaceSample(stickers, lab, 1f);
    }

    @Test public void rotatingAFaceCarriesItsReadingsAlong() {
        CubeColor[] stickers = new CubeColor[9];
        float[][] lab = new float[9][];
        for (int i = 0; i < 9; i++) {
            stickers[i] = CubeColor.values()[i % 6];
            lab[i] = new float[]{i, i, i};
        }
        FaceSample rotated = new FaceSample(stickers, lab, 1f).rotateClockwise();
        assertEquals(stickers[6], rotated.stickers[0]);
        assertEquals(6f, rotated.lab[0][0], 1e-6);
        assertEquals(4f, rotated.centerLab()[0], 1e-6);
    }

    private static FaceSample uniformFace(CubeColor color, int wobble) {
        CubeColor[] stickers = new CubeColor[9];
        float[][] lab = new float[9][];
        int[] rgb = rgbOf(color);
        for (int i = 0; i < 9; i++) {
            stickers[i] = color;
            lab[i] = Lab.fromRgb(clamp(rgb[0] + wobble), clamp(rgb[1] + wobble), clamp(rgb[2] + wobble));
        }
        return new FaceSample(stickers, lab, 1f);
    }

    private static int[] rgbOf(CubeColor color) {
        return new int[]{(color.argb >> 16) & 0xFF, (color.argb >> 8) & 0xFF, color.argb & 0xFF};
    }

    private static int clamp(int value) { return Math.max(0, Math.min(255, value)); }

    @Test public void detectionTrackerBridgesShortDropouts() {
        CubeColor[] stickers = new CubeColor[9];
        java.util.Arrays.fill(stickers, CubeColor.BLUE);
        FaceSample sample = new FaceSample(stickers, 1f);
        DetectionTracker tracker = new DetectionTracker();
        DetectedFace first = new DetectedFace(sample,
            new float[]{10,10, 110,10, 110,110, 10,110}, 200, 300, 1f);
        DetectedFace second = new DetectedFace(sample,
            new float[]{20,20, 120,20, 120,120, 20,120}, 200, 300, 1f);
        assertSame(first, tracker.update(first));
        assertSame("smoothing belongs upstream now", second, tracker.update(second));
        assertSame(second, tracker.update(null));
        tracker.update(null); tracker.update(null); tracker.update(null);
        assertNull(tracker.update(null));
    }

    @Test public void cornerSmootherAveragesSmallMotionAndSnapsOnLargeMotion() {
        CornerSmoother smoother = new CornerSmoother();
        org.opencv.core.Point[] first = quad(0, 0, 100);
        org.opencv.core.Point[] nudged = quad(4, 4, 100);
        assertEquals(0.0, smoother.update(first)[0].x, 1e-6);

        org.opencv.core.Point[] blended = smoother.update(nudged);
        assertTrue("a small nudge is averaged, not followed exactly",
            blended[0].x > 0.0 && blended[0].x < 4.0);

        // A turn of the cube moves corners far; tracking must snap rather than lag behind.
        org.opencv.core.Point[] jumped = quad(90, 90, 100);
        assertEquals(90.0, smoother.update(jumped)[0].x, 1e-6);

        smoother.reset();
        assertEquals(0.0, smoother.update(first)[0].x, 1e-6);
        assertNull(smoother.update(null));
    }

    private static org.opencv.core.Point[] quad(double x, double y, double size) {
        return new org.opencv.core.Point[]{
            new org.opencv.core.Point(x, y), new org.opencv.core.Point(x + size, y),
            new org.opencv.core.Point(x + size, y + size), new org.opencv.core.Point(x, y + size)};
    }
}
