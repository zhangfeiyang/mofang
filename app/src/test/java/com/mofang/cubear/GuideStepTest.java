package com.mofang.cubear;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Checks the hand-relative instructions against a cube simulated from first principles: stickers
 * placed in view space by the frame, layers turned there, and the front face read the way the
 * camera reads it (row-major from the top-left of the picture).
 */
public class GuideStepTest {
    private static final String SOLVED = GuideCube.solved();

    private static String scrambled() {
        String state = SOLVED;
        for (String move : "R U2 F' L D B2 R' F U' L2 D'".split(" ")) state = CubeMoves.apply(state, move);
        return state;
    }

    /** All 24 ways to hold a cube. */
    private static List<CubeFrame> everyFrame() {
        List<CubeFrame> frames = new ArrayList<>();
        for (char face : "URFDLB".toCharArray()) {
            for (int rotation = 0; rotation < 4; rotation++) frames.add(CubeFrame.seen(face, rotation));
        }
        return frames;
    }

    /** View-space sticker map: "x,y,z|nx,ny,nz" → colour letter. */
    private static Map<String, Character> view(String state, CubeFrame frame) {
        Map<String, Character> out = new HashMap<>();
        for (int i = 0; i < 54; i++) {
            int[] pn = GuideCube.stickerPos(i);
            int[] p = frame.toView(new int[]{pn[0], pn[1], pn[2]});
            int[] n = frame.toView(new int[]{pn[3], pn[4], pn[5]});
            out.put(key(p, n), state.charAt(i));
        }
        return out;
    }

    /** The hands at work: turn the stickers of one view-space layer. */
    private static Map<String, Character> turn(Map<String, Character> view, int[] axis, int layer,
                                               int quarters) {
        Map<String, Character> out = new HashMap<>();
        for (Map.Entry<String, Character> e : view.entrySet()) {
            int[][] pn = parse(e.getKey());
            if (GuideStep.turns(layer, CubeFrame.dot(pn[0], axis))) {
                pn[0] = CubeFrame.rotate(pn[0], axis, quarters);
                pn[1] = CubeFrame.rotate(pn[1], axis, quarters);
            }
            out.put(key(pn[0], pn[1]), e.getValue());
        }
        return out;
    }

    /** What the camera reads: the front face, row-major from the top-left of the picture. */
    private static FaceSample cameraReading(Map<String, Character> view) {
        CubeColor[] stickers = new CubeColor[9];
        for (Map.Entry<String, Character> e : view.entrySet()) {
            int[][] pn = parse(e.getKey());
            if (pn[1][2] != 1) continue;
            int row = 1 - pn[0][1], col = pn[0][0] + 1;
            stickers[row * 3 + col] = CubeColor.fromFace(e.getValue());
        }
        return new FaceSample(stickers, 1f);
    }

    private static String key(int[] p, int[] n) {
        return p[0] + "," + p[1] + "," + p[2] + "|" + n[0] + "," + n[1] + "," + n[2];
    }

    private static int[][] parse(String key) {
        String[] halves = key.split("\\|");
        int[][] out = new int[2][3];
        for (int h = 0; h < 2; h++) {
            String[] parts = halves[h].split(",");
            for (int i = 0; i < 3; i++) out[h][i] = Integer.parseInt(parts[i]);
        }
        return out;
    }

    private static String applyAll(String state, List<String> moves, int from, int count) {
        for (int i = from; i < from + count; i++) state = CubeMoves.apply(state, moves.get(i));
        return state;
    }

    @Test public void framesAreRightHandedAndRoundTrip() {
        for (CubeFrame frame : everyFrame()) {
            assertArrayEquals(frame.toString(), frame.right, CubeFrame.cross(frame.up, frame.front));
            assertEquals(frame, CubeFrame.seen(frame.front(), frame.rotation()));
        }
        assertEquals(24, new java.util.HashSet<>(everyFrame()).size());
    }

    /** The rotation bit the tracker reports for a look is exactly how the cube is held. */
    @Test public void aLookRecoversHowTheCubeIsHeld() {
        String state = scrambled();
        for (CubeFrame frame : everyFrame()) {
            FaceSample reading = cameraReading(view(state, frame));
            char face = reading.center().face;
            assertEquals(frame.front(), face);
            int mask = MoveTracker.matchMask(CubeMoves.face(state, face), reading);
            assertEquals(frame.toString(), 1 << frame.rotation(), mask);
            assertEquals(frame, CubeFrame.fromLook(face, mask, true, null));
            assertEquals(frame, CubeFrame.fromLook(face, mask, true, CubeFrame.DEFAULT));
        }
    }

