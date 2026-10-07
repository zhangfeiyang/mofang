package com.mofang.cubear;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/**
 * A simulated person following the guide: they hold the cube some way, look at it, do exactly
 * what the step says, and look again. Every step the camera can see must confirm itself, and the
 * guide must keep knowing how the cube is held, through middle-layer and front-two-layer turns.
 */
public class GuideSessionTest {
    private static final String[] MOVES = {"U", "U2", "U'", "R", "R2", "R'", "F", "F2", "F'",
        "D", "D2", "D'", "L", "L2", "L'", "B", "B2", "B'"};

    /** What the camera reads off the face towards it. */
    private static FaceSample look(String state, CubeFrame held) {
        String reading = GuideStep.reading(state, held);
        CubeColor[] stickers = new CubeColor[9];
        for (int i = 0; i < 9; i++) stickers[i] = CubeColor.fromFace(reading.charAt(i));
        return new FaceSample(stickers, 1f);
    }

    private static List<String> solve(String state) {
        String result = new cs.min2phase.Search().solution(state, 21, 100_000_000L, 0, 0).trim();
        assertFalse(result, result.startsWith("Error"));
        return result.isEmpty() ? new ArrayList<>() : Arrays.asList(result.split("\\s+"));
    }

    @Test public void followingTheInstructionsWalksThroughRandomSolves() {
        Random random = new Random(20261004);
        int steps = 0, presses = 0, sideLooks = 0, middles = 0, wides = 0, chains = 0, moves = 0;
        int backsWithoutTricks = 0;
        for (int trial = 0; trial < 120; trial++) {
            String state = GuideCube.solved();
            for (int i = 0; i < 25; i++) state = CubeMoves.apply(state, MOVES[random.nextInt(MOVES.length)]);
            List<String> solution = solve(state);
            CubeFrame held = CubeFrame.seen("URFDLB".charAt(random.nextInt(6)), random.nextInt(4));
            for (String move : solution) if (GuideStep.outer(move, 0, held).isBack()) backsWithoutTricks++;
            moves += solution.size();

            GuideSession session = new GuideSession(state, solution, held, true);
            String[] states = session.tracker().states();
            int guard = 0;
            while (!session.done()) {
                assertTrue("stuck", guard++ < 60);
                GuideStep step = session.currentStep();
                assertEquals(held, session.frame());
                // A steady look before turning, then the hands do exactly what the step says.
                session.observe(look(states[step.first], held), true);
                held = step.after;
                GuideSession.Result result =
                    session.observe(look(states[step.first + step.count], held), true);
                steps++;
                if (step.isMiddle()) middles++;
                if (step.isWide()) wides++;
                if (step.then != null) chains++;
                if (step.seen) {
                    assertEquals(trial + ": " + step.caption(), GuideSession.Result.ADVANCED, result);
                    assertEquals(trial + ": " + step.caption() + " at " + step.first + " in " + solution,
                        step.first + step.count, session.tracker().index());
                } else if (step.first + step.count == solution.size()) {
                    // The last turn of a solve, under a front face already one colour: the
                    // user is asked to show any side, which the solved cube confirms.
                    assertNotEquals(GuideSession.Result.ADVANCED, result);
                    CubeFrame side = CubeFrame.seen(held.faceAt(GuideStep.VIEW_RIGHT), 0);
                    result = session.observe(look(states[solution.size()], side), true);
                    assertEquals(trial + ": " + step.caption(), GuideSession.Result.ADVANCED, result);
                    sideLooks++;
                    break;
                } else {
                    assertNotEquals(GuideSession.Result.ADVANCED, result);
                    session.next();
                    presses++;
                }
                assertEquals(trial + ": " + step.caption(), held, session.frame());
            }
        }
        System.out.printf("moves %d, steps %d, middle %d, front-two %d, chained %d, presses %d, "
            + "last turn shown on a side %d (back turns without any of it: %d)%n", moves, steps,
            middles, wides, chains, presses, sideLooks, backsWithoutTricks);
        // Measured: no presses at all in 2376 steps, against 402 back turns the camera could not
        // see; an unseen turn mid-solve is chained to the next, and the last one is shown on a side.
        assertTrue("presses " + presses + " of " + backsWithoutTricks, presses * 20 < backsWithoutTricks);
    }

    /** "上一步" goes back over a middle-layer turn and restores how the cube was held. */
    @Test public void previousUndoesAMiddleTurnAndItsHold() {
        String state = GuideCube.solved();
        for (String move : "L R'".split(" ")) state = CubeMoves.apply(state, move);
        List<String> solution = Arrays.asList("R", "L'");
        GuideSession session = new GuideSession(state, solution, CubeFrame.DEFAULT, true);
        GuideStep m = session.currentStep();
        assertTrue(m.isMiddle());
        session.next();
        assertTrue(session.done());
        assertEquals(m.after, session.frame());
        session.previous();
        assertEquals(0, session.tracker().index());
        assertEquals(CubeFrame.DEFAULT, session.frame());
    }

    /**
     * Someone who turns the back layer anyway, although the step said front two layers: the
     * camera sees nothing, "下一步" assumes the roll, and the next look puts the hold right.
     */
    @Test public void aLookCorrectsTheHoldAfterThePlainBackTurn() {
        Random random = new Random(5);
        String state = GuideCube.solved();
        for (int i = 0; i < 25; i++) state = CubeMoves.apply(state, MOVES[random.nextInt(MOVES.length)]);
        List<String> solution = new ArrayList<>(Arrays.asList("B"));
        String start = CubeMoves.apply(state, "B'");
        GuideSession session = new GuideSession(start, solution.subList(0, 1), CubeFrame.DEFAULT, true);
        GuideStep step = session.currentStep();
        assertTrue(step.isWide());
        session.next();
        assertNotEquals(CubeFrame.DEFAULT, session.frame());
        // The cube was really held still: the look says so.
        assertTrue(session.learn(look(state, CubeFrame.DEFAULT), state, true));
        assertEquals(CubeFrame.DEFAULT, session.frame());
    }
}