    /** Doing what each step says, in view space, lands exactly on the solver's next state. */
    @Test public void everyStepIsWhatTheHandsDo() {
        String state = scrambled();
        List<String> moves = new ArrayList<>();
        for (char face : "URFDLB".toCharArray()) {
            for (String suffix : new String[]{"", "2", "'"}) moves.add(face + suffix);
        }
        for (CubeFrame frame : everyFrame()) {
            for (String move : moves) {
                assertDoes(state, GuideStep.outer(move, 0, frame), Arrays.asList(move), frame);
                // Holding the layer and turning the rest instead must come to the same cube.
                assertDoes(state, GuideStep.wide(move, 0, frame), Arrays.asList(move), frame);
            }
            for (String[] pair : new String[][]{{"R", "L'"}, {"L'", "R"}, {"U2", "D2"},
                    {"F'", "B"}, {"D", "U'"}, {"B2", "F2"}}) {
                assertTrue(GuideStep.pairsIntoMiddle(pair[0], pair[1]));
                GuideStep step = GuideStep.middle(pair[0], 0, frame);
                assertDoes(state, step, Arrays.asList(pair), frame);
            }
        }
    }

    private static void assertDoes(String state, GuideStep step, List<String> moves, CubeFrame frame) {
        Map<String, Character> done = turn(view(state, frame), step.viewAxis(), step.layer, step.quarters);
        String expected = applyAll(state, moves, 0, moves.size());
        assertEquals(frame + " " + moves, view(expected, step.after), done);
    }

    @Test public void captionsSpeakFromTheHolder() {
        CubeFrame held = CubeFrame.DEFAULT;
        assertStep("右层向上拧", "R", GuideStep.outer("R", 0, held));
        assertStep("右层向下拧", "R′", GuideStep.outer("R'", 0, held));
        assertStep("左层向下拧", "L", GuideStep.outer("L", 0, held));
        assertStep("顶层向左拧", "U", GuideStep.outer("U", 0, held));
        assertStep("底层向右拧", "D", GuideStep.outer("D", 0, held));
        assertStep("前层顺时针拧", "F", GuideStep.outer("F", 0, held));
        assertStep("前层逆时针拧", "F′", GuideStep.outer("F'", 0, held));
        assertStep("后层向左拧", "B", GuideStep.outer("B", 0, held));
        assertStep("右层拧半圈", "R2", GuideStep.outer("R2", 0, held));
        assertFalse(GuideStep.outer("B", 0, held).seen);
        assertTrue(GuideStep.outer("F", 0, held).seen);
        assertTrue(GuideStep.outer("D'", 0, held).seen);
        // The back layer held while the front two turn: the face towards the camera turns.
        assertStep("前两层顺时针拧", "Fw", GuideStep.wide("B", 0, held));
        assertStep("前两层逆时针拧", "Fw′", GuideStep.wide("B'", 0, held));
        assertStep("前两层拧半圈", "Fw2", GuideStep.wide("B2", 0, held));
        assertTrue(GuideStep.wide("B", 0, held).seen);

        // White towards the camera, blue on top: green is now the bottom layer.
        CubeFrame white = CubeFrame.seen('U', 0);
        assertEquals('B', white.faceAt(GuideStep.VIEW_UP));
        assertStep("底层拧半圈", "D2", GuideStep.outer("F2", 0, white));
        assertStep("前层顺时针拧", "F", GuideStep.outer("U", 0, white));
        assertStep("顶层向右拧", "U′", GuideStep.outer("B'", 0, white));
    }

    private static void assertStep(String caption, String notation, GuideStep step) {
        assertEquals(caption, step.caption());
        assertEquals(notation, step.notation());
    }

    @Test public void oppositePairsBecomeOneMiddleTurn() {
        GuideStep m = GuideStep.plan(Arrays.asList("R", "L'"), null, 0, CubeFrame.DEFAULT, 1).get(0);
        assertEquals(2, m.count);
        assertStep("中间竖层向下拧", "M", m);
        // The middle column carried the white centre to the front and blue to the top.
        assertEquals('U', m.after.front());
        assertEquals('B', m.after.faceAt(GuideStep.VIEW_UP));

        GuideStep e = GuideStep.plan(Arrays.asList("U", "D'"), null, 0, CubeFrame.DEFAULT, 1).get(0);
        assertEquals(2, e.count);
        assertStep("中间横层向右拧", "E", e);

        // The slice between front and back is awkward to grip and unseen: two outer turns.
        assertEquals(1, GuideStep.plan(Arrays.asList("F", "B'"), null, 0, CubeFrame.DEFAULT, 1).get(0).count);
        // Held with white towards the camera, U D′ is that slice: a front turn, then the back
        // one done with the front two layers so the camera sees it.
        List<GuideStep> white = GuideStep.plan(Arrays.asList("U", "D'"), null, 0, CubeFrame.seen('U', 0), 5);
        assertEquals(2, white.size());
        assertTrue(white.get(0).isFront());
        assertTrue(white.get(1).isWide());

        assertFalse(GuideStep.pairsIntoMiddle("R", "L"));
        assertFalse(GuideStep.pairsIntoMiddle("R", "R'"));
        assertFalse(GuideStep.pairsIntoMiddle("U2", "D"));
    }

    /**
     * A middle half turn swaps front and back, so every later front move would become a back
     * move the camera cannot confirm: merged only when the rest of the solution comes out ahead.
     */
    @Test public void aMiddleTurnIsUsedOnlyWhenItPaysForItself() {
        List<String> moves = Arrays.asList("L2", "R2", "F", "R'", "F", "U2");
        List<GuideStep> plan = GuideStep.plan(moves, null, 0, CubeFrame.DEFAULT, 10);
        assertEquals(moves.size(), plan.size());
        for (GuideStep step : plan) assertTrue(step.caption(), step.seen && !step.isMiddle());
        // With green at the back the same half turn brings the front moves round to the camera.
        GuideStep first = GuideStep.plan(moves, null, 0, CubeFrame.seen('B', 0), 1).get(0);
        assertTrue(first.isMiddle());
        assertEquals('F', first.after.front());
    }

    @Test public void planWalksTheFrameThroughMiddleTurns() {
        List<String> moves = Arrays.asList("R", "L'", "U");
        List<GuideStep> plan = GuideStep.plan(moves, null, 0, CubeFrame.DEFAULT, 5);
        assertEquals(2, plan.size());
        assertEquals(plan.get(0).after, plan.get(1).frame);
        // After M, white faces the camera: the white layer is now the front layer.
        assertStep("前层顺时针拧", "F", plan.get(1));
    }

    @Test public void readingIsWhatTheCameraSees() {
        String state = scrambled();
        for (CubeFrame frame : everyFrame()) {
            FaceSample camera = cameraReading(view(state, frame));
            StringBuilder seen = new StringBuilder();
            for (CubeColor sticker : camera.stickers) seen.append(sticker.face);
            assertEquals(frame.toString(), seen.toString(), GuideStep.reading(state, frame));
        }
    }

    /** The camera cannot see the back layer, but it sees the front two layers turn. */
    @Test public void aBackTurnIsDoneWithTheFrontTwoLayersWhenThatShows() {
        String state = scrambled();
        List<String> moves = Arrays.asList("B");
        String[] states = {state, CubeMoves.apply(state, "B")};
        GuideStep step = GuideStep.plan(moves, states, 0, CubeFrame.DEFAULT, 1).get(0);
        assertTrue(step.isWide());
        assertTrue(step.seen);
        assertStep("前两层顺时针拧", "Fw", step);
        // Green stays in front; the white centre, carried by the middle layer, is now on the right.
        assertEquals('F', step.after.front());
        assertEquals('U', step.after.faceAt(GuideStep.VIEW_RIGHT));
        assertEquals('L', step.after.faceAt(GuideStep.VIEW_UP));

        // A front face of one colour turns invisibly: then the plain back turn is no worse.
        String plainFront = CubeMoves.apply(GuideCube.solved(), "B'");
        String[] plain = {plainFront, GuideCube.solved()};
        GuideStep back = GuideStep.plan(moves, plain, 0, CubeFrame.DEFAULT, 1).get(0);
        assertTrue(back.isBack());
        assertFalse(back.seen);
    }

    @Test public void ambiguousLooksKeepTheNearestFrame() {
        CubeFrame held = CubeFrame.seen('F', 1);
        // Same face, roll not trusted: nothing changes.
        assertSame(held, CubeFrame.fromLook('F', 0b0001, false, held));
        // Same face, the old roll still fits among several: nothing changes.
        assertSame(held, CubeFrame.fromLook('F', 0b1111, true, held));
        // Same face, a trusted reading a quarter turn off: the cube was re-gripped.
        assertEquals(CubeFrame.seen('F', 2), CubeFrame.fromLook('F', 0b0100, true, held));
        // A uniform face turned to the camera: the single quarter turn from before wins.
        CubeFrame start = CubeFrame.DEFAULT;
        CubeFrame turned = CubeFrame.fromLook('R', 0b1111, true, start);
        assertEquals('R', turned.front());
        assertEquals(1, turned.agreement(start));
        assertEquals('U', turned.faceAt(GuideStep.VIEW_UP));
        // Flipped to show the back: turning about the vertical keeps the top.
        CubeFrame back = CubeFrame.fromLook('B', 0b1111, true, start);
        assertEquals('U', back.faceAt(GuideStep.VIEW_UP));
        assertNull(CubeFrame.fromLook('F', 0, true, start));
    }
}
